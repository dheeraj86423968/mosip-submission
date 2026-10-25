package io.mosip.vivid.api;

import java.util.Objects;
import java.util.Optional;

/** Observable state of a session, rendered by UI. Immutable snapshot. */
public final class SessionState {
    public enum Phase {
        IDLE, DEVICE_CONNECTING, FACE_ACQUIRING, PASSIVE_EVALUATING, ACTIVE_CHALLENGE, PASSED, FAILED, LOCKED, CANCELLED
    }
    /** Guidance for the user, chosen by the orchestrator; UI maps to localised strings. */
    public enum Hint {
        NONE, MOVE_CLOSER, MOVE_BACK, CENTER_FACE, LOOK_AT_CAMERA, IMPROVE_LIGHTING, HOLD_STILL, ONLY_ONE_FACE,
        CHECKING_LIVENESS, PLEASE_CONTINUE, ACTION_DETECTED, CAPTURED_OK, TRY_AGAIN, RETRY_AVAILABLE, MAX_RETRIES_REACHED,
        DEVICE_RECONNECTING, DEVICE_DISCONNECTED
    }

    private final Phase phase;
    private final Hint hint;
    private final Challenge challenge;        // nullable
    private final FailureReason failure;      // nullable
    private final int attempt;
    private final int maxAttempts;
    private final double progress;            // [0,1] overall session progress for a progress indicator
    private final double passiveScore;        // last fused liveness score in [0,1]; 1 = certainly live
    private final long remainingChallengeMillis;

    public SessionState(Phase phase, Hint hint, Challenge challenge, FailureReason failure, int attempt, int maxAttempts,
                        double progress, double passiveScore, long remainingChallengeMillis) {
        this.phase = Objects.requireNonNull(phase); this.hint = Objects.requireNonNullElse(hint, Hint.NONE);
        this.challenge = challenge; this.failure = failure; this.attempt = attempt; this.maxAttempts = maxAttempts;
        this.progress = progress; this.passiveScore = passiveScore; this.remainingChallengeMillis = remainingChallengeMillis;
    }
    public Phase phase() { return phase; }
    public Hint hint() { return hint; }
    public Optional<Challenge> challenge() { return Optional.ofNullable(challenge); }
    public Optional<FailureReason> failure() { return Optional.ofNullable(failure); }
    public int attempt() { return attempt; }
    public int maxAttempts() { return maxAttempts; }
    public double progress() { return progress; }
    public double passiveScore() { return passiveScore; }
    public long remainingChallengeMillis() { return remainingChallengeMillis; }
    public boolean isTerminal() { return phase == Phase.PASSED || phase == Phase.LOCKED || phase == Phase.CANCELLED; }
    public boolean canRetry() { return phase == Phase.FAILED && attempt < maxAttempts; }
    @Override public String toString() { return phase + "/" + hint + (challenge != null ? "/" + challenge : "") + (failure != null ? "/" + failure : "") + " attempt=" + attempt + "/" + maxAttempts; }
}
