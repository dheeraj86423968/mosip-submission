package io.mosip.vivid.e2e;

import io.mosip.vivid.api.Challenge;
import io.mosip.vivid.api.EngineInfo;
import io.mosip.vivid.api.FaceObservation;
import io.mosip.vivid.api.FrameAnalysis;
import io.mosip.vivid.api.FrameAnalyzer;
import io.mosip.vivid.api.LivenessEngine;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.engine.detect.FaceDetection;
import io.mosip.vivid.engine.image.FrameDecoder;
import io.mosip.vivid.engine.image.ImageOps;
import io.mosip.vivid.engine.image.RgbImage;
import io.mosip.vivid.engine.pose.HeadPose;
import io.mosip.vivid.engine.pose.ObservationExtractor;
import io.mosip.vivid.engine.pose.PitchBaseline;

import java.util.function.Supplier;

/**
 * The production engine with one substitution: the two measures that NOD, LOOK_UP and LOOK_DOWN depend on, pitch and
 * vertical gaze, are computed by the engine's own code ({@link HeadPose} with a per-session {@link PitchBaseline},
 * and {@link ObservationExtractor}'s eye-band gaze) from the five points the mock actually drew instead of the five
 * points YuNet regressed.
 *
 * <p>Why: YuNet is trained on photographs. On the mock's drawn face its nose landmark follows the eyes and the face
 * box, not the drawn nose (moving only the drawn nose by 80 px moves YuNet's nose point by about 30 px and drags the
 * eye points with it), so pitch on the cartoon reads about a quarter of the rendered angle (see
 * {@link PoseProbeTest} and docs/testing/e2e-findings.md). YuNet's eye points also sit about 7 px below the drawn
 * irises, so the eye-band gaze, which is measured around them, reads -0.78 at rest and jumps to 0 when the face
 * turns or smiles. Yaw, eye and mouth aspect, quality, passive signals and the whole
 * orchestrator are unchanged; the baseline follows the analyzer's life cycle exactly as in
 * {@code OnnxFrameAnalyzer} (learn until the first challenge, then fixed; forget on reset).
 */
final class DrawnLandmarkPitchEngine implements LivenessEngine {
    private final LivenessEngine delegate;
    private final Supplier<double[]> drawnLandmarks;

    /** @param drawnLandmarks the ground-truth landmarks (pixels, YuNet order) of the frame about to be analysed */
    DrawnLandmarkPitchEngine(LivenessEngine delegate, Supplier<double[]> drawnLandmarks) {
        this.delegate = delegate;
        this.drawnLandmarks = drawnLandmarks;
    }

    @Override public EngineInfo info() { return delegate.info(); }

    @Override
    public FrameAnalyzer newAnalyzer() {
        FrameAnalyzer inner = delegate.newAnalyzer();
        PitchBaseline baseline = new PitchBaseline();
        return new FrameAnalyzer() {
            @Override
            public FrameAnalysis analyze(FaceFrame frame) {
                FrameAnalysis a = inner.analyze(frame);
                if (a.face().isEmpty()) return a;
                double[] lm = drawnLandmarks.get();
                baseline.observe(lm);
                FaceObservation o = a.face().get();
                RgbImage rgb = FrameDecoder.toRgb(frame);
                FaceDetection drawn = new FaceDetection(o.boxX() * rgb.width(), o.boxY() * rgb.height(), o.boxW() * rgb.width(),
                        o.boxH() * rgb.height(), 1.0, lm);
                FaceObservation fromDrawn = ObservationExtractor.extract(ImageOps.toGray(rgb), drawn, rgb.width(), rgb.height(), baseline.estimate(lm));
                FaceObservation replaced = FaceObservation.builder()
                        .box(o.boxX(), o.boxY(), o.boxW(), o.boxH())
                        .pose(o.yaw(), fromDrawn.pitch(), o.roll())
                        .eyeAspect(o.leftEyeAspect(), o.rightEyeAspect())
                        .mouthAspect(o.mouthAspect()).smileScore(o.smileScore()).browRaiseScore(o.browRaiseScore())
                        .gaze(o.gazeX(), fromDrawn.gazeY()).landmarks(o.landmarks()).blendshapes(o.blendshapes())
                        .build();
                return new FrameAnalysis(a.frameSequence(), a.frameTimestampNanos(), a.quality(), replaced, a.signals(),
                        a.embedding().orElse(null), a.processingNanos());
            }

            @Override
            public void onChallenge(Challenge challenge) {
                if (challenge != null) baseline.freeze();
                inner.onChallenge(challenge);
            }

            @Override
            public void reset() {
                baseline.reset();
                inner.reset();
            }

            @Override public void close() { inner.close(); }
        };
    }

    @Override public void close() { /* the delegate is owned by the caller */ }
}
