package io.mosip.vivid.engine.signal;

import io.mosip.vivid.engine.image.ImageOps;

/**
 * Platt scaling {@code p = sigmoid(a * raw + b)} applied to a signal's raw attack logit, plus the decision
 * threshold the signal uses for its {@code hardVeto} flag. The identity calibration ({@code a = 1, b = 0}) leaves the
 * signal's hand-tuned default mapping in place; a model bundle can override it per signal id after a calibration run
 * on the evaluation corpus.
 */
public final class PlattCalibration {
    private final double a, b, threshold;

    public PlattCalibration(double a, double b, double threshold) {
        this.a = a; this.b = b; this.threshold = threshold;
    }

    public static PlattCalibration identity() { return new PlattCalibration(1, 0, 0.5); }

    public double a() { return a; }
    public double b() { return b; }
    /** Attack probability above which the signal may raise a hard veto. */
    public double threshold() { return threshold; }

    /** Calibrated attack probability for a raw attack logit. */
    public double apply(double rawLogit) { return ImageOps.sigmoid(a * rawLogit + b); }

    @Override public String toString() { return "Platt(a=" + a + ", b=" + b + ", thr=" + threshold + ")"; }
}
