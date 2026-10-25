package io.mosip.vivid.av.testing;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.EngineInfo;
import io.mosip.vivid.api.FaceObservation;
import io.mosip.vivid.api.FaceQuality;
import io.mosip.vivid.api.FrameAnalysis;
import io.mosip.vivid.api.FrameAnalyzer;
import io.mosip.vivid.api.LivenessEngine;
import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.PixelFormat;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongToDoubleFunction;

/**
 * Inner engine stand-in: one well-lit, centred, still face whose mouth opening is whatever {@link #mouth} says at
 * the current session time; passive signals report a live face. Replaces the camera and the ONNX engine so the AV
 * logic, the real orchestrator and the real HOLD_STILL detector can be driven end to end.
 */
public final class ScriptedEngine implements LivenessEngine {
    private final FakeClock clock;
    public volatile LongToDoubleFunction mouth = t -> LipTracks.REST;
    public volatile double passiveAttackProbability = 0.05;

    public ScriptedEngine(FakeClock clock) { this.clock = clock; }

    @Override
    public EngineInfo info() {
        return new EngineInfo("scripted", "1", "none", "none", EnumSet.allOf(ChallengeType.class),
                Set.of(Signals.CNN_TEXTURE), true);
    }

    @Override
    public FrameAnalyzer newAnalyzer() {
        return new FrameAnalyzer() {
            @Override public FrameAnalysis analyze(FaceFrame frame) {
                double m = mouth.applyAsDouble(clock.nanoTime());
                FaceObservation face = FaceObservation.builder().box(0.3, 0.25, 0.4, 0.5).pose(0, 0, 0).eyeAspect(0.3, 0.3).mouthAspect(m).build();
                float[] emb = new float[128];
                emb[0] = 1f;
                return new FrameAnalysis(frame.sequence(), frame.timestampNanos(), new FaceQuality(1, 0.9, 0.9, 0.9, 1.0, 0.9), face,
                        List.of(SignalScore.of(Signals.CNN_TEXTURE, passiveAttackProbability, 0.9)), emb, 1_000_000);
            }
            @Override public void reset() {}
        };
    }

    public static FaceFrame frame(long seq, long nanos) {
        return FaceFrame.builder().sequence(seq).timestampNanos(nanos).width(4).height(4).format(PixelFormat.GRAY8).pixels(new byte[16]).build();
    }
}
