package io.mosip.vivid.testkit.corpus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.PixelFormat;
import io.mosip.vivid.device.mock.scenario.Scenarios;
import io.mosip.vivid.testkit.bench.LatencyBenchmark;
import io.mosip.vivid.testkit.metrics.IsoReportMain;
import io.mosip.vivid.testkit.metrics.PadSample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CorpusScoreMainTest {
    static final Path MODELS = Path.of("..", "liveness-engine-onnx", "models").toAbsolutePath().normalize();
    static final Path TEMPLATE = Path.of("..", "docs", "corpus", "capture-log-template.csv");

    static boolean modelsPresent() throws Exception {
        if (!Files.isDirectory(MODELS)) return false;
        try (var s = Files.list(MODELS)) { return s.anyMatch(p -> { String n = p.getFileName().toString(); return n.contains("yunet") && n.endsWith(".onnx"); }); }
    }

    @Test
    void captureLogTemplateParsesAndGroupsRespectDemographicConsent() throws Exception {
        CaptureLog log = CaptureLog.read(TEMPLATE);
        assertThat(log.size()).isEqualTo(4);
        Map<String, String> row = log.row("P01-BONA_FIDE-L1-D1-T1");
        assertThat(row.get("label")).isEqualTo("BONA_FIDE");
        assertThat(CaptureLog.groups(row)).containsExactlyInAnyOrderEntriesOf(Map.of("lighting", "L1", "device", "D1",
                "skinToneBand", "4-7", "ageBand", "18-29", "eyewear", "none", "headCovering", "none", "facialHair", "none"));
        Map<String, String> noConsent = new java.util.HashMap<>(row);
        noConsent.put("consent_demographics", "N");
        assertThat(CaptureLog.groups(noConsent)).containsOnlyKeys("lighting", "device");
        assertThat(CaptureLog.split("a,\"b,c\",\"d\"\"e\",")).containsExactly("a", "b,c", "d\"e", "");
    }

    @Test
    void itemNamesFramesAndTimestamps(@TempDir Path dir) throws Exception {
        assertThat(CorpusItem.parseName("P07-VIDEO_REPLAY_PHONE-L2-D1-T1"))
                .isEqualTo(new CorpusItem.Name("P07", "VIDEO_REPLAY_PHONE", "L2", "D1", 1));
        assertThat(CorpusItem.parseName("random-folder")).isNull();

        Path a = Files.createDirectories(dir.resolve("frames/P01-BONA_FIDE-L1-D1-T1"));
        for (int i = 1; i <= 3; i++) writePng(a.resolve(String.format("%05d.png", i)), 0x204080);
        Path b = Files.createDirectories(dir.resolve("frames/P01-PRINT_A4_MATTE-L1-D1-T1"));
        for (int i = 1; i <= 3; i++) writePng(b.resolve(String.format("%05d.png", i)), 0x804020);
        Files.writeString(b.resolve("timestamps.txt"), "# file millis\n00001.png 1000\n00002.png 1040\n00003.png 1100\n");
        Files.writeString(dir.resolve("frames/notes.txt"), "not an item");

        List<CorpusItem> items = CorpusItem.discover(dir, 15);
        assertThat(items).extracting(CorpusItem::id).containsExactly("P01-BONA_FIDE-L1-D1-T1", "P01-PRINT_A4_MATTE-L1-D1-T1");
        assertThat(items.get(0).timestampsMillis()).containsExactly(0, 67, 133);          // 15 fps fallback
        assertThat(items.get(1).timestampsMillis()).containsExactly(0, 40, 100);          // relative to the first frame

        FaceFrame f = CorpusScoreMain.readFrame(items.get(0).frames().get(0), 7, 123);
        assertThat(f.format()).isEqualTo(PixelFormat.RGB888);
        assertThat(f.width()).isEqualTo(8); assertThat(f.height()).isEqualTo(6);
        ByteBuffer px = f.pixels();
        assertThat(new int[] {px.get() & 0xff, px.get() & 0xff, px.get() & 0xff}).containsExactly(0x20, 0x40, 0x80);

        Files.writeString(b.resolve("timestamps.txt"), "100\n50\n200\n");
        assertThatThrownBy(() -> CorpusItem.load(b, 15)).hasMessageContaining("decrease");
    }

    @Test
    void passivePolicyNeverChallengesAndChallengePolicyAlwaysDoes() {
        LivenessPolicy p = CorpusScoreMain.policyFor(Workflow.RESIDENT, CorpusScoreMain.Mode.PASSIVE, Double.NaN);
        LivenessPolicy d = LivenessPolicy.defaults(Workflow.RESIDENT);
        assertThat(p.activeMode()).isEqualTo(LivenessPolicy.ActiveMode.NEVER);
        assertThat(p.passiveThreshold()).isEqualTo(d.passiveThreshold());
        assertThat(p.padHardThreshold()).isEqualTo(d.padHardThreshold());
        assertThat(p.policyVersion()).endsWith("+passive-replay");
        LivenessPolicy c = CorpusScoreMain.policyFor(Workflow.OPERATOR, CorpusScoreMain.Mode.CHALLENGE, 0.7);
        assertThat(c.activeMode()).isEqualTo(LivenessPolicy.ActiveMode.ALWAYS);
        assertThat(c.passiveThreshold()).isEqualTo(0.7);
    }

    @Test
    void usageErrorsAndMissingModels(@TempDir Path dir) throws Exception {
        assertThat(CorpusScoreMain.run(new String[] {"--corpus", dir.toString()})).isEqualTo(2);
        assertThat(CorpusScoreMain.run(new String[] {"--corpus", dir.toString(), "--out", dir.resolve("s.jsonl").toString()})).isEqualTo(2); // no items
        Path item = Files.createDirectories(dir.resolve("P01-BONA_FIDE-L1-D1-T1"));
        writePng(item.resolve("00001.png"), 0);
        assertThat(CorpusScoreMain.run(new String[] {"--corpus", dir.toString(), "--out", dir.resolve("s.jsonl").toString(),
                "--models", dir.resolve("nope").toString()})).isEqualTo(3);
    }

    /**
     * End to end on a tiny synthetic corpus rendered by device-mock (cartoon faces, no real people): corpus folders ->
     * CorpusScoreMain (real orchestrator + real ONNX engine) -> scores.jsonl -> IsoReportMain. Skipped without models.
     */
    @Test
    void scoresSyntheticCorpusWithRealPipeline(@TempDir Path dir) throws Exception {
        assumeTrue(modelsPresent(), "ONNX models not installed; see liveness-engine-onnx/models/README.md");
        Path corpus = dir.resolve("corpus");
        render(corpus.resolve("frames/P01-BONA_FIDE-L1-D1-T1"), "live-pass", 60);
        render(corpus.resolve("frames/P01-PRINT_A4_MATTE-L1-D1-T1"), "print-attack", 60);
        render(corpus.resolve("frames/P02-BONA_FIDE-L3-D1-T1"), "no-face", 30);   // no face in the first 4 s -> times out
        Files.writeString(corpus.resolve("capture-log.csv"), String.join("\n",
                "# synthetic",
                "item_id,participant_id,label,attack_class,lighting,device,take,consent_demographics,mst_band,age_band,eyewear",
                "P01-BONA_FIDE-L1-D1-T1,P01,BONA_FIDE,NONE,L1,D1,1,Y,4-7,18-29,none",
                "P01-PRINT_A4_MATTE-L1-D1-T1,P01,PRINT_A4_MATTE,PRINT,L1,D1,1,Y,4-7,18-29,none") + "\n");
        Path scores = dir.resolve("scores.jsonl");
        int code = CorpusScoreMain.run(new String[] {"--corpus", corpus.toString(), "--out", scores.toString(),
                "--models", MODELS.toString(), "--detector-threshold", "0.35"});
        assertThat(code).isZero();

        ObjectMapper om = new ObjectMapper();
        List<JsonNode> lines = new ArrayList<>();
        for (String l : Files.readAllLines(scores)) if (!l.startsWith("#")) lines.add(om.readTree(l));
        assertThat(lines).extracting(j -> j.get("id").asText())
                .containsExactly("P01-BONA_FIDE-L1-D1-T1", "P01-PRINT_A4_MATTE-L1-D1-T1", "P02-BONA_FIDE-L3-D1-T1");
        for (JsonNode j : lines) {
            assertThat(j.get("score").asDouble()).isBetween(0.0, 1.0);
            assertThat(j.get("decision").asText()).isIn("LIVE", "NOT_LIVE", "ATTACK", "ERROR");
            assertThat(j.get("mode").asText()).isEqualTo("passive");
            assertThat(j.get("framesFed").asInt()).isPositive();
        }
        JsonNode print = lines.get(1);
        assertThat(print.get("label").asText()).isEqualTo("PRINT_A4_MATTE");
        assertThat(print.get("paiClass").asText()).isEqualTo("PRINT");
        assertThat(print.get("subject").asText()).isEqualTo("P01");
        assertThat(print.at("/groups/skinToneBand").asText()).isEqualTo("4-7");
        assertThat(print.get("decision").asText()).as("a print must never be accepted").isNotEqualTo("LIVE");
        assertThat(lines.get(0).get("signals").isObject()).as("per-signal diagnostics: %s", lines.get(0)).isTrue();
        assertThat(lines.get(0).get("signals").fieldNames()).toIterable().contains("moire", "color_stats");
        JsonNode noFace = lines.get(2);                                  // not in the log: label/groups from the item name
        assertThat(noFace.get("label").asText()).isEqualTo("BONA_FIDE");
        assertThat(noFace.at("/groups/lighting").asText()).isEqualTo("L3");
        assertThat(noFace.has("skinToneBand")).isFalse();
        assertThat(noFace.get("response").asBoolean()).as("no face ever detected is a non-response: %s", noFace).isFalse();

        // the file is valid input for IsoReportMain
        List<PadSample> samples = IsoReportMain.readSamples(scores);
        assertThat(samples).hasSize(3);
        assertThat(samples.get(2).responded()).isFalse();
        boolean computable = samples.stream().anyMatch(s -> s.isBonaFide() && s.responded());
        if (computable) {
            Path out = dir.resolve("report");
            assertThat(IsoReportMain.run(new String[] {"--scores", scores.toString(), "--out", out.toString()})).isZero();
            assertThat(out.resolve("iso30107.json")).exists();
            assertThat(out.resolve("iso30107.html")).exists();
        }
    }

    /** FrameTraceMain on a synthetic clip: one row per frame, the pitch baseline freezes after the calibration time. */
    @Test
    void frameTraceWritesOneRowPerFrame(@TempDir Path dir) throws Exception {
        assumeTrue(modelsPresent(), "ONNX models not installed; see liveness-engine-onnx/models/README.md");
        render(dir.resolve("corpus/P01-BONA_FIDE-L1-D1-T1"), "live-pass", 40);
        Path out = dir.resolve("trace.csv");
        assertThat(FrameTraceMain.run(new String[] {"--corpus", dir.resolve("corpus").toString(), "--out", out.toString(),
                "--models", MODELS.toString(), "--detector-threshold", "0.35", "--calib-ms", "1000"})).isZero();
        List<String> rows = Files.readAllLines(out);
        assertThat(rows).hasSize(41);
        List<String> header = List.of(rows.get(0).split(","));
        assertThat(header).contains("pitch", "pitch_abs", "nose_drop", "gaze_y", "moire_p", "moire_c", "cnn_texture_p");
        int frozen = header.indexOf("frozen"), t = header.indexOf("t_ms");
        for (String r : rows.subList(1, rows.size())) {
            String[] c = r.split(",", -1);
            assertThat(c).hasSize(header.size());
            assertThat(c[frozen]).isEqualTo(Long.parseLong(c[t]) >= 1000 ? "1" : "0");
        }
        assertThat(FrameTraceMain.run(new String[] {"--corpus", dir.toString()})).isEqualTo(2);
    }

    static void render(Path dir, String scenario, int frames) throws Exception {
        Files.createDirectories(dir);
        List<FaceFrame> fs = LatencyBenchmark.renderFrames(Scenarios.bundled(scenario), frames, PixelFormat.MJPEG);
        StringBuilder ts = new StringBuilder();
        long t0 = fs.get(0).timestampNanos();
        for (int i = 0; i < fs.size(); i++) {
            FaceFrame f = fs.get(i);
            byte[] jpg = new byte[f.pixels().remaining()];
            f.pixels().get(jpg);
            String name = String.format("%05d.jpg", i + 1);
            Files.write(dir.resolve(name), jpg);
            ts.append(name).append(' ').append((f.timestampNanos() - t0) / 1_000_000).append('\n');
        }
        Files.writeString(dir.resolve(CorpusItem.TIMESTAMPS_FILE), ts.toString());
    }

    static void writePng(Path p, int rgb) throws Exception {
        BufferedImage img = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 6; y++) for (int x = 0; x < 8; x++) img.setRGB(x, y, rgb);
        ImageIO.write(img, "png", p.toFile());
    }
}
