package io.mosip.vivid.core.action;

import io.mosip.vivid.api.ChallengeType;

import java.util.Objects;

/** Factory mapping every {@link ChallengeType} to its {@link ActionDetector} implementation. */
public final class ActionDetectors {

    private ActionDetectors() {}

    /** Creates a fresh detector for a challenge issued at {@code issuedAtNanos} (session clock). */
    public static ActionDetector create(ChallengeType type, ActionThresholds thresholds, long issuedAtNanos) {
        Objects.requireNonNull(type, "type");
        ActionThresholds t = Objects.requireNonNull(thresholds, "thresholds");
        switch (type) {
            case BLINK:
                return new BlinkDetector(type, t, issuedAtNanos, 1);
            case BLINK_TWICE:
                return new BlinkDetector(type, t, issuedAtNanos, 2);
            case SMILE:
                return new SustainedExpressionDetector(type, t, issuedAtNanos, SustainedExpressionDetector.smile(t), t.smileHoldMillis());
            case OPEN_MOUTH:
                return new SustainedExpressionDetector(type, t, issuedAtNanos, SustainedExpressionDetector.openMouth(t), t.mouthOpenHoldMillis());
            case RAISE_EYEBROWS:
                return new SustainedExpressionDetector(type, t, issuedAtNanos, SustainedExpressionDetector.raiseEyebrows(t), t.browRaiseHoldMillis());
            case TURN_LEFT:
            case TURN_RIGHT:
            case LOOK_UP:
            case LOOK_DOWN:
            case NOD:
                return new HeadMotionDetector(type, t, issuedAtNanos);
            case HOLD_STILL:
                return new HoldStillDetector(t, issuedAtNanos);
            default:
                throw new IllegalArgumentException("no detector for " + type);
        }
    }
}
