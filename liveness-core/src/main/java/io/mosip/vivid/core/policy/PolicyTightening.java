package io.mosip.vivid.core.policy;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.LivenessPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Applies locally configured policy values on top of an authoritative baseline (the signed policy document, or the
 * compiled defaults when none is installed) so that local configuration can only make liveness stricter
 * ({@code docs/design/07-configuration.md}, "tightening direction"). A value that would weaken the baseline is
 * replaced by the baseline's value and reported in {@link Result#rejected()}, so the integration can log and audit it.
 *
 * <p>Two settings are deliberately not clamped: {@code enabled}, because the problem statement requires that
 * liveness verification can be switched on and off by configuration, and {@code onMaxRetries} and signal weights,
 * which are operational choices without a single "stricter" direction.
 */
public final class PolicyTightening {
    private PolicyTightening() {}

    /** The effective policy and the keys whose local value was ignored because it would have loosened the baseline. */
    public static final class Result {
        private final LivenessPolicy policy;
        private final List<String> rejected;

        Result(LivenessPolicy policy, List<String> rejected) {
            this.policy = policy;
            this.rejected = Collections.unmodifiableList(rejected);
        }

        public LivenessPolicy policy() { return policy; }

        /** Human-readable entries such as {@code "passiveThreshold 0.70 < 0.80"}; empty when nothing was loosened. */
        public List<String> rejected() { return rejected; }
    }

    /** Merges {@code local} into {@code baseline}, keeping the stricter value of every ordered setting. */
    public static Result tighten(LivenessPolicy baseline, LivenessPolicy local) {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(local, "local");
        if (baseline.workflow() != local.workflow()) {
            throw new IllegalArgumentException("workflow mismatch: " + baseline.workflow() + " vs " + local.workflow());
        }
        List<String> rejected = new ArrayList<>();
        LivenessPolicy.Builder b = LivenessPolicy.builder(local.workflow())
                .policyVersion(local.policyVersion())
                .enabled(local.enabled())
                .onMaxRetries(local.onMaxRetries())
                .signalWeights(local.signalWeights())
                .passiveThreshold(atLeast("passiveThreshold", local.passiveThreshold(), baseline.passiveThreshold(), rejected))
                .padHardThreshold(atMost("padHardThreshold", local.padHardThreshold(), baseline.padHardThreshold(), rejected))
                .passiveWindowMillis(atLeast("passiveWindowMillis", local.passiveWindowMillis(), baseline.passiveWindowMillis(), rejected))
                .passiveMinFrames(atLeast("passiveMinFrames", local.passiveMinFrames(), baseline.passiveMinFrames(), rejected))
                .activeMode(mode(local.activeMode(), baseline.activeMode(), rejected))
                .requireDepthRevealingChallenge(flag("requireDepthRevealingChallenge", local.requireDepthRevealingChallenge(),
                        baseline.requireDepthRevealingChallenge(), rejected))
                .challengeTimeoutMillis(atMost("challengeTimeoutMillis", local.challengeTimeoutMillis(), baseline.challengeTimeoutMillis(), rejected))
                .minActionLatencyMillis(atLeast("minActionLatencyMillis", local.minActionLatencyMillis(), baseline.minActionLatencyMillis(), rejected))
                .maxActionLatencyMillis(atMost("maxActionLatencyMillis", local.maxActionLatencyMillis(), baseline.maxActionLatencyMillis(), rejected))
                .maxRetries(atMost("maxRetries", local.maxRetries(), baseline.maxRetries(), rejected))
                .sessionTimeoutMillis(atMost("sessionTimeoutMillis", local.sessionTimeoutMillis(), baseline.sessionTimeoutMillis(), rejected))
                .acquisitionTimeoutMillis(atMost("acquisitionTimeoutMillis", local.acquisitionTimeoutMillis(), baseline.acquisitionTimeoutMillis(), rejected))
                .minFaceQuality(atLeast("minFaceQuality", local.minFaceQuality(), baseline.minFaceQuality(), rejected))
                .identityDriftThreshold(atMost("identityDriftThreshold", local.identityDriftThreshold(), baseline.identityDriftThreshold(), rejected))
                .requireIdentityContinuity(flag("requireIdentityContinuity", local.requireIdentityContinuity(),
                        baseline.requireIdentityContinuity(), rejected));
        int min = atLeast("minChallenges", local.minChallenges(), baseline.minChallenges(), rejected);
        int max = Math.max(min, atLeast("maxChallenges", local.maxChallenges(), baseline.maxChallenges(), rejected));
        b.minChallenges(min).maxChallenges(max);
        b.challengePool(subset("challengePool", local.challengePool(), baseline.challengePool(), rejected));
        b.accessibilityPool(subset("accessibilityPool", local.accessibilityPool(), baseline.accessibilityPool(), rejected));
        return new Result(b.build(), rejected);
    }

    private static double atLeast(String key, double local, double base, List<String> rejected) {
        if (local < base) { rejected.add(key + " " + local + " < " + base); return base; }
        return local;
    }

    private static double atMost(String key, double local, double base, List<String> rejected) {
        if (local > base) { rejected.add(key + " " + local + " > " + base); return base; }
        return local;
    }

    private static long atLeast(String key, long local, long base, List<String> rejected) {
        if (local < base) { rejected.add(key + " " + local + " < " + base); return base; }
        return local;
    }

    private static long atMost(String key, long local, long base, List<String> rejected) {
        if (local > base) { rejected.add(key + " " + local + " > " + base); return base; }
        return local;
    }

    private static int atLeast(String key, int local, int base, List<String> rejected) {
        if (local < base) { rejected.add(key + " " + local + " < " + base); return base; }
        return local;
    }

    private static int atMost(String key, int local, int base, List<String> rejected) {
        if (local > base) { rejected.add(key + " " + local + " > " + base); return base; }
        return local;
    }

    private static boolean flag(String key, boolean local, boolean base, List<String> rejected) {
        if (base && !local) { rejected.add(key + " false < true"); return true; }
        return local;
    }

    /** NEVER < ON_LOW_CONFIDENCE < ALWAYS: the local mode may only move towards ALWAYS. */
    private static LivenessPolicy.ActiveMode mode(LivenessPolicy.ActiveMode local, LivenessPolicy.ActiveMode base, List<String> rejected) {
        if (local.ordinal() < base.ordinal()) { rejected.add("activeMode " + local + " < " + base); return base; }
        return local;
    }

    /** A local pool may narrow the baseline's pool but not add challenge types to it. */
    private static Set<ChallengeType> subset(String key, Set<ChallengeType> local, Set<ChallengeType> base, List<String> rejected) {
        EnumSet<ChallengeType> out = local.isEmpty() ? EnumSet.noneOf(ChallengeType.class) : EnumSet.copyOf(local);
        out.retainAll(base);
        if (out.size() < local.size()) {
            EnumSet<ChallengeType> added = EnumSet.copyOf(local);
            added.removeAll(base);
            rejected.add(key + " adds " + added);
        }
        if (out.isEmpty()) return base;
        return out;
    }
}
