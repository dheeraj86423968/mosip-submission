package io.mosip.vivid.core.action;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.FaceObservation;

/**
 * HOLD_STILL: the face centre must stay within {@code holdStillCentreTolerance} and yaw/pitch within
 * {@code holdStillPoseToleranceDegrees} of a reference for {@code holdStillMillis}. Any larger movement restarts
 * the window from the current observation. Timing (S6) does not apply: the onset is simply the start of the
 * window that eventually succeeds.
 */
final class HoldStillDetector extends AbstractActionDetector {

    private FaceObservation reference;
    private long windowStartNanos = -1;

    HoldStillDetector(ActionThresholds t, long issuedAtNanos) {
        super(ChallengeType.HOLD_STILL, t, issuedAtNanos);
    }

    @Override
    public boolean timingApplies() {
        return false;
    }

    @Override
    protected ActionStatus evaluate(FaceObservation face, long now) {
        if (reference == null || moved(face)) {
            reference = face;
            windowStartNanos = now;
            clearOnset();
            markOnset(now);
            setProgress(0);
            return ActionStatus.IN_PROGRESS;
        }
        long held = millis(now - windowStartNanos);
        if (held >= thresholds.holdStillMillis()) {
            return complete(now);
        }
        setProgress((double) held / thresholds.holdStillMillis());
        return ActionStatus.IN_PROGRESS;
    }

    private boolean moved(FaceObservation face) {
        return Math.abs(face.centerX() - reference.centerX()) > thresholds.holdStillCentreTolerance()
                || Math.abs(face.centerY() - reference.centerY()) > thresholds.holdStillCentreTolerance()
                || Math.abs(face.yaw() - reference.yaw()) > thresholds.holdStillPoseToleranceDegrees()
                || Math.abs(face.pitch() - reference.pitch()) > thresholds.holdStillPoseToleranceDegrees();
    }
}
