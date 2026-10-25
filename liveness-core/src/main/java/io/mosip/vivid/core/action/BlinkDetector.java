package io.mosip.vivid.core.action;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.FaceObservation;

/**
 * BLINK and BLINK_TWICE: the eye aspect ratio drops below {@code blinkCloseEar}, then recovers above
 * {@code blinkOpenEar} after a closure lasting between {@code blinkMinClosedMillis} and
 * {@code blinkMaxClosedMillis}. The eyes must be seen open after the prompt before a closure counts, so a blink
 * already in progress (or a looping replay) cannot satisfy the challenge. For BLINK_TWICE both blinks must end
 * within {@code blinkTwiceWindowMillis} of each other.
 */
final class BlinkDetector extends AbstractActionDetector {

    private final int requiredBlinks;
    private boolean seenOpen;
    private long closedAtNanos = -1;
    private int blinks;
    private long lastBlinkEndNanos = -1;

    BlinkDetector(ChallengeType type, ActionThresholds t, long issuedAtNanos, int requiredBlinks) {
        super(type, t, issuedAtNanos);
        this.requiredBlinks = requiredBlinks;
        this.seenOpen = !t.requireNeutralStart();
    }

    @Override
    protected ActionStatus evaluate(FaceObservation face, long now) {
        double ear = face.eyeAspect();
        if (closedAtNanos < 0) {
            if (ear > thresholds.blinkOpenEar()) {
                seenOpen = true;
            } else if (ear < thresholds.blinkCloseEar() && seenOpen) {
                closedAtNanos = now;
                markOnset(now);
            }
            setProgress((blinks + (closedAtNanos >= 0 ? 0.5 : 0)) / requiredBlinks);
            return pending();
        }
        long closedMs = millis(now - closedAtNanos);
        if (ear > thresholds.blinkOpenEar()) {
            closedAtNanos = -1;
            if (closedMs >= thresholds.blinkMinClosedMillis() && closedMs <= thresholds.blinkMaxClosedMillis()) {
                return registerBlink(now);
            }
            // Too short to be a closure the model is sure about: not a blink.
            if (blinks == 0) {
                clearOnset();
            }
            setProgress((double) blinks / requiredBlinks);
            return pending();
        }
        if (closedMs > thresholds.blinkMaxClosedMillis()) {
            // Eyes held shut: not a blink. Wait for them to open again.
            closedAtNanos = -1;
            seenOpen = false;
            if (blinks == 0) {
                clearOnset();
            }
            setProgress((double) blinks / requiredBlinks);
        }
        return pending();
    }

    private ActionStatus registerBlink(long now) {
        if (blinks > 0 && lastBlinkEndNanos >= 0 && millis(now - lastBlinkEndNanos) > thresholds.blinkTwiceWindowMillis()) {
            // Too long since the previous blink: this one starts a new pair.
            blinks = 0;
        }
        blinks++;
        lastBlinkEndNanos = now;
        if (blinks >= requiredBlinks) {
            return complete(now);
        }
        setProgress((double) blinks / requiredBlinks);
        return ActionStatus.IN_PROGRESS;
    }
}
