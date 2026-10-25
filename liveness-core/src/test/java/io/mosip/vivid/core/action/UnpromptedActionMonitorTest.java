package io.mosip.vivid.core.action;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.FaceObservation;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Optional;

import static io.mosip.vivid.api.ChallengeType.*;
import static org.assertj.core.api.Assertions.assertThat;

class UnpromptedActionMonitorTest {
    private static final long MS = 1_000_000L;
    private final ActionThresholds t = ActionThresholds.defaults();

    private static FaceObservation face(double yaw, double smile) {
        return FaceObservation.builder().box(0.35, 0.25, 0.3, 0.4).pose(yaw, 0, 0).eyeAspect(0.3, 0.3).smileScore(smile).build();
    }

    @Test
    void exemptionsCoverSpontaneousRequestedAndRelatedActions() {
        assertThat(UnpromptedActionMonitor.watched(TURN_RIGHT, BLINK)).isFalse();
        assertThat(UnpromptedActionMonitor.watched(TURN_RIGHT, HOLD_STILL)).isFalse();
        assertThat(UnpromptedActionMonitor.watched(TURN_RIGHT, TURN_RIGHT)).isFalse();
        assertThat(UnpromptedActionMonitor.watched(OPEN_MOUTH, SMILE)).isFalse();
        assertThat(UnpromptedActionMonitor.watched(NOD, LOOK_DOWN)).isFalse();
        assertThat(UnpromptedActionMonitor.watched(TURN_RIGHT, TURN_LEFT)).isTrue();
        assertThat(UnpromptedActionMonitor.watched(TURN_RIGHT, SMILE)).isTrue();
        UnpromptedActionMonitor m = new UnpromptedActionMonitor(TURN_RIGHT, EnumSet.of(BLINK, SMILE, TURN_LEFT, TURN_RIGHT), t, 0);
        assertThat(m.watching()).containsExactlyInAnyOrder(SMILE, TURN_LEFT);
    }

    /** A recording turns left while the subject was asked to turn right. */
    @Test
    void reportsAWrongHeadTurn() {
        UnpromptedActionMonitor m = new UnpromptedActionMonitor(TURN_RIGHT, EnumSet.of(TURN_LEFT, TURN_RIGHT, SMILE), t, 0);
        Optional<ChallengeType> seen = Optional.empty();
        double[] yaws = {0, 0, 10, 25, 28, 20, 5, 0, 0};
        for (int i = 0; i < yaws.length && seen.isEmpty(); i++) seen = m.update(face(yaws[i], 0.1), (i + 1) * 100 * MS);
        assertThat(seen).contains(TURN_LEFT);
    }

    @Test
    void stayingNeutralOrBlinkingReportsNothing() {
        UnpromptedActionMonitor m = new UnpromptedActionMonitor(SMILE, EnumSet.of(BLINK, SMILE, TURN_LEFT, TURN_RIGHT), t, 0);
        for (int i = 1; i <= 50; i++) assertThat(m.update(face(1, 0.1), i * 66 * MS)).isEmpty();
    }
}
