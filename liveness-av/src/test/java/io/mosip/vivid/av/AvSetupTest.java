package io.mosip.vivid.av;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.LivenessResult;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.core.action.ActionThresholds;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvSetupTest {
    @Test
    void carrierHoldOutlastsTheResponseWindow() {
        AvConfig cfg = AvConfig.defaults();
        ActionThresholds t = AvSetup.carrierThresholds(ActionThresholds.defaults(), cfg);
        assertThat(t.holdStillMillis()).isEqualTo(cfg.responseWindowMillis() + AvSetup.CARRIER_SLACK_MILLIS);
        LivenessPolicy p = LivenessPolicy.builder(Workflow.RESIDENT).accessibilityPool(EnumSet.of(ChallengeType.HOLD_STILL))
                .challengePool(EnumSet.of(ChallengeType.BLINK, ChallengeType.TURN_LEFT)).build();
        assertThat(AvSetup.check(p, true, t, cfg)).isEmpty();
    }

    @Test
    void reportsUnsafeWiring() {
        AvConfig cfg = AvConfig.defaults();
        LivenessPolicy p = LivenessPolicy.defaults(Workflow.RESIDENT);
        List<String> problems = AvSetup.check(p, false, ActionThresholds.defaults(), cfg);
        assertThat(String.join("\n", problems)).contains("holdStillMillis").contains("not in the challenge pool").contains("unprompted");
    }

    @Test
    void headMovementCannotCarryTheCode() {
        assertThatThrownBy(() -> AvConfig.builder().carriers(EnumSet.of(ChallengeType.TURN_LEFT))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requirementNeedsAPassingAvSignal() {
        assertThat(SpokenCodeRequirement.satisfied(result(LivenessResult.Decision.LIVE, Map.of(AvSignals.AV_SYNC, 0.05)))).isTrue();
        assertThat(SpokenCodeRequirement.satisfied(result(LivenessResult.Decision.LIVE, Map.of("cnn_texture", 0.05)))).isFalse();
        assertThat(SpokenCodeRequirement.satisfied(result(LivenessResult.Decision.ATTACK, Map.of(AvSignals.AV_SYNC, 1.0)))).isFalse();
    }

    private static LivenessResult result(LivenessResult.Decision d, Map<String, Double> scores) {
        return new LivenessResult("s", Workflow.RESIDENT, d, null, 0.9, scores, null, null, List.of(), 1, 0, 0, 0, "e", "b", "p", "n", null, "h");
    }
}
