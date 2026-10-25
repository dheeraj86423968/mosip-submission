package io.mosip.vivid.core.action;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.FaceObservation;

/**
 * TURN_LEFT, TURN_RIGHT, LOOK_UP, LOOK_DOWN and NOD: a neutral baseline (|angle| below {@code baselineMaxDegrees})
 * is captured after the prompt; the head must then move past a threshold in the requested direction (onset) and
 * return to within {@code returnDegrees} of the baseline (completion).
 *
 * <p>Conventions follow {@link FaceObservation}: yaw &gt; 0 is the subject turning to their own left; pitch &gt; 0
 * is the nose tilting up; gazeY &gt; 0 is looking up. LOOK_UP and LOOK_DOWN also accept a gaze excursion of
 * {@code gazeThreshold} from the gaze at baseline, for subjects who move their eyes rather than their head.</p>
 */
final class HeadMotionDetector extends AbstractActionDetector {

    private enum Axis { YAW, PITCH }

    private final Axis axis;
    private final int sign;
    private final double excursionDegrees;
    private final boolean acceptGaze;
    private double baseline = Double.NaN;
    private double baselineGaze = Double.NaN;
    private boolean crossed;

    HeadMotionDetector(ChallengeType type, ActionThresholds t, long issuedAtNanos) {
        super(type, t, issuedAtNanos);
        switch (type) {
            case TURN_LEFT: axis = Axis.YAW; sign = 1; excursionDegrees = t.turnYawDegrees(); acceptGaze = false; break;
            case TURN_RIGHT: axis = Axis.YAW; sign = -1; excursionDegrees = t.turnYawDegrees(); acceptGaze = false; break;
            case LOOK_UP: axis = Axis.PITCH; sign = 1; excursionDegrees = t.lookPitchDegrees(); acceptGaze = true; break;
            case LOOK_DOWN: axis = Axis.PITCH; sign = -1; excursionDegrees = t.lookPitchDegrees(); acceptGaze = true; break;
            case NOD: axis = Axis.PITCH; sign = -1; excursionDegrees = t.nodPitchDegrees(); acceptGaze = false; break;
            default: throw new IllegalArgumentException(type + " is not a head-motion challenge");
        }
    }

    @Override
    protected ActionStatus evaluate(FaceObservation face, long now) {
        double angle = axis == Axis.YAW ? face.yaw() : face.pitch();
        if (Double.isNaN(baseline)) {
            if (Math.abs(angle) <= thresholds.baselineMaxDegrees() || !thresholds.requireNeutralStart()) {
                baseline = angle;
                if (acceptGaze && face.eyeAspect() >= thresholds.blinkOpenEar()) baselineGaze = face.gazeY();
                setProgress(0.1);
            }
            return ActionStatus.WAITING;
        }
        double delta = sign * (angle - baseline);
        // Gaze, like the angle, is measured from the baseline: the eye-band gaze estimate has a per-face offset (on the
        // mock's drawn face a neutral gaze reads -0.78), so an absolute threshold fires without any movement.
        // While the eyes are closing or closed (a blink) the eye band is the eyelid, not the iris, so there is no gaze.
        boolean gazeValid = acceptGaze && face.eyeAspect() >= thresholds.blinkOpenEar();
        if (gazeValid && Double.isNaN(baselineGaze)) baselineGaze = face.gazeY();   // first open-eyed frame
        double gaze = gazeValid ? sign * (face.gazeY() - baselineGaze) : 0;
        if (!crossed) {
            if (delta >= excursionDegrees || gaze >= thresholds.gazeThreshold()) {
                crossed = true;
                markOnset(now);
                setProgress(0.6);
                return ActionStatus.IN_PROGRESS;
            }
            setProgress(0.1 + 0.5 * Math.max(0, Math.min(1, Math.max(delta / excursionDegrees, gazeValid ? gaze / thresholds.gazeThreshold() : 0))));
            return ActionStatus.WAITING;
        }
        boolean returnedAngle = Math.abs(angle - baseline) <= thresholds.returnDegrees();
        boolean returnedGaze = !gazeValid || Math.abs(face.gazeY() - baselineGaze) < thresholds.gazeThreshold() / 2;
        if (returnedAngle && returnedGaze) {
            return complete(now);
        }
        setProgress(0.6 + 0.4 * Math.max(0, Math.min(1, 1 - Math.abs(angle - baseline) / excursionDegrees)));
        return ActionStatus.IN_PROGRESS;
    }
}
