package io.mosip.vivid.desktop.core;

import io.mosip.vivid.api.*;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.PixelFormat;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import io.mosip.vivid.core.action.ActionThresholds;
import io.mosip.vivid.core.session.LivenessSessionFactory;
import io.mosip.vivid.core.session.TimeSource;
import io.mosip.vivid.telemetry.audit.InMemoryAuditSink;
import io.mosip.vivid.telemetry.metrics.LivenessMetrics;
import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;

class CoreLivenessWiringTest {

    /** Engine whose analyzer always reports one good, clearly live face. */
    private static LivenessEngine liveEngine() {
        return new LivenessEngine() {
            @Override public EngineInfo info() {
                return new EngineInfo("test", "1", "none", "none", EnumSet.allOf(ChallengeType.class), Set.of(Signals.CNN_TEXTURE), false);
            }
            @Override public FrameAnalyzer newAnalyzer() {
                return new FrameAnalyzer() {
                    @Override public FrameAnalysis analyze(FaceFrame f) {
                        FaceObservation face = FaceObservation.builder().box(0.35, 0.25, 0.3, 0.4).eyeAspect(0.3, 0.3).build();
                        return new FrameAnalysis(f.sequence(), f.timestampNanos(), new FaceQuality(1, 1, 1, 1, 1, 1), face,
                                List.of(SignalScore.of(Signals.CNN_TEXTURE, 0.01, 1.0)), null, 1_000);
                    }
                    @Override public void reset() {}
                };
            }
        };
    }

    @Test
    void providerOpensRealOrchestratorThatPassesALiveSubject() {
        LivenessPolicy policy = LivenessPolicy.builder(Workflow.RESIDENT).activeMode(LivenessPolicy.ActiveMode.NEVER)
                .minChallenges(0).passiveWindowMillis(500).passiveMinFrames(5).requireIdentityContinuity(false).build();
        SessionContext ctx = new SessionContext(Workflow.RESIDENT, "s-1", new byte[16], Locale.ENGLISH, false, false);
        AtomicLong now = new AtomicLong();
        LivenessSessionFactory factory = new LivenessSessionFactory(liveEngine(), w -> policy, new InMemoryAuditSink(100),
                new LivenessMetrics(), TimeSource.of(now::get), new SecureRandom(), ActionThresholds.defaults());
        LivenessSession session = CoreLivenessWiring.sessionProvider(factory).open(ctx, policy);

        SessionState state = session.state();
        for (int i = 0; i < 60 && !state.isTerminal(); i++) {
            FaceFrame frame = FaceFrame.builder().sequence(i).timestampNanos(i * 66_000_000L).width(4).height(4)
                    .format(PixelFormat.RGB888).pixels(new byte[48]).build();
            now.set(frame.timestampNanos());
            state = session.feed(frame);
        }
        assertThat(state.phase()).isEqualTo(SessionState.Phase.PASSED);
        assertThat(session.result().accepted()).isTrue();
        assertThat(session.result().evidenceHash()).hasSize(64);
    }

    @Test
    void identityDistanceIsCosineDistance() {
        assertThat(CoreLivenessWiring.identityDistance().apply(new float[]{1, 0}, new float[]{1, 0})).isEqualTo(0.0);
        assertThat(CoreLivenessWiring.identityDistance().apply(new float[]{1, 0}, new float[]{0, 1})).isEqualTo(1.0);
    }
}
