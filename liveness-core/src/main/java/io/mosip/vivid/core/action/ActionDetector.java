package io.mosip.vivid.core.action;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.FaceObservation;

import java.util.OptionalLong;

/**
 * Verifies one active challenge purely from the sequence of {@link FaceObservation}s and the session clock.
 * One instance is created per issued challenge by {@link ActionDetectors#create}; it is driven by the
 * orchestrator on its feeding thread and is not thread-safe.
 *
 * <p>Every detector enforces the anti-replay rule from PLAN.md section 4.4: the action must <em>start</em> after
 * the prompt was issued, from a neutral state observed after the prompt. The onset time feeds signal S6
 * (challenge timing); the completion time feeds the challenge log.</p>
 */
public interface ActionDetector {

    /** The challenge this detector verifies. */
    ChallengeType type();

    /** Session-clock time at which the challenge was issued. */
    long issuedAtNanos();

    /** Feeds one observation taken at {@code nowNanos} (session clock) and returns the new status. No-op once terminal. */
    ActionStatus update(FaceObservation face, long nowNanos);

    /** Current status. */
    ActionStatus status();

    /** Session-clock time of the detected onset, once observed. */
    OptionalLong onsetNanos();

    /** Session-clock time of completion, once completed. */
    OptionalLong completedNanos();

    /** Progress in [0,1] for the UI. */
    double progress();

    /**
     * Whether the prompt-to-onset latency is meaningful for this action. {@code false} for challenges whose
     * "onset" is simply the first frame after the prompt (holding still), where a latency check would be noise.
     */
    default boolean timingApplies() {
        return true;
    }
}
