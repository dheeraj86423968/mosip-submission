package io.mosip.vivid.testkit.corpus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PadReplayMainTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void usageErrors() {
        assertThat(PadReplayMain.run(new String[0])).isEqualTo(2);
        assertThat(PadReplayMain.run(new String[] {"nope"})).isEqualTo(2);
        assertThat(PadReplayMain.run(new String[] {"replay", "--dump", "x"})).isEqualTo(2);
    }

    /** Replaying stored analyses without overrides gives exactly what CorpusScoreMain gives on the frames. */
    @Test
    void replayWithoutOverridesEqualsCorpusScoreMain(@TempDir Path dir) throws Exception {
        assumeTrue(CorpusScoreMainTest.modelsPresent(), "ONNX models not installed; see liveness-engine-onnx/models/README.md");
        Path corpus = dir.resolve("corpus");
        CorpusScoreMainTest.render(corpus.resolve("P01-BONA_FIDE-L1-D1-T1"), "live-pass", 50);
        CorpusScoreMainTest.render(corpus.resolve("P01-PRINT-L1-D1-T1"), "print-attack", 50);
        String models = CorpusScoreMainTest.MODELS.toString();
        Path csm = dir.resolve("csm.jsonl");
        assertThat(CorpusScoreMain.run(new String[] {"--corpus", corpus.toString(), "--out", csm.toString(), "--models", models})).isZero();
        Path dump = dir.resolve("dump.jsonl.gz");
        assertThat(PadReplayMain.run(new String[] {"dump", "--corpus", corpus.toString(), "--out", dump.toString(), "--models", models,
                "--view", "A=antispoof_minifasnetv2se_128.onnx", "--features", dir.resolve("frames.csv").toString()})).isZero();
        Path cfg = dir.resolve("cfg.jsonl");
        Files.writeString(cfg, "{\"name\":\"stored\"}\n{\"name\":\"view\",\"s1\":[\"A\"]}\n{\"name\":\"strict\",\"passiveThreshold\":1.0}\n");
        assertThat(PadReplayMain.run(new String[] {"replay", "--dump", dump.toString(), "--configs", cfg.toString(), "--out-dir", dir.resolve("r").toString()})).isZero();

        Map<String, JsonNode> expected = read(csm);
        for (String name : List.of("stored", "view")) {
            Map<String, JsonNode> got = read(dir.resolve("r/" + name + ".jsonl"));
            assertThat(got.keySet()).isEqualTo(expected.keySet());
            for (String id : expected.keySet()) {
                assertThat(got.get(id).get("decision")).as(name + " " + id).isEqualTo(expected.get(id).get("decision"));
                assertThat(got.get(id).get("failure")).as(name + " " + id).isEqualTo(expected.get(id).get("failure"));
                // the view is recomputed from the stored (normalised) face box, so allow float rounding
                assertThat(got.get(id).get("score").asDouble()).as(name + " " + id)
                        .isCloseTo(expected.get(id).get("score").asDouble(), org.assertj.core.data.Offset.offset(1e-6));
            }
        }
        // a threshold of 1 never accepts
        read(dir.resolve("r/strict.jsonl")).values().forEach(n -> assertThat(n.get("decision").asText()).isNotEqualTo("LIVE"));
        assertThat(Files.readAllLines(dir.resolve("frames.csv")).get(0)).contains("preal_A");
    }

    private static Map<String, JsonNode> read(Path p) throws Exception {
        Map<String, JsonNode> m = new HashMap<>();
        for (String l : Files.readAllLines(p)) {
            if (l.isBlank() || l.startsWith("#")) continue;
            JsonNode n = MAPPER.readTree(l);
            m.put(n.get("id").asText(), n);
        }
        return m;
    }
}
