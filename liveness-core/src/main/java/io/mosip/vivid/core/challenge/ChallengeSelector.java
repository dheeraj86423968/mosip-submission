package io.mosip.vivid.core.challenge;

import io.mosip.vivid.core.action.UnpromptedActionMonitor;
import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.SessionContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Random;

/**
 * Draws the active challenge sequence for one attempt (PLAN.md section 4.4).
 *
 * <ul>
 *   <li>Candidates are the intersection of the policy pool and the engine's supported challenges.</li>
 *   <li>When {@link SessionContext#assistedCapture()} is set, the accessibility pool is used instead and every
 *       {@link ChallengeType#requiresMotorAbility()} challenge is excluded.</li>
 *   <li>Length is uniform in {@code [minChallenges, maxChallenges]}, capped by the number of candidates.</li>
 *   <li>Elements are drawn without replacement using the injected {@link Random}
 *       (production: {@code SecureRandom}).</li>
 *   <li>When {@link LivenessPolicy#requireDepthRevealingChallenge()} is set and a depth-revealing candidate exists,
 *       sequences without one are rejected and redrawn so the constraint holds without biasing the order of the
 *       accepted sequences; after {@value #MAX_REDRAWS} rejections one random position is replaced instead.</li>
 * </ul>
 *
 * <p>Stateless and thread-safe apart from the {@link Random} it is given.</p>
 */
public final class ChallengeSelector {

    /** Upper bound on rejection sampling before falling back to a forced replacement. */
    static final int MAX_REDRAWS = 64;

    private final Random random;

    /** Selector drawing from the given generator. */
    public ChallengeSelector(Random random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Draws a sequence for the given policy, context and engine capability. Returns an empty list when no
     * candidate is available or the drawn length is zero.
     */
    public List<ChallengeType> select(LivenessPolicy policy, SessionContext context, Set<ChallengeType> engineSupported) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(engineSupported, "engineSupported");
        List<ChallengeType> candidates = candidates(policy, context.assistedCapture(), engineSupported);
        if (candidates.isEmpty()) {
            return List.of();
        }
        int min = Math.max(0, policy.minChallenges());
        int max = Math.max(min, policy.maxChallenges());
        int length = Math.min(candidates.size(), min + random.nextInt(max - min + 1));
        if (length == 0) {
            return List.of();
        }
        boolean needDepth = policy.requireDepthRevealingChallenge() && !context.assistedCapture()
                && candidates.stream().anyMatch(ChallengeType::revealsDepth);
        // Spontaneous actions (blink, hold still) may be drawn but do not count toward the minimum: a replay
        // performs them on its own. Require min informative challenges wherever the pool allows it.
        long informativeAvailable = candidates.stream().filter(t -> !UnpromptedActionMonitor.spontaneous(t)).count();
        int needInformative = (int) Math.min(Math.min(min, length), informativeAvailable);
        List<ChallengeType> draw = draw(candidates, length);
        int redraws = 0;
        while ((needDepth && draw.stream().noneMatch(ChallengeType::revealsDepth)) || informative(draw) < needInformative) {
            if (++redraws > MAX_REDRAWS) {
                forceInformative(draw, candidates, needInformative);
                if (needDepth && draw.stream().noneMatch(ChallengeType::revealsDepth)) {
                    forceDepth(draw, candidates);
                }
                break;
            }
            draw = draw(candidates, length);
        }
        return Collections.unmodifiableList(draw);
    }

    /** The candidate pool in enum order: policy pool (or accessibility pool) intersected with engine support. */
    public static List<ChallengeType> candidates(LivenessPolicy policy, boolean assistedCapture, Set<ChallengeType> engineSupported) {
        Set<ChallengeType> pool = assistedCapture ? policy.accessibilityPool() : policy.challengePool();
        EnumSet<ChallengeType> out = EnumSet.noneOf(ChallengeType.class);
        for (ChallengeType t : pool) {
            if (engineSupported.contains(t) && !(assistedCapture && t.requiresMotorAbility())) {
                out.add(t);
            }
        }
        return new ArrayList<>(out);
    }

    private static long informative(List<ChallengeType> draw) {
        return draw.stream().filter(t -> !UnpromptedActionMonitor.spontaneous(t)).count();
    }

    /** Replaces spontaneous entries with unused informative ones until {@code need} informative challenges are present. */
    private void forceInformative(List<ChallengeType> draw, List<ChallengeType> candidates, int need) {
        List<ChallengeType> spare = new ArrayList<>();
        for (ChallengeType t : candidates) {
            if (!UnpromptedActionMonitor.spontaneous(t) && !draw.contains(t)) spare.add(t);
        }
        for (int i = 0; i < draw.size() && informative(draw) < need && !spare.isEmpty(); i++) {
            if (UnpromptedActionMonitor.spontaneous(draw.get(i))) {
                draw.set(i, spare.remove(random.nextInt(spare.size())));
            }
        }
    }

    /** Partial Fisher-Yates: the first {@code length} elements of a uniformly random permutation. */
    private List<ChallengeType> draw(List<ChallengeType> candidates, int length) {
        List<ChallengeType> pool = new ArrayList<>(candidates);
        List<ChallengeType> out = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            int j = i + random.nextInt(pool.size() - i);
            Collections.swap(pool, i, j);
            out.add(pool.get(i));
        }
        return out;
    }

    private void forceDepth(List<ChallengeType> draw, List<ChallengeType> candidates) {
        List<ChallengeType> depth = new ArrayList<>();
        for (ChallengeType t : candidates) {
            if (t.revealsDepth() && !draw.contains(t)) {
                depth.add(t);
            }
        }
        if (!depth.isEmpty()) {
            draw.set(random.nextInt(draw.size()), depth.get(random.nextInt(depth.size())));
        }
    }
}
