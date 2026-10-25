package io.mosip.vivid.e2e;

import io.mosip.vivid.api.*;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.PixelFormat;
import io.mosip.vivid.device.mock.render.ExpressionEvent;
import io.mosip.vivid.device.mock.render.ExpressionEvent.Action;
import io.mosip.vivid.device.mock.render.ExpressionTimeline;
import io.mosip.vivid.device.mock.render.SyntheticFaceRenderer;
import io.mosip.vivid.engine.onnx.OnnxEngineFactory;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Diagnostic: what the ONNX engine reports for each rendered pose and expression. Logs only. */
class PoseProbeTest {
    @Test
    void turnTrace() {
        assumeTrue(SyntheticSubjectEngine.available());
        var log = LoggerFactory.getLogger(getClass());
        SyntheticFaceRenderer r = new SyntheticFaceRenderer(640, 480);
        for (double deg : new double[]{30, -30}) {
            ExpressionTimeline tl = ExpressionTimeline.of(List.of(ExpressionEvent.between(Action.YAW, 500, 2000, deg)));
            StringBuilder sb = new StringBuilder(String.format("turn %+.0f:", deg));
            try (LivenessEngine engine = SyntheticSubjectEngine.create(); FrameAnalyzer an = engine.newAnalyzer()) {
                for (long t = 0; t <= 2400; t += 66) {
                    BufferedImage img = r.render(tl.stateAt(t));
                    int w = img.getWidth(), h = img.getHeight(); byte[] rgb = new byte[w * h * 3]; int[] px = img.getRGB(0, 0, w, h, null, 0, w);
                    for (int i = 0, j = 0; i < px.length; i++) { rgb[j++] = (byte) (px[i] >> 16); rgb[j++] = (byte) (px[i] >> 8); rgb[j++] = (byte) px[i]; }
                    FrameAnalysis a = an.analyze(FaceFrame.builder().sequence(t).timestampNanos(t * 1_000_000L).width(w).height(h).format(PixelFormat.RGB888).pixels(rgb).build());
                    sb.append(a.face().map(f -> String.format(" %.0f[s%.2f i%.2f p%.2f o%.2f z%.2f]", f.yaw(), a.quality().sharpness(), a.quality().illumination(), a.quality().poseQuality(), a.quality().occlusion(), a.quality().sizeQuality())).orElse(" X"));
                }
            }
            log.warn(sb.toString());
        }
    }

    /** Vertical gaze and pitch (YuNet and drawn-landmark) over a turn, a smile and a look down. */
    @Test
    void gazeTrace() {
        assumeTrue(SyntheticSubjectEngine.available());
        var log = LoggerFactory.getLogger(getClass());
        SyntheticFaceRenderer r = new SyntheticFaceRenderer(640, 480);
        Object[][] cases = {{"turn-30", ExpressionEvent.between(Action.YAW, 300, 1800, -30)}, {"smile", ExpressionEvent.between(Action.SMILE, 300, 1500, 1.0)},
                {"down-22", ExpressionEvent.between(Action.PITCH, 300, 1800, -22)}, {"nod", ExpressionEvent.between(Action.NOD, 300, 1800, 22)}, {"mouth", ExpressionEvent.between(Action.OPEN_MOUTH, 300, 1500, 1.0)}};
        for (Object[] c : cases) {
            ExpressionTimeline tl = ExpressionTimeline.of(List.of((ExpressionEvent) c[1]));
            StringBuilder sb = new StringBuilder(c[0] + " [drawnPitch/yunetPitch/gazeY]:");
            try (LivenessEngine engine = SyntheticSubjectEngine.create(); FrameAnalyzer an = engine.newAnalyzer()) {
                for (long t = 0; t <= 2200; t += 133) {
                    var st = tl.stateAt(t);
                    BufferedImage img = r.render(st);
                    int w = img.getWidth(), h = img.getHeight(); byte[] rgb = new byte[w * h * 3]; int[] px = img.getRGB(0, 0, w, h, null, 0, w);
                    for (int i = 0, j = 0; i < px.length; i++) { rgb[j++] = (byte) (px[i] >> 16); rgb[j++] = (byte) (px[i] >> 8); rgb[j++] = (byte) px[i]; }
                    FrameAnalysis a = an.analyze(FaceFrame.builder().sequence(t).timestampNanos(t * 1_000_000L).width(w).height(h).format(PixelFormat.RGB888).pixels(rgb).build());
                    double[] n0 = r.landmarks(io.mosip.vivid.device.mock.render.FaceState.neutral());
                    double dp = io.mosip.vivid.engine.pose.HeadPose.estimateRelative(r.landmarks(st),
                            io.mosip.vivid.engine.pose.HeadPose.noseFraction(n0), io.mosip.vivid.engine.pose.HeadPose.noseDrop(n0)).pitch();
                    sb.append(a.face().map(f -> String.format(" %.0f/%.0f/%.2f", dp, f.pitch(), f.gazeY())).orElse(" X"));
                }
            }
            log.warn(sb.toString());
        }
    }

    /** Rendered pitch against the engine's reported pitch, for a static head at each angle. */
    @Test
    void pitchSweep() {
        assumeTrue(SyntheticSubjectEngine.available());
        var log = LoggerFactory.getLogger(getClass());
        SyntheticFaceRenderer r = new SyntheticFaceRenderer(640, 480);
        StringBuilder sb = new StringBuilder("pitch sweep (rendered->reported):");
        try (LivenessEngine engine = SyntheticSubjectEngine.create(); FrameAnalyzer an = engine.newAnalyzer()) {
            for (double deg = -30; deg <= 30; deg += 5) {
                BufferedImage img = r.render(ExpressionTimeline.of(List.of(ExpressionEvent.between(Action.PITCH, 0, 5000, deg))).stateAt(2500));
                int w = img.getWidth(), h = img.getHeight(); byte[] rgb = new byte[w * h * 3]; int[] px = img.getRGB(0, 0, w, h, null, 0, w);
                for (int i = 0, j = 0; i < px.length; i++) { rgb[j++] = (byte) (px[i] >> 16); rgb[j++] = (byte) (px[i] >> 8); rgb[j++] = (byte) px[i]; }
                FrameAnalysis a = an.analyze(FaceFrame.builder().sequence(1).timestampNanos(1).width(w).height(h).format(PixelFormat.RGB888).pixels(rgb).build());
                final double d = deg;
                sb.append(a.face().map(f -> String.format(" %+.0f->%+.1f", d, f.pitch())).orElse(String.format(" %+.0f->X", d)));
                a.face().ifPresent(f -> { double[] l = f.landmarks(); StringBuilder q = new StringBuilder(String.format("lm %+.0f:", d));
                    for (int i = 0; i < l.length; i += 2) q.append(String.format(" (%.0f,%.0f)", l[i] * w, l[i + 1] * h));
                    q.append(String.format(" box y=%.0f h=%.0f", f.boxY() * h, f.boxH() * h)); log.warn(q.toString()); });
                String dump = System.getProperty("e2e.dumpDir");
                if (dump != null) try { javax.imageio.ImageIO.write(img, "png", new java.io.File(dump, String.format("pitch%+03.0f.png", deg))); } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            }
        }
        log.warn(sb.toString());
    }

    @Test
    void probe() {
        assumeTrue(SyntheticSubjectEngine.available());
        var log = LoggerFactory.getLogger(getClass());
        SyntheticFaceRenderer r = new SyntheticFaceRenderer(640, 480);
        Object[][] cases = {
                {"neutral", null}, {"yaw+30", ExpressionEvent.between(Action.YAW, 0, 5000, 30)}, {"yaw-30", ExpressionEvent.between(Action.YAW, 0, 5000, -30)},
                {"pitch+22", ExpressionEvent.between(Action.PITCH, 0, 5000, 22)}, {"pitch-22", ExpressionEvent.between(Action.PITCH, 0, 5000, -22)},
                {"smile", ExpressionEvent.between(Action.SMILE, 0, 5000, 1.0)}, {"mouth", ExpressionEvent.between(Action.OPEN_MOUTH, 0, 5000, 1.0)},
                {"blink-mid", ExpressionEvent.blinkAt(2350)},
                {"yaw+10", ExpressionEvent.between(Action.YAW, 0, 5000, 10)}, {"yaw+15", ExpressionEvent.between(Action.YAW, 0, 5000, 15)},
                {"yaw+20", ExpressionEvent.between(Action.YAW, 0, 5000, 20)}, {"yaw+25", ExpressionEvent.between(Action.YAW, 0, 5000, 25)},
                {"yaw-15", ExpressionEvent.between(Action.YAW, 0, 5000, -15)}, {"yaw-20", ExpressionEvent.between(Action.YAW, 0, 5000, -20)},
                {"pitch+10", ExpressionEvent.between(Action.PITCH, 0, 5000, 10)}, {"pitch+15", ExpressionEvent.between(Action.PITCH, 0, 5000, 15)},
                {"pitch-10", ExpressionEvent.between(Action.PITCH, 0, 5000, -10)}, {"pitch-15", ExpressionEvent.between(Action.PITCH, 0, 5000, -15)},
                {"blink2400", ExpressionEvent.blinkAt(2400)}, {"blink2450", ExpressionEvent.blinkAt(2450)}, {"blink2300", ExpressionEvent.blinkAt(2300)}};
        try (LivenessEngine engine = SyntheticSubjectEngine.create(); FrameAnalyzer an = engine.newAnalyzer()) {
            for (Object[] c : cases) {
                ExpressionTimeline tl = c[1] == null ? ExpressionTimeline.empty() : ExpressionTimeline.of(List.of((ExpressionEvent) c[1]));
                BufferedImage img = r.render(tl.stateAt(2500));
                int w = img.getWidth(), h = img.getHeight(); byte[] rgb = new byte[w * h * 3]; int[] px = img.getRGB(0, 0, w, h, null, 0, w);
                for (int i = 0, j = 0; i < px.length; i++) { rgb[j++] = (byte) (px[i] >> 16); rgb[j++] = (byte) (px[i] >> 8); rgb[j++] = (byte) px[i]; }
                FrameAnalysis a = an.analyze(FaceFrame.builder().sequence(1).timestampNanos(1).width(w).height(h).format(PixelFormat.RGB888).pixels(rgb).build());
                a.face().ifPresentOrElse(f -> log.warn(String.format("%-9s yaw=%6.1f pitch=%6.1f roll=%5.1f eye=%.3f mouth=%.3f smile=%.2f", c[0], f.yaw(), f.pitch(), f.roll(), f.eyeAspect(), f.mouthAspect(), f.smileScore())),
                        () -> log.warn("{} no face (count={})", c[0], a.quality().faceCount()));
            }
        }
    }
}
