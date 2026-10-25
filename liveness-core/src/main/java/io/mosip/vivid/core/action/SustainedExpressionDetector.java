package io.mosip.vivid.core.action;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.FaceObservation;

import java.util.function.BiPredicate;

/**
 * SMILE, OPEN_MOUTH and RAISE_EYEBROWS: an expression predicate must hold continuously for a hold duration.
 * The first observation after the prompt is kept as the neutral baseline (used by the smile fallback heuristic),
 * and with {@code requireNeutralStart} the predicate must be observed false once before an onset counts.
 */
final class SustainedExpressionDetector extends AbstractActionDetector {

    /** Expression test: (observation, neutral baseline or null) → active. */
    interface Expression extends BiPredicate<FaceObservation, FaceObservation> {}

    private final Expression expression;
    private final long holdMillis;
    private FaceObservation baseline;
    private boolean seenNeutral;
    private long activeSinceNanos = -1;

    SustainedExpressionDetector(ChallengeType type, ActionThresholds t, long issuedAtNanos, Expression expression, long holdMillis) {
        super(type, t, issuedAtNanos);
        this.expression = expression;
        this.holdMillis = holdMillis;
        this.seenNeutral = !t.requireNeutralStart();
    }

    @Override
    protected ActionStatus evaluate(FaceObservation face, long now) {
        if (baseline == null) {
            baseline = face;
        }
        boolean active = expression.test(face, baseline);
        if (!active) {
            seenNeutral = true;
            activeSinceNanos = -1;
            clearOnset();
            setProgress(0);
            return ActionStatus.WAITING;
        }
        if (!seenNeutral) {
            return ActionStatus.WAITING;
        }
        if (activeSinceNanos < 0) {
            activeSinceNanos = now;
            markOnset(now);
        }
        long held = millis(now - activeSinceNanos);
        if (held >= holdMillis) {
            return complete(now);
        }
        setProgress((double) held / holdMillis);
        return ActionStatus.IN_PROGRESS;
    }

    /** Smile: smile score, smile blendshapes, or (when the engine provides no smile score) mouth narrowing against the baseline. */
    static Expression smile(ActionThresholds t) {
        return (face, base) -> {
            if (face.smileScore() > 0) {
                return face.smileScore() >= t.smileScore();
            }
            Double l = face.blendshapes().get("mouthSmileLeft");
            Double r = face.blendshapes().get("mouthSmileRight");
            if (l != null && r != null) {
                return (l + r) / 2 >= t.smileScore();
            }
            return base != null && base.mouthAspect() > 0 && face.mouthAspect() <= base.mouthAspect() * t.smileMouthNarrowingRatio();
        };
    }

    static Expression openMouth(ActionThresholds t) {
        return (face, base) -> face.mouthAspect() >= t.mouthOpenAspect();
    }

    static Expression raiseEyebrows(ActionThresholds t) {
        return (face, base) -> {
            if (face.browRaiseScore() > 0) {
                return face.browRaiseScore() >= t.browRaiseScore();
            }
            Double l = face.blendshapes().get("browInnerUp");
            return l != null && l >= t.browRaiseScore();
        };
    }
}
