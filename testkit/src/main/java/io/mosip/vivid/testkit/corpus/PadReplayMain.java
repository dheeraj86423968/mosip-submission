package io.mosip.vivid.testkit.corpus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.EngineInfo;
import io.mosip.vivid.api.FaceObservation;
import io.mosip.vivid.api.FaceQuality;
import io.mosip.vivid.api.FrameAnalysis;
import io.mosip.vivid.api.FrameAnalyzer;
import io.mosip.vivid.api.LivenessEngine;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.LivenessResult;
import io.mosip.vivid.api.SessionContext;
import io.mosip.vivid.api.SessionState;
import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.core.action.ActionThresholds;
import io.mosip.vivid.core.session.LivenessOrchestrator;
import io.mosip.vivid.core.session.LivenessSessionFactory;
import io.mosip.vivid.core.session.TimeSource;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.PixelFormat;
import io.mosip.vivid.engine.detect.FaceDetection;
import io.mosip.vivid.engine.image.ImageOps;
import io.mosip.vivid.engine.image.RgbImage;
import io.mosip.vivid.engine.onnx.AntiSpoofModelConfig;
import io.mosip.vivid.engine.onnx.OnnxFrameAnalyzer;
import io.mosip.vivid.engine.onnx.OnnxLivenessEngine;
import io.mosip.vivid.engine.onnx.OrtModel;
import io.mosip.vivid.engine.signal.CnnTextureSignal;
import io.mosip.vivid.engine.signal.FaceContext;
import io.mosip.vivid.engine.signal.PlattCalibration;
import io.mosip.vivid.telemetry.audit.AuditSink;
import io.mosip.vivid.telemetry.metrics.LivenessMetrics;
import io.mosip.vivid.testkit.metrics.NonResponse;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Analyse once, replay many: the tool behind the PAD improvement study (docs/testing/proxy-eval/improvement-2026-11.md).
 * <pre>
 * pad-replay dump   --corpus &lt;dir&gt; --out &lt;dump.jsonl.gz&gt; --models &lt;dir&gt; [--fps 15] [--log capture-log.csv]
 *                   [--view NAME=model.onnx[@cropScale][+flip][+fit|+square]]... [--features &lt;frames.csv&gt;]
 * pad-replay replay --dump &lt;dump.jsonl.gz&gt;... --configs &lt;configs.jsonl&gt; --out-dir &lt;dir&gt; [--workflow RESIDENT]
 * </pre>
 * {@code dump} feeds every frame of every item once through a fresh production {@link OnnxFrameAnalyzer} and stores
 * each {@link FrameAnalysis} (quality, observation, every signal score, embedding). For each {@code --view} it also
 * stores the anti-spoof model's P(real) on the analyzer's own face box, with that model's preset recipe and an
 * optional crop scale, mirrored view and crop mode ({@code +fit}: Silent-Face crop, {@code +square}: square crop). Measurements only, no pixels.
 *
 * <p>{@code replay} runs each configuration through the real {@link LivenessOrchestrator} in passive mode, with a stub
 * engine that returns the stored analyses frame by frame. A configuration may replace S1 with the mean P(real) of a
 * set of views (scored exactly as {@link CnnTextureSignal} does: attack logit, Platt calibration, confidence ramp on
 * inter-ocular distance) and may set signal weights, the passive threshold and the hard-veto threshold. With no
 * override the stored S1 score is used unchanged, so the baseline replay must equal a {@link CorpusScoreMain} run.
 * Each configuration writes {@code <out-dir>/<name>.jsonl} in the {@code scores.jsonl} format.
 * Config line: {@code {"name":"x","s1":["A15","B27"],"platt":[1,0],"weights":{"moire":0},"passiveThreshold":0.8,
 * "padHardThreshold":0.9}}.
 */
public final class PadReplayMain {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TICK_MILLIS = 50;
    private static final double MIN_IOD = 16, FULL_IOD = 40;   // as CnnTextureSignal

    private PadReplayMain() {}

    public static void main(String[] args) { System.exit(run(args)); }

    public static int run(String[] args) {
        if (args.length == 0) { usage(); return 2; }
        try {
            String cmd = args[0];
            String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
            if (cmd.equals("dump")) return dump(rest);
            if (cmd.equals("replay")) return replay(rest);
            usage();
            return 2;
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            System.err.println("pad-replay: " + e.getMessage());
            usage();
            return 2;
        } catch (Exception e) {
            System.err.println("pad-replay: " + e);
            e.printStackTrace();
            return 2;
        }
    }

    private static void usage() {
        System.err.println("usage: pad-replay dump --corpus <dir> --out <dump.jsonl.gz> [--models <dir>] [--fps 15] [--log capture-log.csv] "
                + "[--view NAME=model.onnx[@cropScale][+flip][+fit|+square]]... [--features frames.csv]\n"
                + "       pad-replay replay --dump <dump.jsonl.gz>... --configs <configs.jsonl> --out-dir <dir> [--workflow RESIDENT]");
    }

    // ------------------------------------------------------------------------------------------------ dump

    private record View(String name, CnnTextureSignal.Model model) {}

    static int dump(String[] args) throws Exception {
        Path corpus = null, out = null, models = null, log = null, features = null;
        double fps = 15;
        List<String> viewSpecs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--corpus" -> corpus = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--models" -> models = Path.of(args[++i]);
                case "--log" -> log = Path.of(args[++i]);
                case "--fps" -> fps = Double.parseDouble(args[++i]);
                case "--view" -> viewSpecs.add(args[++i]);
                case "--features" -> features = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("unknown argument '" + args[i] + "'");
            }
        }
        if (corpus == null || out == null || models == null) throw new IllegalArgumentException("--corpus, --out and --models are required");
        List<CorpusItem> items = CorpusItem.discover(corpus, fps);
        CaptureLog captureLog = log == null ? CaptureLog.empty() : CaptureLog.read(log);
        List<OrtModel> loaded = new ArrayList<>();
        List<View> views = new ArrayList<>();
        try (OnnxLivenessEngine engine = (OnnxLivenessEngine) CorpusScoreMain.engine(models, Double.NaN);
             BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(out)), StandardCharsets.UTF_8));
             BufferedWriter fw = features == null ? null : Files.newBufferedWriter(features, StandardCharsets.UTF_8)) {
            for (String spec : viewSpecs) {
                String[] kv = spec.split("=", 2);
                String file = kv[1];
                boolean flip = false, fit = false, square = false;
                while (file.matches(".*\\+(flip|fit|square)$")) {
                    int plus = file.lastIndexOf('+');
                    String opt = file.substring(plus + 1);
                    if (opt.equals("flip")) flip = true; else if (opt.equals("fit")) fit = true; else square = true;
                    file = file.substring(0, plus);
                }
                double scale = Double.NaN;
                int at = file.lastIndexOf('@');
                if (at > 0) { scale = Double.parseDouble(file.substring(at + 1)); file = file.substring(0, at); }
                Path p = Path.of(file).isAbsolute() ? Path.of(file) : models.resolve(file);
                AntiSpoofModelConfig c = AntiSpoofModelConfig.preset(p, 0);
                OrtModel m = OrtModel.load(p, engine.config().threads());
                loaded.add(m);
                boolean fitCrop = fit || (!square && c.fitCrop());
                views.add(new View(kv[0], new CnnTextureSignal.Model(m, c.inputSize(), Double.isNaN(scale) ? c.cropScale() : scale,
                        c.bgr(), c.realClassIndex(), c.inputScale(), flip, fitCrop)));
                System.err.println("view " + kv[0] + " = " + c + (Double.isNaN(scale) ? "" : " crop " + scale) + (flip ? " +flip" : "")
                        + (fitCrop ? " fit-crop" : " square-crop"));
            }
            List<String> ids = new ArrayList<>(engine.info().signalIds());
            if (fw != null) {
                StringBuilder h = new StringBuilder("item,label,subject,frame,t_ms,faces,quality,iod");
                for (String id : ids) h.append(',').append(id).append("_p,").append(id).append("_c");
                for (View v : views) h.append(",preal_").append(v.name());
                fw.write(h.append('\n').toString());
            }
            ObjectNode meta = MAPPER.createObjectNode();
            meta.put("meta", true).put("engine", engine.info().engineId()).put("engineVersion", engine.info().engineVersion())
                    .put("bundle", engine.info().modelBundleVersion()).put("embeddings", engine.info().producesEmbeddings());
            ArrayNode sigIds = meta.putArray("signalIds");
            ids.forEach(sigIds::add);
            ArrayNode ch = meta.putArray("challenges");
            engine.info().supportedChallenges().forEach(c -> ch.add(c.name()));
            ArrayNode vn = meta.putArray("views");
            viewSpecs.forEach(vn::add);
            w.write(MAPPER.writeValueAsString(meta));
            w.write('\n');
            int n = 0;
            for (CorpusItem item : items) {
                CorpusItem.Name name = CorpusItem.parseName(item.id());
                Map<String, String> row = captureLog.row(item.id());
                String label = row != null && !row.getOrDefault("label", "").isBlank() ? row.get("label") : name == null ? null : name.label();
                if (label == null) { System.err.println("skipping " + item.id()); continue; }
                String subject = row != null && !row.getOrDefault("participant_id", "").isBlank() ? row.get("participant_id") : name == null ? null : name.participant();
                ObjectNode it = MAPPER.createObjectNode();
                it.put("id", item.id()).put("label", label.toUpperCase(Locale.ROOT));
                if (subject != null) it.put("subject", subject);
                if (row != null && !row.getOrDefault("attack_class", "").isBlank()) it.put("paiClass", row.get("attack_class"));
                ObjectNode g = it.putObject("groups");
                CaptureLog.groups(row).forEach(g::put);
                if (name != null) { if (!g.has("lighting")) g.put("lighting", name.lighting()); if (!g.has("device")) g.put("device", name.device()); }
                ArrayNode frames = it.putArray("frames");
                OnnxFrameAnalyzer a = engine.newAnalyzer();
                for (int i = 0; i < item.frames().size(); i++) {
                    long tMs = item.timestampsMillis()[i];
                    FaceFrame frame = CorpusScoreMain.readFrame(item.frames().get(i), i, tMs * 1_000_000L);
                    FrameAnalysis r = a.analyze(frame);
                    ObjectNode fr = encode(r);
                    fr.put("tMs", tMs);
                    double iod = Double.NaN;
                    Map<String, Double> pv = new LinkedHashMap<>();
                    if (r.face().isPresent() && !views.isEmpty()) {
                        FaceObservation o = r.face().get();
                        int W = frame.width(), H = frame.height();
                        double[] lm = o.landmarks();
                        for (int k = 0; k < lm.length; k++) lm[k] *= (k % 2 == 0) ? W : H;
                        FaceDetection det = new FaceDetection(o.boxX() * W, o.boxY() * H, o.boxW() * W, o.boxH() * H, 1, lm);
                        iod = det.interOcular();
                        byte[] px = new byte[W * H * 3];
                        frame.pixels().get(px);
                        RgbImage rgb = new RgbImage(W, H, px);
                        FaceContext fctx = new FaceContext(rgb, det, frame.timestampNanos(), i, null);
                        ObjectNode vo = fr.putObject("views");
                        for (View v : views) {
                            double p = v.model().probabilityReal(v.model().crop(fctx));
                            vo.put(v.name(), p);
                            pv.put(v.name(), p);
                        }
                        fr.put("iod", iod);
                    }
                    frames.add(fr);
                    if (fw != null) {
                        StringBuilder b = new StringBuilder();
                        b.append(item.id()).append(',').append(label).append(',').append(subject == null ? "" : subject).append(',').append(i + 1)
                                .append(',').append(tMs).append(',').append(r.quality().faceCount()).append(',').append(f(r.quality().overall()))
                                .append(',').append(f(iod));
                        Map<String, SignalScore> sc = new LinkedHashMap<>();
                        for (SignalScore s : r.signals()) sc.put(s.signalId(), s);
                        for (String id : ids) {
                            SignalScore s = sc.get(id);
                            if (s == null) b.append(",,");
                            else b.append(',').append(f(s.attackProbability())).append(',').append(f(s.confidence()));
                        }
                        for (View v : views) b.append(',').append(pv.containsKey(v.name()) ? f(pv.get(v.name())) : "");
                        fw.write(b.append('\n').toString());
                    }
                }
                w.write(MAPPER.writeValueAsString(it));
                w.write('\n');
                System.out.printf(Locale.ROOT, "%s: %d frames (%d/%d)%n", item.id(), item.frames().size(), ++n, items.size());
                System.out.flush();
            }
        } finally {
            for (OrtModel m : loaded) m.close();
        }
        return 0;
    }

    private static String f(double v) { return Double.isFinite(v) ? String.format(Locale.ROOT, "%.5f", v) : ""; }

    static ObjectNode encode(FrameAnalysis r) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("seq", r.frameSequence()).put("ts", r.frameTimestampNanos());
        FaceQuality q = r.quality();
        n.putArray("q").add(q.faceCount()).add(q.sharpness()).add(q.illumination()).add(q.poseQuality()).add(q.occlusion()).add(q.sizeQuality());
        r.face().ifPresent(o -> {
            ObjectNode on = n.putObject("obs");
            on.putArray("box").add(o.boxX()).add(o.boxY()).add(o.boxW()).add(o.boxH());
            on.putArray("pose").add(o.yaw()).add(o.pitch()).add(o.roll());
            on.putArray("eye").add(o.leftEyeAspect()).add(o.rightEyeAspect());
            on.put("mouth", o.mouthAspect()).put("smile", o.smileScore()).put("brow", o.browRaiseScore());
            on.putArray("gaze").add(o.gazeX()).add(o.gazeY());
            ArrayNode lm = on.putArray("lm");
            for (double v : o.landmarks()) lm.add(v);
            if (!o.blendshapes().isEmpty()) {
                ObjectNode bs = on.putObject("bs");
                o.blendshapes().forEach(bs::put);
            }
        });
        ArrayNode sig = n.putArray("sig");
        for (SignalScore s : r.signals()) {
            ArrayNode a = sig.addArray();
            a.add(s.signalId()).add(s.attackProbability()).add(s.confidence()).add(s.hardVeto());
            if (s.attackClass() == null) a.addNull(); else a.add(s.attackClass());
        }
        r.embedding().ifPresent(e -> {
            ArrayNode em = n.putArray("emb");
            for (float v : e) em.add(v);
        });
        n.put("ns", r.processingNanos());
        return n;
    }

    static FrameAnalysis decode(JsonNode n, List<SignalScore> signals) {
        JsonNode q = n.get("q");
        FaceQuality quality = new FaceQuality(q.get(0).asInt(), q.get(1).asDouble(), q.get(2).asDouble(), q.get(3).asDouble(), q.get(4).asDouble(), q.get(5).asDouble());
        FaceObservation obs = null;
        JsonNode on = n.get("obs");
        if (on != null) {
            JsonNode lmN = on.get("lm");
            double[] lm = new double[lmN.size()];
            for (int i = 0; i < lm.length; i++) lm[i] = lmN.get(i).asDouble();
            FaceObservation.Builder b = FaceObservation.builder()
                    .box(on.get("box").get(0).asDouble(), on.get("box").get(1).asDouble(), on.get("box").get(2).asDouble(), on.get("box").get(3).asDouble())
                    .pose(on.get("pose").get(0).asDouble(), on.get("pose").get(1).asDouble(), on.get("pose").get(2).asDouble())
                    .eyeAspect(on.get("eye").get(0).asDouble(), on.get("eye").get(1).asDouble())
                    .mouthAspect(on.get("mouth").asDouble()).smileScore(on.get("smile").asDouble()).browRaiseScore(on.get("brow").asDouble())
                    .gaze(on.get("gaze").get(0).asDouble(), on.get("gaze").get(1).asDouble()).landmarks(lm);
            if (on.has("bs")) {
                Map<String, Double> bs = new LinkedHashMap<>();
                on.get("bs").fields().forEachRemaining(e -> bs.put(e.getKey(), e.getValue().asDouble()));
                b.blendshapes(bs);
            }
            obs = b.build();
        }
        float[] emb = null;
        if (n.has("emb")) {
            JsonNode e = n.get("emb");
            emb = new float[e.size()];
            for (int i = 0; i < emb.length; i++) emb[i] = (float) e.get(i).asDouble();
        }
        return new FrameAnalysis(n.get("seq").asLong(), n.get("ts").asLong(), quality, obs, signals, emb, n.get("ns").asLong());
    }

    static List<SignalScore> storedSignals(JsonNode n) {
        List<SignalScore> out = new ArrayList<>();
        for (JsonNode a : n.get("sig")) {
            out.add(new SignalScore(a.get(0).asText(), a.get(1).asDouble(), a.get(2).asDouble(), a.get(3).asBoolean(), a.get(4).isNull() ? null : a.get(4).asText()));
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------- replay

    /** One replay configuration. */
    record Config(String name, List<String> s1Views, PlattCalibration platt, Map<String, Double> weights, double passiveThreshold, double padHardThreshold) {
        static Config parse(JsonNode n) {
            List<String> views = new ArrayList<>();
            if (n.has("s1")) n.get("s1").forEach(v -> views.add(v.asText()));
            PlattCalibration platt = PlattCalibration.identity();
            if (n.has("platt")) platt = new PlattCalibration(n.get("platt").get(0).asDouble(), n.get("platt").get(1).asDouble(),
                    n.get("platt").size() > 2 ? n.get("platt").get(2).asDouble() : 0.5);
            Map<String, Double> w = new LinkedHashMap<>();
            if (n.has("weights")) n.get("weights").fields().forEachRemaining(e -> w.put(e.getKey(), e.getValue().asDouble()));
            return new Config(n.get("name").asText(), views, platt, w, n.path("passiveThreshold").asDouble(Double.NaN), n.path("padHardThreshold").asDouble(Double.NaN));
        }
    }

    static int replay(String[] args) throws Exception {
        List<Path> dumps = new ArrayList<>();
        Path configs = null, outDir = null;
        Workflow workflow = Workflow.RESIDENT;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dump" -> dumps.add(Path.of(args[++i]));
                case "--configs" -> configs = Path.of(args[++i]);
                case "--out-dir" -> outDir = Path.of(args[++i]);
                case "--workflow" -> workflow = Workflow.valueOf(args[++i].toUpperCase(Locale.ROOT));
                default -> throw new IllegalArgumentException("unknown argument '" + args[i] + "'");
            }
        }
        if (dumps.isEmpty() || configs == null || outDir == null) throw new IllegalArgumentException("--dump, --configs and --out-dir are required");
        List<Config> cfgs = new ArrayList<>();
        for (String line : Files.readAllLines(configs)) if (!line.isBlank() && !line.startsWith("#")) cfgs.add(Config.parse(MAPPER.readTree(line)));
        Files.createDirectories(outDir);
        EngineInfo info = null;
        List<JsonNode> items = new ArrayList<>();
        for (Path d : dumps) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(d)), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    JsonNode n = MAPPER.readTree(line);
                    if (n.path("meta").asBoolean(false)) {
                        if (info == null) {
                            java.util.Set<ChallengeType> ch = java.util.EnumSet.noneOf(ChallengeType.class);
                            n.get("challenges").forEach(c -> ch.add(ChallengeType.valueOf(c.asText())));
                            java.util.Set<String> ids = new java.util.LinkedHashSet<>();
                            n.get("signalIds").forEach(s -> ids.add(s.asText()));
                            info = new EngineInfo(n.get("engine").asText(), n.get("engineVersion").asText(), "none", n.get("bundle").asText(), ch, ids,
                                    n.get("embeddings").asBoolean());
                        }
                    } else items.add(n);
                }
            }
        }
        if (info == null) throw new IllegalArgumentException("dump has no meta line");
        LivenessPolicy base = CorpusScoreMain.policyFor(workflow, CorpusScoreMain.Mode.PASSIVE, Double.NaN);
        for (Config c : cfgs) {
            LivenessPolicy policy = policyFor(base, c);
            try (BufferedWriter w = Files.newBufferedWriter(outDir.resolve(c.name() + ".jsonl"), StandardCharsets.UTF_8)) {
                w.write("# pad-replay config=" + c.name() + " s1=" + (c.s1Views().isEmpty() ? "stored" : String.join("+", c.s1Views()))
                        + " platt=" + c.platt() + " weights=" + policy.signalWeights() + " passiveThreshold=" + policy.passiveThreshold()
                        + " padHardThreshold=" + policy.padHardThreshold() + "\n");
                for (JsonNode item : items) {
                    w.write(MAPPER.writeValueAsString(replayItem(info, policy, c, item)));
                    w.write('\n');
                }
            }
            System.out.println("config " + c.name() + " -> " + outDir.resolve(c.name() + ".jsonl"));
        }
        return 0;
    }

    static LivenessPolicy policyFor(LivenessPolicy d, Config c) {
        Map<String, Double> weights = new LinkedHashMap<>(d.signalWeights());
        weights.putAll(c.weights());
        return LivenessPolicy.builder(d.workflow()).policyVersion(d.policyVersion() + "+" + c.name()).enabled(true)
                .passiveThreshold(Double.isNaN(c.passiveThreshold()) ? d.passiveThreshold() : c.passiveThreshold())
                .padHardThreshold(Double.isNaN(c.padHardThreshold()) ? d.padHardThreshold() : c.padHardThreshold())
                .passiveWindowMillis(d.passiveWindowMillis()).passiveMinFrames(d.passiveMinFrames())
                .minChallenges(d.minChallenges()).maxChallenges(d.maxChallenges()).challengePool(d.challengePool())
                .accessibilityPool(d.accessibilityPool()).requireDepthRevealingChallenge(d.requireDepthRevealingChallenge())
                .challengeTimeoutMillis(d.challengeTimeoutMillis()).minActionLatencyMillis(d.minActionLatencyMillis())
                .maxActionLatencyMillis(d.maxActionLatencyMillis()).maxRetries(d.maxRetries()).onMaxRetries(d.onMaxRetries())
                .sessionTimeoutMillis(d.sessionTimeoutMillis()).minFaceQuality(d.minFaceQuality())
                .identityDriftThreshold(d.identityDriftThreshold()).signalWeights(weights)
                .requireIdentityContinuity(d.requireIdentityContinuity()).activeMode(d.activeMode()).build();
    }

    /** S1 score from stored view probabilities, exactly as {@link CnnTextureSignal#score} computes it. */
    static SignalScore s1From(JsonNode frame, Config c) {
        JsonNode v = frame.get("views");
        double sum = 0;
        for (String name : c.s1Views()) {
            if (v == null || !v.has(name)) throw new IllegalArgumentException("view " + name + " not in dump");
            sum += v.get(name).asDouble();
        }
        double pReal = sum / c.s1Views().size();
        double eps = 1e-6;
        double p = c.platt().apply(Math.log((1 - pReal + eps) / (pReal + eps)));
        double conf = ImageOps.clamp01((frame.get("iod").asDouble() - MIN_IOD) / (FULL_IOD - MIN_IOD));
        PlattCalibration cal = c.platt();
        boolean veto = p >= cal.threshold() && cal.threshold() > 0.5 && conf >= 0.99 && p >= 0.995;
        return new SignalScore(Signals.CNN_TEXTURE, p, conf, veto, p > 0.5 ? Signals.ATTACK_UNKNOWN : null);
    }

    static ObjectNode replayItem(EngineInfo info, LivenessPolicy policy, Config c, JsonNode item) {
        JsonNode frames = item.get("frames");
        List<FrameAnalysis> analyses = new ArrayList<>(frames.size());
        long[] tMs = new long[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            JsonNode fr = frames.get(i);
            List<SignalScore> sig = storedSignals(fr);
            if (!c.s1Views().isEmpty() && fr.has("views")) {
                for (int k = 0; k < sig.size(); k++) if (sig.get(k).signalId().equals(Signals.CNN_TEXTURE)) sig.set(k, s1From(fr, c));
            }
            analyses.add(decode(fr, sig));
            tMs[i] = fr.get("tMs").asLong();
        }
        LivenessEngine engine = new LivenessEngine() {
            @Override public EngineInfo info() { return info; }
            @Override public FrameAnalyzer newAnalyzer() {
                return new FrameAnalyzer() {
                    @Override public FrameAnalysis analyze(FaceFrame frame) { return analyses.get((int) frame.sequence()); }
                    @Override public void reset() {}
                };
            }
        };
        AtomicLong clockNanos = new AtomicLong();
        LivenessSessionFactory factory = new LivenessSessionFactory(engine, w -> policy, AuditSink.discarding(), new LivenessMetrics(),
                TimeSource.of(clockNanos::get), new SecureRandom(), ActionThresholds.defaults());
        SessionContext ctx = factory.newContext(policy.workflow(), Locale.ROOT, false, false);
        int fed = 0;
        LivenessResult res;
        try (LivenessOrchestrator session = factory.open(policy, ctx)) {
            session.start();
            SessionState st = session.state();
            for (int i = 0; i < analyses.size() && !done(st); i++) {
                long t = tMs[i] * 1_000_000L;
                clockNanos.accumulateAndGet(t, Math::max);
                st = session.feed(FaceFrame.builder().sequence(i).timestampNanos(t).width(1).height(1).format(PixelFormat.RGB888)
                        .pixels(ByteBuffer.wrap(new byte[3]).array()).build());
                fed++;
                if (!done(st)) st = session.tick();
            }
            long limit = clockNanos.get() + (policy.sessionTimeoutMillis() + 5_000) * 1_000_000L;
            while (!done(st) && clockNanos.get() < limit) {
                clockNanos.addAndGet(TICK_MILLIS * 1_000_000L);
                st = session.tick();
            }
            if (!done(st)) session.cancel();
            res = session.result();
        }
        String decision = res.decision().name();
        String failure = res.failure().map(Enum::name).orElse(null);
        ObjectNode n = MAPPER.createObjectNode();
        n.put("id", item.get("id").asText());
        if (item.has("subject")) n.put("subject", item.get("subject").asText());
        n.put("label", item.get("label").asText());
        if (item.has("paiClass")) n.put("paiClass", item.get("paiClass").asText());
        n.put("score", res.fusedLivenessScore());
        n.put("decision", decision);
        if (failure == null) n.putNull("failure"); else n.put("failure", failure);
        n.put("response", !NonResponse.isNonResponse(decision, failure) && !NonResponse.isExcluded(decision, failure));
        res.attackClass().ifPresent(a -> n.put("detectedAttackClass", a));
        res.vetoSignal().ifPresent(v -> n.put("vetoSignal", v));
        if (!res.signalScores().isEmpty()) {
            ObjectNode sig = n.putObject("signals");
            new TreeMap<>(res.signalScores()).forEach(sig::put);
        }
        n.put("frames", frames.size()).put("framesFed", fed).put("framesAnalyzed", res.framesAnalyzed());
        n.put("mode", "passive").put("policy", res.policyVersion()).put("engine", res.engineId());
        n.set("groups", item.get("groups"));
        return n;
    }

    private static boolean done(SessionState st) {
        return switch (st.phase()) { case PASSED, FAILED, LOCKED, CANCELLED -> true; default -> false; };
    }
}
