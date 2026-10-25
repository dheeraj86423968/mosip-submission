package io.mosip.vivid.av;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.core.action.ActionThresholds;

import java.util.ArrayList;
import java.util.List;

/** Wiring helpers that keep the carrier challenge and the spoken-code round consistent. */
public final class AvSetup {
    /** Slack between the forced decision and the earliest possible carrier completion. */
    public static final long CARRIER_SLACK_MILLIS = 500;

    private AvSetup() {}

    /** Thresholds whose HOLD_STILL hold time outlasts the response window, so the verdict always lands first. */
    public static ActionThresholds carrierThresholds(ActionThresholds base, AvConfig cfg) {
        long need = cfg.responseWindowMillis() + CARRIER_SLACK_MILLIS;
        return base.holdStillMillis() >= need ? base : base.toBuilder().holdStillMillis(need).build();
    }

    /** Configuration problems that would let a session pass without a spoken-code verdict, or fail spuriously. */
    public static List<String> check(LivenessPolicy policy, boolean assistedCapture, ActionThresholds t, AvConfig cfg) {
        List<String> problems = new ArrayList<>();
        if (!cfg.carriers().equals(java.util.EnumSet.of(ChallengeType.HOLD_STILL))) {
            problems.add("only HOLD_STILL is a verified carrier; others complete independently of the round: " + cfg.carriers());
        }
        if (t.holdStillMillis() < cfg.responseWindowMillis() + CARRIER_SLACK_MILLIS) {
            problems.add("holdStillMillis " + t.holdStillMillis() + " < responseWindowMillis + slack; use AvSetup.carrierThresholds");
        }
        if (policy.challengeTimeoutMillis() <= t.holdStillMillis()) {
            problems.add("challengeTimeoutMillis " + policy.challengeTimeoutMillis() + " must exceed holdStillMillis " + t.holdStillMillis());
        }
        java.util.Set<ChallengeType> pool = assistedCapture ? policy.accessibilityPool() : policy.challengePool();
        if (!pool.contains(ChallengeType.HOLD_STILL)) {
            problems.add("HOLD_STILL is not in the " + (assistedCapture ? "accessibility" : "challenge") + " pool, so the round never runs");
        }
        if (t.rejectUnpromptedActions() && (policy.challengePool().contains(ChallengeType.OPEN_MOUTH) || policy.challengePool().contains(ChallengeType.SMILE))) {
            problems.add("warning: OPEN_MOUTH/SMILE in the challenge pool are watched as unprompted actions during HOLD_STILL; "
                    + "wide-mouthed speech held over " + t.mouthOpenHoldMillis() + " ms can fail the carrier");
        }
        return problems;
    }
}
