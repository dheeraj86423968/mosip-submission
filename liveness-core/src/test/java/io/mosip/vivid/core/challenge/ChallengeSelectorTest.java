package io.mosip.vivid.core.challenge;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.SessionContext;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.core.testing.Scenarios;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChallengeSelectorTest {

    private static final Set<ChallengeType> ALL = EnumSet.allOf(ChallengeType.class);
    /** Chi-square critical values at p = 0.001. */
    private static final double CHI2_DF3 = 16.266;
    private static final double CHI2_DF11 = 31.264;
    private static final int DRAWS = 20_000;

    private static final SessionContext RESIDENT = Scenarios.context(Workflow.RESIDENT, false);
    private static final SessionContext ASSISTED = Scenarios.context(Workflow.RESIDENT, true);

    private static LivenessPolicy fourPool(boolean requireDepth) {
        return LivenessPolicy.builder(Workflow.RESIDENT).minChallenges(2).maxChallenges(2).requireDepthRevealingChallenge(requireDepth)
                .challengePool(EnumSet.of(ChallengeType.OPEN_MOUTH, ChallengeType.SMILE, ChallengeType.TURN_LEFT, ChallengeType.NOD)).build();
    }

    @Test
    void firstElementAndPermutationsAreUniform_seeded() {
        assertUniform(new Random(42));
    }

    @Test
    void firstElementAndPermutationsAreUniform_secureRandom() {
        assertUniform(new SecureRandom());
    }

    private static void assertUniform(Random rng) {
        ChallengeSelector selector = new ChallengeSelector(rng);
        LivenessPolicy policy = fourPool(false);
        Map<ChallengeType, Integer> first = new HashMap<>();
        Map<List<ChallengeType>, Integer> perms = new HashMap<>();
        for (int i = 0; i < DRAWS; i++) {
            List<ChallengeType> seq = selector.select(policy, RESIDENT, ALL);
            assertThat(seq).hasSize(2).doesNotHaveDuplicates();
            first.merge(seq.get(0), 1, Integer::sum);
            perms.merge(seq, 1, Integer::sum);
        }
        assertThat(first).hasSize(4);
        assertThat(perms).hasSize(12);
        double chiFirst = chiSquare(first.values(), DRAWS / 4.0);
        double chiPerm = chiSquare(perms.values(), DRAWS / 12.0);
        assertThat(chiFirst).as("first element chi-square").isLessThan(CHI2_DF3);
        assertThat(chiPerm).as("permutation chi-square").isLessThan(CHI2_DF11);
    }

    private static double chiSquare(Iterable<Integer> observed, double expected) {
        double chi = 0;
        for (int o : observed) {
            chi += (o - expected) * (o - expected) / expected;
        }
        return chi;
    }

    @Test
    void lengthIsUniformBetweenMinAndMax() {
        ChallengeSelector selector = new ChallengeSelector(new Random(7));
        LivenessPolicy policy = LivenessPolicy.builder(Workflow.RESIDENT).minChallenges(1).maxChallenges(3).requireDepthRevealingChallenge(false).build();
        Map<Integer, Integer> lengths = new HashMap<>();
        for (int i = 0; i < 6000; i++) {
            lengths.merge(selector.select(policy, RESIDENT, ALL).size(), 1, Integer::sum);
        }
        assertThat(lengths.keySet()).containsExactlyInAnyOrder(1, 2, 3);
        assertThat(chiSquare(lengths.values(), 2000)).isLessThan(13.816);
    }

    @Test
    void depthRevealingChallengeIsGuaranteed() {
        ChallengeSelector selector = new ChallengeSelector(new Random(1));
        LivenessPolicy policy = fourPool(true);
        for (int i = 0; i < 2000; i++) {
            List<ChallengeType> seq = selector.select(policy, RESIDENT, ALL);
            assertThat(seq).anyMatch(ChallengeType::revealsDepth);
        }
        // The constraint must not destroy variety: both orders and both non-depth partners still occur.
        Map<List<ChallengeType>, Integer> perms = new HashMap<>();
        for (int i = 0; i < 2000; i++) {
            perms.merge(selector.select(policy, RESIDENT, ALL), 1, Integer::sum);
        }
        assertThat(perms).hasSize(10); // 12 permutations minus the two with no depth-revealing element
    }

    @Test
    void depthConstraintIsSkippedWhenPoolHasNone() {
        ChallengeSelector selector = new ChallengeSelector(new Random(3));
        LivenessPolicy policy = LivenessPolicy.builder(Workflow.RESIDENT).minChallenges(1).maxChallenges(1)
                .challengePool(EnumSet.of(ChallengeType.BLINK, ChallengeType.SMILE)).requireDepthRevealingChallenge(true).build();
        assertThat(selector.select(policy, RESIDENT, ALL)).hasSize(1);
    }

    @Test
    void forcedReplacementWhenRedrawsAreExhausted() {
        // A generator that always picks index 0 produces [BLINK, SMILE] forever; the fallback must inject a depth challenge.
        Random stuck = new Random() {
            @Override public long nextLong() { return 0; }
            @Override public int nextInt(int bound) { return 0; }
        };
        List<ChallengeType> seq = new ChallengeSelector(stuck).select(fourPool(true), RESIDENT, ALL);
        assertThat(seq).hasSize(2).anyMatch(ChallengeType::revealsDepth).doesNotHaveDuplicates();
    }

    @Test
    void intersectsWithEngineSupport() {
        ChallengeSelector selector = new ChallengeSelector(new Random(5));
        LivenessPolicy policy = LivenessPolicy.builder(Workflow.RESIDENT).minChallenges(3).maxChallenges(3).requireDepthRevealingChallenge(false).build();
        Set<ChallengeType> supported = EnumSet.of(ChallengeType.BLINK, ChallengeType.SMILE);
        for (int i = 0; i < 50; i++) {
            List<ChallengeType> seq = selector.select(policy, RESIDENT, supported);
            assertThat(seq).hasSize(2).containsExactlyInAnyOrder(ChallengeType.BLINK, ChallengeType.SMILE);
        }
        assertThat(selector.select(policy, RESIDENT, EnumSet.of(ChallengeType.HOLD_STILL))).isEmpty();
        assertThat(ChallengeSelector.candidates(policy, false, ALL)).containsExactly(ChallengeType.BLINK, ChallengeType.SMILE,
                ChallengeType.OPEN_MOUTH, ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT);
    }

    @Test
    void assistedCaptureUsesAccessibilityPoolWithoutMotorChallenges() {
        ChallengeSelector selector = new ChallengeSelector(new Random(9));
        LivenessPolicy policy = LivenessPolicy.builder(Workflow.RESIDENT).minChallenges(2).maxChallenges(3).requireDepthRevealingChallenge(true)
                .accessibilityPool(EnumSet.of(ChallengeType.BLINK, ChallengeType.SMILE, ChallengeType.HOLD_STILL, ChallengeType.NOD)).build();
        for (int i = 0; i < 200; i++) {
            List<ChallengeType> seq = selector.select(policy, ASSISTED, ALL);
            assertThat(seq).isNotEmpty().allMatch(t -> !t.requiresMotorAbility())
                    .allMatch(t -> EnumSet.of(ChallengeType.BLINK, ChallengeType.SMILE, ChallengeType.HOLD_STILL).contains(t));
        }
        assertThat(ChallengeSelector.candidates(policy, true, ALL)).doesNotContain(ChallengeType.NOD);
    }

    @Test
    void zeroLengthAndEmptyPools() {
        ChallengeSelector selector = new ChallengeSelector(new Random(2));
        LivenessPolicy none = LivenessPolicy.builder(Workflow.RESIDENT).minChallenges(0).maxChallenges(0).build();
        assertThat(selector.select(none, RESIDENT, ALL)).isEmpty();
        LivenessPolicy emptyAccessibility = LivenessPolicy.builder(Workflow.RESIDENT).accessibilityPool(EnumSet.noneOf(ChallengeType.class)).build();
        assertThat(selector.select(emptyAccessibility, ASSISTED, ALL)).isEmpty();
        assertThatThrownBy(() -> new ChallengeSelector(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> selector.select(none, RESIDENT, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void resultIsImmutable() {
        List<ChallengeType> seq = new ChallengeSelector(new Random(4)).select(fourPool(false), RESIDENT, ALL);
        assertThatThrownBy(() -> seq.add(ChallengeType.BLINK)).isInstanceOf(UnsupportedOperationException.class);
    }

    /** A blink proves nothing against a replay that blinks, so it never fills a required slot when the pool has alternatives. */
    @Test
    void spontaneousActionsDoNotCountTowardTheMinimum() {
        LivenessPolicy policy = LivenessPolicy.builder(Workflow.RESIDENT).minChallenges(2).maxChallenges(3)
                .requireDepthRevealingChallenge(false)
                .challengePool(EnumSet.of(ChallengeType.BLINK, ChallengeType.HOLD_STILL, ChallengeType.SMILE, ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT)).build();
        ChallengeSelector selector = new ChallengeSelector(new Random(7));
        boolean sawBlink = false;
        for (int i = 0; i < 5_000; i++) {
            List<ChallengeType> seq = selector.select(policy, RESIDENT, ALL);
            long informative = seq.stream().filter(t -> t != ChallengeType.BLINK && t != ChallengeType.HOLD_STILL).count();
            assertThat(informative).as("sequence %s", seq).isGreaterThanOrEqualTo(2);
            sawBlink |= seq.contains(ChallengeType.BLINK);
        }
        assertThat(sawBlink).as("blink may still be drawn as an extra challenge").isTrue();
    }
}
