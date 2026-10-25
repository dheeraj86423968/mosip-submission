package io.mosip.vivid.testkit.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosip.vivid.api.FrameAnalysis;
import io.mosip.vivid.api.FrameAnalyzer;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.SessionContext;
import io.mosip.vivid.api.SessionState;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.core.action.ActionThresholds;
import io.mosip.vivid.core.session.LivenessOrchestrator;
import io.mosip.vivid.core.session.LivenessSessionFactory;
import io.mosip.vivid.core.session.TimeSource;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.PixelFormat;
import io.mosip.vivid.device.mock.scenario.Scenarios;
import io.mosip.vivid.engine.onnx.EngineConfig;
import io.mosip.vivid.engine.onnx.ExecutionProvider;
import io.mosip.vivid.engine.onnx.OnnxEngineFactory;
import io.mosip.vivid.engine.onnx.OnnxLivenessEngine;
import io.mosip.vivid.telemetry.audit.AuditSink;
import io.mosip.vivid.telemetry.metrics.LivenessMetrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-frame latency of the ONNX engine alone and of the full orchestrator (real {@code liveness-core} session over
 * the real engine), with an SFace embedding on every frame (K=1) and at the default cadence (K=5). Meant to be run
 * under {@code taskset} and a small {@code -Xmx} as a proxy for low-end hardware; see
 * {@code docs/testing/performance.md} and {@code tools/perf-lowres.sh}.
 *
 * <pre>
 * java -Xmx256m -cp ... io.mosip.vivid.testkit.bench.PerfBenchMain [--models dir] [--frames 300] [--warmup 30] [--pool 48]
 *      [--threads 1] [--scenario live-blink-smile-turn] [--format RGB888|MJPEG] [--detector-threshold 0.35]
 *      [--provider cpu] [--label 1core-256m] [--json out.json]
 * </pre>
 * Frames are rendered by {@code device-mock} before timing starts. The drawn faces need a detector threshold of
 * 0.35 to be detected by YuNet (the production default is 0.7), so every frame takes the full face path.
 * Exit codes: 0 ok, 2 usage, 3 no models.
 */
public final class PerfBenchMain {
    private PerfBenchMain() {}

    public static void main(String[] args) throws Exception {
        Path models = null, json = null;
        int frames = 300, warmup = 30, threads = 1, pool = 48;
        String scenario = "live-blink-smile-turn", label = "", provider = "cpu";
        PixelFormat format = PixelFormat.RGB888;
        double detector = 0.35;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (i + 1 >= args.length && a.startsWith("--")) { usage(); return; }
            switch (a) {
                case "--models" -> models = Path.of(args[++i]);
                case "--frames" -> frames = Integer.parseInt(args[++i]);
                case "--warmup" -> warmup = Integer.parseInt(args[++i]);
                case "--threads" -> threads = Integer.parseInt(args[++i]);
                case "--pool" -> pool = Integer.parseInt(args[++i]);
                case "--scenario" -> scenario = args[++i];
                case "--format" -> format = PixelFormat.valueOf(args[++i].toUpperCase(Locale.ROOT));
                case "--detector-threshold" -> detector = Double.parseDouble(args[++i]);
                case "--provider" -> provider = args[++i];
                case "--label" -> label = args[++i];
                case "--json" -> json = Path.of(args[++i]);
                default -> { usage(); return; }
            }
        }
        Path dir = models != null ? models : OnnxEngineFactory.findModelDirectory().orElse(null);
        if (dir == null || !Files.isDirectory(dir)) { System.err.println("no model directory (use --models)"); System.exit(3); }

        // A small pool of distinct frames is rendered once and cycled with fresh sequence numbers and timestamps,
        // so the harness itself fits in a 256 MiB heap (300 RGB888 VGA frames alone would need ~270 MiB).
        List<FaceFrame> pooled = LatencyBenchmark.renderFrames(Scenarios.bundled(scenario), Math.max(1, Math.min(pool, frames + warmup)), format);
        List<FaceFrame> rendered = new ArrayList<>(frames + warmup);
        long frameNanos = 1_000_000_000L / 15;
        for (int i = 0; i < frames + warmup; i++) {
            FaceFrame src = pooled.get(i % pooled.size());
            rendered.add(FaceFrame.builder().sequence(i).timestampNanos((i + 1) * frameNanos).width(src.width()).height(src.height())
                    .format(src.format()).pixels(src.pixels()).build());
        }
        ObjectNode root = new ObjectMapper().createObjectNode();
        root.put("label", label).put("availableProcessors", Runtime.getRuntime().availableProcessors())
                .put("maxHeapMiB", Runtime.getRuntime().maxMemory() >> 20).put("ortThreads", threads)
                .put("frames", frames).put("warmup", warmup).put("distinctFrames", pooled.size()).put("scenario", scenario).put("format", format.name())
                .put("width", rendered.get(0).width()).put("height", rendered.get(0).height())
                .put("java", System.getProperty("java.version")).put("provider", provider);
        ArrayNode results = root.putArray("results");
        System.out.printf(Locale.ROOT, "# %s cpus=%d maxHeap=%dMiB ortThreads=%d frames=%d %dx%d %s%n", label,
                Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() >> 20, threads, frames,
                rendered.get(0).width(), rendered.get(0).height(), format);

        for (int k : new int[] {1, 5}) {
            EngineConfig cfg = OnnxEngineFactory.discover(dir).threads(threads).embeddingEveryFrames(k)
                    .detectorThresholds(detector, 0.3).executionProvider(ExecutionProvider.parse(provider)).build();
            try (OnnxLivenessEngine engine = new OnnxLivenessEngine(cfg)) {
                results.add(engineRun(engine, rendered, warmup, k));
                results.add(orchestratorRun(engine, rendered, warmup, k));
            }
        }
        root.put("peakRssMiB", peakRssMiB());
        System.out.printf(Locale.ROOT, "# peak RSS %d MiB (Java heap max %d MiB; ONNX Runtime memory is native)%n", peakRssMiB(), Runtime.getRuntime().maxMemory() >> 20);
        if (json != null) {
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(json.toFile(), root);
        }
    }

    private static ObjectNode engineRun(OnnxLivenessEngine engine, List<FaceFrame> frames, int warmup, int k) {
        try (FrameAnalyzer analyzer = engine.newAnalyzer()) {
            int n = frames.size() - warmup;
            long[] all = new long[n];
            List<Long> withEmb = new ArrayList<>(), withoutEmb = new ArrayList<>();
            int faces = 0;
            long t0all = 0;
            for (int i = 0; i < frames.size(); i++) {
                if (i == warmup) t0all = System.nanoTime();
                long t0 = System.nanoTime();
                FrameAnalysis a = analyzer.analyze(frames.get(i));
                long dt = System.nanoTime() - t0;
                if (i < warmup) continue;
                all[i - warmup] = dt;
                if (a.face().isPresent()) faces++;
                (a.embedding().isPresent() ? withEmb : withoutEmb).add(dt);
            }
            long elapsed = System.nanoTime() - t0all;
            ObjectNode r = stats("engine", k, new LatencyStats(all), elapsed, n);
            r.put("faceFraction", faces / (double) n).put("embeddedFrames", withEmb.size());
            if (k > 1 && !withEmb.isEmpty() && !withoutEmb.isEmpty()) {
                r.set("framesWithEmbedding", statsOnly(withEmb));
                r.set("framesWithoutEmbedding", statsOnly(withoutEmb));
            }
            print(r);
            return r;
        }
    }

    private static ObjectNode orchestratorRun(OnnxLivenessEngine engine, List<FaceFrame> frames, int warmup, int k) throws Exception {
        LivenessPolicy policy = LivenessPolicy.defaults(Workflow.RESIDENT);
        AtomicLong clock = new AtomicLong();
        LivenessSessionFactory factory = new LivenessSessionFactory(engine, w -> policy, AuditSink.discarding(), new LivenessMetrics(),
                TimeSource.of(clock::get), new SecureRandom(), ActionThresholds.defaults());
        int n = frames.size() - warmup;
        long[] all = new long[n];
        int sessions = 0;
        long frameNanos = 1_000_000_000L / 15;
        LivenessOrchestrator session = null;
        long elapsed = 0;
        try {
            for (int i = 0; i < frames.size(); i++) {
                if (session == null) {
                    SessionContext ctx = factory.newContext(Workflow.RESIDENT, Locale.ROOT, false, false);
                    session = factory.open(policy, ctx);
                    session.start();
                    sessions++;
                }
                long ts = clock.addAndGet(frameNanos);
                FaceFrame src = frames.get(i);
                FaceFrame f = FaceFrame.builder().sequence(i).timestampNanos(ts).width(src.width()).height(src.height())
                        .format(src.format()).pixels(src.pixels()).build();
                long t0 = System.nanoTime();
                SessionState st = session.feed(f);
                if (!terminal(st)) st = session.tick();
                long dt = System.nanoTime() - t0;
                if (i >= warmup) { all[i - warmup] = dt; elapsed += dt; }
                if (terminal(st)) { session.close(); session = null; }
            }
        } finally {
            if (session != null) { session.cancel(); session.close(); }
        }
        ObjectNode r = stats("orchestrator", k, new LatencyStats(all), elapsed, n);
        r.put("sessions", sessions);
        print(r);
        return r;
    }

    private static boolean terminal(SessionState st) {
        return switch (st.phase()) { case PASSED, FAILED, LOCKED, CANCELLED -> true; default -> false; };
    }

    private static ObjectNode stats(String what, int k, LatencyStats s, long elapsedNanos, int n) {
        ObjectNode r = new ObjectMapper().createObjectNode();
        r.put("pipeline", what).put("embeddingEveryFrames", k);
        r.put("medianMs", ms(s.p50())).put("p90Ms", ms(s.percentile(90))).put("p99Ms", ms(s.p99()))
                .put("meanMs", s.mean() / 1e6).put("fps", n / (elapsedNanos / 1e9));
        return r;
    }

    private static ObjectNode statsOnly(List<Long> v) {
        long[] a = v.stream().mapToLong(Long::longValue).toArray();
        LatencyStats s = new LatencyStats(a);
        ObjectNode o = new ObjectMapper().createObjectNode();
        o.put("count", a.length).put("medianMs", ms(s.p50())).put("p90Ms", ms(s.percentile(90)));
        return o;
    }

    private static double ms(long nanos) { return Math.round(nanos / 1e4) / 100.0; }

    private static void print(ObjectNode r) {
        StringBuilder extra = new StringBuilder();
        if (r.has("framesWithoutEmbedding")) {
            extra.append(String.format(Locale.ROOT, "  [no-emb frames median %.1f p90 %.1f | emb frames median %.1f p90 %.1f]",
                    r.get("framesWithoutEmbedding").get("medianMs").asDouble(), r.get("framesWithoutEmbedding").get("p90Ms").asDouble(),
                    r.get("framesWithEmbedding").get("medianMs").asDouble(), r.get("framesWithEmbedding").get("p90Ms").asDouble()));
        }
        if (r.has("faceFraction")) extra.append(String.format(Locale.ROOT, " faces=%.2f", r.get("faceFraction").asDouble()));
        if (r.has("sessions")) extra.append(" sessions=").append(r.get("sessions").asInt());
        System.out.printf(Locale.ROOT, "%-12s K=%d  median %6.1f ms  p90 %6.1f ms  p99 %6.1f ms  %5.1f fps%s%n",
                r.get("pipeline").asText(), r.get("embeddingEveryFrames").asInt(), r.get("medianMs").asDouble(),
                r.get("p90Ms").asDouble(), r.get("p99Ms").asDouble(), r.get("fps").asDouble(), extra);
    }

    /** Peak resident set size (VmHWM) on Linux, -1 elsewhere. */
    static long peakRssMiB() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmHWM:")) return Long.parseLong(line.replaceAll("[^0-9]", "")) >> 10;
            }
        } catch (IOException | RuntimeException ignored) { }
        return -1;
    }

    private static void usage() {
        System.err.println("usage: PerfBenchMain [--models dir] [--frames 300] [--warmup 30] [--pool 48] [--threads 1] [--scenario name]"
                + " [--format RGB888|MJPEG] [--detector-threshold 0.35] [--provider cpu] [--label text] [--json out.json]");
        System.exit(2);
    }
}
