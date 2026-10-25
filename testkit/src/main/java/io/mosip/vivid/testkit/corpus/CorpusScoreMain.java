package io.mosip.vivid.testkit.corpus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosip.vivid.api.LivenessEngine;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.LivenessResult;
import io.mosip.vivid.api.SessionContext;
import io.mosip.vivid.api.SessionState;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.core.action.ActionThresholds;
import io.mosip.vivid.core.session.LivenessOrchestrator;
import io.mosip.vivid.core.session.LivenessSessionFactory;
import io.mosip.vivid.core.session.TimeSource;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.PixelFormat;
import io.mosip.vivid.engine.onnx.OnnxEngineFactory;
import io.mosip.vivid.engine.onnx.OnnxLivenessEngine;
import io.mosip.vivid.telemetry.audit.AuditSink;
import io.mosip.vivid.telemetry.metrics.LivenessMetrics;
import io.mosip.vivid.testkit.metrics.NonResponse;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Turns a recorded corpus into {@code scores.jsonl} for {@link io.mosip.vivid.testkit.metrics.IsoReportMain}.
 * <pre>
 * corpus-score --corpus &lt;dir&gt; --out &lt;scores.jsonl&gt; [--log &lt;capture-log.csv&gt;] [--mode passive|challenge]
 *              [--workflow RESIDENT|OPERATOR|SUPERVISOR] [--fps 15] [--models &lt;dir&gt;] [--detector-threshold x]
 *              [--passive-threshold x]
 * </pre>
 * Every directory under {@code --corpus} that holds frames is one item ({@link CorpusItem}). Each item is replayed
 * through the <b>real</b> {@code liveness-core} orchestrator and the <b>real</b> ONNX engine, one session per item
 * (first attempt only: presentation level). A fake {@link TimeSource} is advanced to each frame's timestamp before the
 * frame is fed (frame clock), so passive windows and timeouts behave as in real time but run as fast as the engine.
 * After the last frame the clock keeps advancing in 200 ms ticks until the session ends (it then times out, which is a
 * non-response).
 * <p>
 * Policy: the workflow's compiled defaults, with {@code active.mode = NEVER} in {@code passive} mode (a recording
 * cannot answer randomly drawn challenges; corpus-ethics.md §10.2) or {@code ALWAYS} in {@code challenge} mode
 * (measures that prompt-ignoring replays do not pass; bona fide recordings will fail too).
 * <p>
 * Label, participant and group tags come from the capture log row of the item (CSV columns of
 * {@code docs/corpus/capture-log-template.csv}); when the item is not in the log they are parsed from the §10.1 item
 * name. Items with neither are skipped with a warning. Exit codes: 0 ok, 2 usage or input error, 3 no models.
 */
public final class CorpusScoreMain {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final long TICK_MILLIS = 200;

    public enum Mode { PASSIVE, CHALLENGE }

    private CorpusScoreMain() {}

    public static void main(String[] args) { System.exit(run(args)); }

    /** Testable entry point; returns the exit code. */
    public static int run(String[] args) {
        Path corpus = null, out = null, log = null, models = null;
        Mode mode = Mode.PASSIVE;
        Workflow workflow = Workflow.RESIDENT;
        double fps = 15, detector = Double.NaN, passive = Double.NaN;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--corpus" -> corpus = Path.of(args[++i]);
                    case "--out" -> out = Path.of(args[++i]);
                    case "--log" -> log = Path.of(args[++i]);
                    case "--models" -> models = Path.of(args[++i]);
                    case "--mode" -> mode = Mode.valueOf(args[++i].toUpperCase(Locale.ROOT));
                    case "--workflow" -> workflow = Workflow.valueOf(args[++i].toUpperCase(Locale.ROOT));
                    case "--fps" -> fps = Double.parseDouble(args[++i]);
                    case "--detector-threshold" -> detector = Double.parseDouble(args[++i]);
                    case "--passive-threshold" -> passive = Double.parseDouble(args[++i]);
                    case "--help", "-h" -> { usage(); return 0; }
                    default -> throw new IllegalArgumentException("unknown argument '" + args[i] + "'");
                }
            }
            if (corpus == null || out == null) throw new IllegalArgumentException("--corpus and --out are required");
            if (!Files.isDirectory(corpus)) throw new IllegalArgumentException("not a directory: " + corpus);
            if (log == null && Files.isRegularFile(corpus.resolve("capture-log.csv"))) log = corpus.resolve("capture-log.csv");
            CaptureLog captureLog = log == null ? CaptureLog.empty() : CaptureLog.read(log);
            List<CorpusItem> items = CorpusItem.discover(corpus, fps);
            if (items.isEmpty()) throw new IllegalArgumentException("no frame folders under " + corpus);

            Path modelDir = models != null ? models : OnnxEngineFactory.findModelDirectory().orElse(null);
            if (modelDir == null || !Files.isDirectory(modelDir)) {
                System.err.println("corpus-score: no model directory (use --models, -Dvivid.models or $MOSIP_VIVID_MODELS)");
                return 3;
            }
            LivenessPolicy policy = policyFor(workflow, mode, passive);
            int written = 0, skipped = 0, noResponse = 0;
            try (LivenessEngine engine = engine(modelDir, detector)) {
                if (out.toAbsolutePath().getParent() != null) Files.createDirectories(out.toAbsolutePath().getParent());
                try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                    w.write("# scores.jsonl written by CorpusScoreMain: mode=" + mode.name().toLowerCase(Locale.ROOT) + " workflow=" + workflow
                            + " policy=" + policy.policyVersion() + " engine=" + engine.info().engineId() + " items=" + items.size() + "\n");
                    for (CorpusItem item : items) {
                        ObjectNode line = score(engine, policy, mode, item, captureLog.row(item.id()));
                        if (line == null) { skipped++; continue; }
                        if (!line.get("response").asBoolean()) noResponse++;
                        w.write(MAPPER.writeValueAsString(line));
                        w.write('\n');
                        written++;
                    }
                }
            }
            System.out.printf(Locale.ROOT, "items=%d written=%d nonResponses=%d skipped=%d mode=%s -> %s%n",
                    items.size(), written, noResponse, skipped, mode.name().toLowerCase(Locale.ROOT), out);
            return written == 0 ? 2 : 0;
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            System.err.println("corpus-score: " + (e instanceof ArrayIndexOutOfBoundsException ? "missing value for last option" : e.getMessage()));
            usage();
            return 2;
        } catch (Exception e) {
            System.err.println("corpus-score: " + e);
            return 2;
        }
    }

    static LivenessEngine engine(Path modelDir, double detectorThreshold) {
        if (Double.isNaN(detectorThreshold)) return new OnnxLivenessEngine(OnnxEngineFactory.configFor(modelDir));
        return new OnnxLivenessEngine(OnnxEngineFactory.discover(modelDir).detectorThresholds(detectorThreshold, 0.3).build());
    }

    /** The workflow's compiled defaults with the replay mode applied (and optionally a different passive threshold). */
    public static LivenessPolicy policyFor(Workflow workflow, Mode mode, double passiveThreshold) {
        LivenessPolicy d = LivenessPolicy.defaults(workflow);
        LivenessPolicy.Builder b = LivenessPolicy.builder(workflow)
                .policyVersion(d.policyVersion() + "+" + (mode == Mode.PASSIVE ? "passive-replay" : "challenge-replay"))
                .enabled(true).passiveThreshold(Double.isNaN(passiveThreshold) ? d.passiveThreshold() : passiveThreshold)
                .padHardThreshold(d.padHardThreshold()).passiveWindowMillis(d.passiveWindowMillis()).passiveMinFrames(d.passiveMinFrames())
                .minChallenges(d.minChallenges()).maxChallenges(d.maxChallenges()).challengePool(d.challengePool())
                .accessibilityPool(d.accessibilityPool()).requireDepthRevealingChallenge(d.requireDepthRevealingChallenge())
                .challengeTimeoutMillis(d.challengeTimeoutMillis()).minActionLatencyMillis(d.minActionLatencyMillis())
                .maxActionLatencyMillis(d.maxActionLatencyMillis()).maxRetries(d.maxRetries()).onMaxRetries(d.onMaxRetries())
                .sessionTimeoutMillis(d.sessionTimeoutMillis()).minFaceQuality(d.minFaceQuality())
                .identityDriftThreshold(d.identityDriftThreshold()).signalWeights(d.signalWeights())
                .requireIdentityContinuity(d.requireIdentityContinuity());
        if (mode == Mode.PASSIVE) b.activeMode(LivenessPolicy.ActiveMode.NEVER);
        else b.activeMode(LivenessPolicy.ActiveMode.ALWAYS).minChallenges(Math.max(1, d.minChallenges()));
        return b.build();
    }

    /** Replay one item and build its scores.jsonl record, or null when its label cannot be determined. */
    static ObjectNode score(LivenessEngine engine, LivenessPolicy policy, Mode mode, CorpusItem item, Map<String, String> row) throws IOException {
        CorpusItem.Name name = CorpusItem.parseName(item.id());
        String label = row != null && !row.getOrDefault("label", "").isBlank() ? row.get("label") : name == null ? null : name.label();
        if (label == null) {
            System.err.println("corpus-score: skipping " + item.dir() + ": not in the capture log and not named <participant>-<label>-<lighting>-<device>-T<take>");
            return null;
        }
        String subject = row != null && !row.getOrDefault("participant_id", "").isBlank() ? row.get("participant_id") : name == null ? null : name.participant();
        Map<String, String> groups = CaptureLog.groups(row);
        if (name != null) { groups.putIfAbsent("lighting", name.lighting()); groups.putIfAbsent("device", name.device()); }

        Replay r = replay(engine, policy, item);
        LivenessResult res = r.result();
        String decision = res.decision().name();
        String failure = res.failure().map(Enum::name).orElse(null);
        ObjectNode n = MAPPER.createObjectNode();
        n.put("id", item.id());
        if (subject != null) n.put("subject", subject);
        n.put("label", label.toUpperCase(Locale.ROOT));
        if (row != null && !row.getOrDefault("attack_class", "").isBlank()) n.put("paiClass", row.get("attack_class"));
        n.put("score", res.fusedLivenessScore());
        n.put("decision", decision);
        if (failure == null) n.putNull("failure"); else n.put("failure", failure);
        n.put("response", !NonResponse.isNonResponse(decision, failure) && !NonResponse.isExcluded(decision, failure));
        res.attackClass().ifPresent(c -> n.put("detectedAttackClass", c));
        res.vetoSignal().ifPresent(v -> n.put("vetoSignal", v));
        if (!res.signalScores().isEmpty()) {
            ObjectNode sig = n.putObject("signals");                 // per-signal attack probability (diagnostic)
            new java.util.TreeMap<>(res.signalScores()).forEach(sig::put);
        }
        n.put("frames", item.frames().size()).put("framesFed", r.framesFed()).put("framesAnalyzed", res.framesAnalyzed());
        n.put("mode", mode.name().toLowerCase(Locale.ROOT)).put("policy", res.policyVersion()).put("engine", res.engineId());
        if (res.modelBundleVersion() != null) n.put("bundle", res.modelBundleVersion());
        ObjectNode g = n.putObject("groups");
        groups.forEach(g::put);
        return n;
    }

    /** Result of one replayed session. */
    public record Replay(LivenessResult result, int framesFed) {}

    /** Replays an item through a fresh orchestrator session driven by a frame clock. */
    public static Replay replay(LivenessEngine engine, LivenessPolicy policy, CorpusItem item) throws IOException {
        AtomicLong clockNanos = new AtomicLong();
        LivenessSessionFactory factory = new LivenessSessionFactory(engine, w -> policy, AuditSink.discarding(), new LivenessMetrics(),
                TimeSource.of(clockNanos::get), new SecureRandom(), ActionThresholds.defaults());
        SessionContext ctx = factory.newContext(policy.workflow(), Locale.ROOT, false, false);
        int fed = 0;
        try (LivenessOrchestrator session = factory.open(policy, ctx)) {
            session.start();
            SessionState st = session.state();
            for (int i = 0; i < item.frames().size() && !done(st); i++) {
                long tNanos = item.timestampsMillis()[i] * 1_000_000L;
                clockNanos.accumulateAndGet(tNanos, Math::max);
                st = session.feed(readFrame(item.frames().get(i), i, tNanos));
                fed++;
                if (!done(st)) st = session.tick();
            }
            // Recording exhausted: let time run on until the session decides or times out.
            long limit = clockNanos.get() + (policy.sessionTimeoutMillis() + 5_000) * 1_000_000L;
            while (!done(st) && clockNanos.get() < limit) {
                clockNanos.addAndGet(TICK_MILLIS * 1_000_000L);
                st = session.tick();
            }
            if (!done(st)) session.cancel();
            return new Replay(session.result(), fed);
        }
    }

    private static boolean done(SessionState st) {
        return switch (st.phase()) { case PASSED, FAILED, LOCKED, CANCELLED -> true; default -> false; };
    }

    /** Decodes a JPEG/PNG into an RGB888 frame. */
    public static FaceFrame readFrame(Path file, long sequence, long timestampNanos) throws IOException {
        BufferedImage img = ImageIO.read(file.toFile());
        if (img == null) throw new IOException("not a decodable image: " + file);
        int w = img.getWidth(), h = img.getHeight();
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        byte[] rgb = new byte[w * h * 3];
        for (int i = 0, j = 0; i < argb.length; i++) {
            int p = argb[i];
            rgb[j++] = (byte) (p >> 16); rgb[j++] = (byte) (p >> 8); rgb[j++] = (byte) p;
        }
        return FaceFrame.builder().sequence(sequence).timestampNanos(timestampNanos).width(w).height(h).format(PixelFormat.RGB888).pixels(rgb).build();
    }

    private static void usage() {
        System.err.println("usage: corpus-score --corpus <dir> --out <scores.jsonl> [--log <capture-log.csv>] [--mode passive|challenge] "
                + "[--workflow RESIDENT|OPERATOR|SUPERVISOR] [--fps 15] [--models <dir>] [--detector-threshold x] [--passive-threshold x]");
    }
}
