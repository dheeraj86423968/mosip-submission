package io.mosip.vivid.core.challenge;

import io.mosip.vivid.api.Challenge;
import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.api.Workflow;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChallengeTimingTest {

    private static final LivenessPolicy POLICY = LivenessPolicy.builder(Workflow.RESIDENT).minActionLatencyMillis(200).maxActionLatencyMillis(4000).build();
    private static final long ISSUED = 5_000_000_000L;
    private static final Challenge CHALLENGE = new Challenge(ChallengeType.BLINK, 0, 2, ISSUED, 8000);

    private static SignalScore at(long latencyMs) {
        return ChallengeTiming.evaluate(CHALLENGE, ISSUED + latencyMs * 1_000_000L, POLICY);
    }

    @Test
    void onsetBeforePromptIsAVideoReplayVeto() {
        SignalScore s = at(-50);
        assertThat(s.signalId()).isEqualTo(Signals.CHALLENGE_TIMING);
        assertThat(s.hardVeto()).isTrue();
        assertThat(s.attackClass()).isEqualTo(Signals.ATTACK_VIDEO);
        assertThat(s.attackProbability()).isEqualTo(1.0);
        assertThat(ChallengeTiming.latencyMillis(CHALLENGE, ISSUED - 50_000_000L)).isEqualTo(-50);
    }

    @Test
    void fasterThanHalfMinimumIsVeto() {
        assertThat(at(0).hardVeto()).isTrue();
        assertThat(at(99).hardVeto()).isTrue();
        assertThat(at(100).hardVeto()).isFalse();
    }

    @Test
    void fasterThanMinimumIsSuspiciousNotVeto() {
        SignalScore s = at(150);
        assertThat(s.hardVeto()).isFalse();
        assertThat(s.attackProbability()).isEqualTo(ChallengeTiming.TOO_FAST_ATTACK_PROBABILITY);
        assertThat(s.attackClass()).isEqualTo(Signals.ATTACK_VIDEO);
    }

    @Test
    void insideWindowIsLowAttackProbability() {
        for (long ms : new long[] {200, 1000, 4000}) {
            SignalScore s = at(ms);
            assertThat(s.hardVeto()).isFalse();
            assertThat(s.attackProbability()).isEqualTo(ChallengeTiming.IN_WINDOW_ATTACK_PROBABILITY);
            assertThat(s.confidence()).isEqualTo(0.9);
            assertThat(s.attackClass()).isNull();
        }
    }

    @Test
    void slowerThanMaximumIsMildlySuspicious() {
        SignalScore s = at(4001);
        assertThat(s.hardVeto()).isFalse();
        assertThat(s.attackProbability()).isEqualTo(ChallengeTiming.TOO_SLOW_ATTACK_PROBABILITY);
        assertThat(s.confidence()).isEqualTo(0.5);
        assertThat(s.attackClass()).isEqualTo(Signals.ATTACK_DEEPFAKE);
    }

    @Test
    void rawWindowOverload() {
        assertThat(ChallengeTiming.evaluate(50, 100, 1000).hardVeto()).isFalse();
        assertThat(ChallengeTiming.evaluate(49, 100, 1000).hardVeto()).isTrue();
        assertThat(ChallengeTiming.evaluate(500, 100, 1000).attackProbability()).isEqualTo(ChallengeTiming.IN_WINDOW_ATTACK_PROBABILITY);
    }
}
