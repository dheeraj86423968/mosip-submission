package io.mosip.vivid.testkit.corpus;

import io.mosip.vivid.api.Challenge;
import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.FaceObservation;
import io.mosip.vivid.api.FrameAnalysis;
import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.engine.onnx.OnnxEngineFactory;
import io.mosip.vivid.engine.onnx.OnnxFrameAnalyzer;
import io.mosip.vivid.engine.onnx.OnnxLivenessEngine;
import io.mosip.vivid.engine.pose.HeadPose;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-frame diagnostic trace of the ONNX engine over a corpus, for the per-signal and head-pose analysis that the
 * session-level {@code scores.jsonl} cannot show.
 * <pre>
 * frame-trace --corpus &lt;dir&gt; --out &lt;trace.csv&gt; [--models &lt;dir&gt;] [--fps 15] [--calib-ms 2500]
 *             [--freeze-challenge NOD|NONE] [--detector-threshold x]
 * </pre>
 * Each item (same layout as {@link CorpusScoreMain}) is fed frame by frame to one fresh {@link OnnxFrameAnalyzer},
 * without the orchestrator. After {@code --calib-ms} of the item the analyzer is told that a challenge of type
 * {@code --freeze-challenge} has started, exactly as the orchestrator does when it issues the first challenge: that
 * freezes the per-session {@code PitchBaseline} (from then on {@code pitch} is relative to the subject's learned
 * neutral) and, for a depth-revealing type such as {@code NOD}, starts S5 parallax tracking. {@code NONE} never
 * freezes. Every frame writes one CSV row: detection count, quality, yaw, the engine's pitch, the absolute
 * population-constant pitch from {@link HeadPose#estimate}, nose drop and nose fraction, vertical gaze, eye and mouth
 * aspect, and each signal's attack probability and confidence. The trace contains measurements only, no pixels.
 */
public final class FrameTraceMain {
    private FrameTraceMain() {}

    public static void main(String[] args) { System.exit(run(args)); }

    public static int run(String[] args) {
        Path corpus = null, out = null, models = null;
        double fps = 15, detector = Double.NaN;
        long calibMs = 2500;
        ChallengeType freeze = ChallengeType.NOD;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--corpus" -> corpus = Path.of(args[++i]);
                    case "--out" -> out = Path.of(args[++i]);
                    case "--models" -> models = Path.of(args[++i]);
                    case "--fps" -> fps = Double.parseDouble(args[++i]);
                    case "--calib-ms" -> calibMs = Long.parseLong(args[++i]);
                    case "--freeze-challenge" -> { String v = args[++i].toUpperCase(Locale.ROOT); freeze = v.equals("NONE") ? null : ChallengeType.valueOf(v); }
                    case "--detector-threshold" -> detector = Double.parseDouble(args[++i]);
                    default -> throw new IllegalArgumentException("unknown argument '" + args[i] + "'");
                }
            }
            if (corpus == null || out == null) throw new IllegalArgumentException("--corpus and --out are required");
            List<CorpusItem> items = CorpusItem.discover(corpus, fps);
            if (items.isEmpty()) throw new IllegalArgumentException("no frame folders under " + corpus);
            Path modelDir = models != null ? models : OnnxEngineFactory.findModelDirectory().orElse(null);
            if (modelDir == null || !Files.isDirectory(modelDir)) { System.err.println("frame-trace: no model directory"); return 3; }
            try (OnnxLivenessEngine engine = (OnnxLivenessEngine) CorpusScoreMain.engine(modelDir, detector);
                 BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                List<String> ids = new ArrayList<>(engine.info().signalIds());
                StringBuilder h = new StringBuilder("item,frame,t_ms,faces,quality,frozen,yaw,pitch,pitch_abs,roll,nose_drop,nose_frac,gaze_y,eye_aspect,mouth_aspect,smile");
                for (String id : ids) h.append(',').append(id).append("_p,").append(id).append("_c");
                w.write(h.append('\n').toString());
                for (CorpusItem item : items) trace(engine, item, ids, calibMs, freeze, w);
            }
            System.out.printf(Locale.ROOT, "items=%d -> %s%n", items.size(), out);
            return 0;
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            System.err.println("frame-trace: " + e.getMessage());
            System.err.println("usage: frame-trace --corpus <dir> --out <trace.csv> [--models <dir>] [--fps 15] [--calib-ms 2500] [--freeze-challenge NOD|NONE] [--detector-threshold x]");
            return 2;
        } catch (Exception e) {
            System.err.println("frame-trace: " + e);
            return 2;
        }
    }

    static void trace(OnnxLivenessEngine engine, CorpusItem item, List<String> ids, long calibMs, ChallengeType freeze, BufferedWriter w) throws Exception {
        OnnxFrameAnalyzer a = engine.newAnalyzer();
        boolean frozen = false;
        for (int i = 0; i < item.frames().size(); i++) {
            long tMs = item.timestampsMillis()[i];
            if (!frozen && freeze != null && tMs >= calibMs) {
                a.onChallenge(new Challenge(freeze, 0, 1, tMs * 1_000_000L, 60_000));
                frozen = true;
            }
            var frame = CorpusScoreMain.readFrame(item.frames().get(i), i, tMs * 1_000_000L);
            FrameAnalysis r = a.analyze(frame);
            Map<String, SignalScore> sc = new LinkedHashMap<>();
            for (SignalScore s : r.signals()) sc.put(s.signalId(), s);
            StringBuilder b = new StringBuilder();
            b.append(item.id()).append(',').append(i + 1).append(',').append(tMs).append(',').append(r.quality().faceCount())
                    .append(',').append(f(r.quality().overall())).append(',').append(frozen ? 1 : 0);
            FaceObservation o = r.face().orElse(null);
            if (o != null) {
                double[] lm = o.landmarks();
                for (int k = 0; k < lm.length; k++) lm[k] *= (k % 2 == 0) ? frame.width() : frame.height();
                b.append(',').append(f(o.yaw())).append(',').append(f(o.pitch())).append(',').append(f(HeadPose.estimate(lm).pitch()))
                        .append(',').append(f(o.roll())).append(',').append(f(HeadPose.noseDrop(lm))).append(',').append(f(HeadPose.noseFraction(lm)))
                        .append(',').append(f(o.gazeY())).append(',').append(f(o.eyeAspect())).append(',').append(f(o.mouthAspect()))
                        .append(',').append(f(o.smileScore()));
            } else {
                b.append(",,,,,,,,,,");
            }
            for (String id : ids) {
                SignalScore s = sc.get(id);
                if (s == null) b.append(",,");
                else b.append(',').append(f(s.attackProbability())).append(',').append(f(s.confidence()));
            }
            w.write(b.append('\n').toString());
        }
    }

    private static String f(double v) { return Double.isFinite(v) ? String.format(Locale.ROOT, "%.4f", v) : ""; }
}
