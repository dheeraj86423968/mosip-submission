package io.mosip.vivid.core.action;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.FaceObservation;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Common bookkeeping for detectors: status, onset and completion times, progress, and the terminal-state guard.
 * Subclasses implement {@link #evaluate} and call {@link #markOnset}, {@link #clearOnset}, {@link #setProgress}
 * and {@link #complete}.
 */
abstract class AbstractActionDetector implements ActionDetector {

    private final ChallengeType type;
    private final long issuedAtNanos;
    protected final ActionThresholds thresholds;
    private ActionStatus status = ActionStatus.WAITING;
    private long onsetNanos = -1;
    private long completedNanos = -1;
    private double progress;

    AbstractActionDetector(ChallengeType type, ActionThresholds thresholds, long issuedAtNanos) {
        this.type = Objects.requireNonNull(type, "type");
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
        this.issuedAtNanos = issuedAtNanos;
    }

    @Override
    public final ChallengeType type() {
        return type;
    }

    @Override
    public final long issuedAtNanos() {
        return issuedAtNanos;
    }

    @Override
    public final ActionStatus update(FaceObservation face, long nowNanos) {
        if (status.isTerminal() || face == null) {
            return status;
        }
        status = Objects.requireNonNull(evaluate(face, nowNanos), "evaluate must return a status");
        if (status == ActionStatus.FAILED) {
            progress = 0;
        }
        return status;
    }

    /** Evaluates one observation; return {@link ActionStatus#COMPLETED} only via {@link #complete}. */
    protected abstract ActionStatus evaluate(FaceObservation face, long nowNanos);

    @Override
    public final ActionStatus status() {
        return status;
    }

    @Override
    public final OptionalLong onsetNanos() {
        return onsetNanos < 0 ? OptionalLong.empty() : OptionalLong.of(onsetNanos);
    }

    @Override
    public final OptionalLong completedNanos() {
        return completedNanos < 0 ? OptionalLong.empty() : OptionalLong.of(completedNanos);
    }

    @Override
    public final double progress() {
        return progress;
    }

    /** Records the onset if not already recorded. */
    protected final void markOnset(long nowNanos) {
        if (onsetNanos < 0) {
            onsetNanos = nowNanos;
        }
    }

    /** Forgets a tentative onset (the candidate action turned out not to be the action). */
    protected final void clearOnset() {
        onsetNanos = -1;
    }

    protected final boolean onsetSeen() {
        return onsetNanos >= 0;
    }

    protected final void setProgress(double p) {
        progress = Math.max(0, Math.min(1, p));
    }

    /** Marks completion at {@code nowNanos} and returns {@link ActionStatus#COMPLETED}. */
    protected final ActionStatus complete(long nowNanos) {
        markOnset(nowNanos);
        completedNanos = nowNanos;
        progress = 1;
        return ActionStatus.COMPLETED;
    }

    /** {@link ActionStatus#IN_PROGRESS} once an onset exists, otherwise {@link ActionStatus#WAITING}. */
    protected final ActionStatus pending() {
        return onsetSeen() ? ActionStatus.IN_PROGRESS : ActionStatus.WAITING;
    }

    static long millis(long nanos) {
        return nanos / 1_000_000L;
    }
}
