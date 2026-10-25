package io.mosip.vivid.core.challenge;

import io.mosip.vivid.api.Challenge;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Signals;

import java.util.Objects;

/**
 * Signal S6, challenge-response timing: the latency between issuing a prompt and the onset of the user's action
 * must fall inside the human window {@code [minActionLatencyMillis, maxActionLatencyMillis]}.
 *
 * <ul>
 *   <li>Onset before the prompt, or faster than half the minimum: hard veto, attack class
 *       {@link Signals#ATTACK_VIDEO} (a pre-recorded or injected stream that "answers" before being asked).</li>
 *   <li>Faster than the minimum but not absurdly so: high attack probability, no veto; fusion decides.</li>
 *   <li>Inside the window: low attack probability, high confidence.</li>
 *   <li>Slower than the maximum: moderately suspicious (lag of a deepfake pipeline, or simply a slow user);
 *       low confidence so it rarely decides alone.</li>
 * </ul>
 *
 * <p>Stateless.</p>
 */
public final class ChallengeTiming {

    /** Attack probability assigned to a reaction inside the human window. */
    public static final double IN_WINDOW_ATTACK_PROBABILITY = 0.05;
    /** Attack probability for a reaction faster than the minimum but slower than half of it. */
    public static final double TOO_FAST_ATTACK_PROBABILITY = 0.8;
    /** Attack probability for a reaction slower than the maximum. */
    public static final double TOO_SLOW_ATTACK_PROBABILITY = 0.6;

    private ChallengeTiming() {}

    /** Evaluates the latency of a detected action onset. */
    public static SignalScore evaluate(Challenge challenge, long onsetNanos, LivenessPolicy policy) {
        Objects.requireNonNull(challenge, "challenge");
        Objects.requireNonNull(policy, "policy");
        long latencyMs = latencyMillis(challenge, onsetNanos);
        return evaluate(latencyMs, policy.minActionLatencyMillis(), policy.maxActionLatencyMillis());
    }

    /** Evaluates a latency in milliseconds against a window. Negative latency means onset before the prompt. */
    public static SignalScore evaluate(long latencyMs, long minMs, long maxMs) {
        if (latencyMs < 0 || latencyMs < minMs / 2) {
            return new SignalScore(Signals.CHALLENGE_TIMING, 1.0, 1.0, true, Signals.ATTACK_VIDEO);
        }
        if (latencyMs < minMs) {
            return new SignalScore(Signals.CHALLENGE_TIMING, TOO_FAST_ATTACK_PROBABILITY, 0.8, false, Signals.ATTACK_VIDEO);
        }
        if (latencyMs > maxMs) {
            return new SignalScore(Signals.CHALLENGE_TIMING, TOO_SLOW_ATTACK_PROBABILITY, 0.5, false, Signals.ATTACK_DEEPFAKE);
        }
        return new SignalScore(Signals.CHALLENGE_TIMING, IN_WINDOW_ATTACK_PROBABILITY, 0.9, false, null);
    }

    /** Milliseconds from prompt to onset; negative when the onset precedes the prompt. */
    public static long latencyMillis(Challenge challenge, long onsetNanos) {
        return Math.floorDiv(onsetNanos - challenge.issuedAtNanos(), 1_000_000L);
    }
}
