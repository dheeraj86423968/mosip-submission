package io.mosip.vivid.telemetry.calibration;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Random;

/**
 * Additive-noise mechanism applied independently to every count of a calibration histogram
 * (design 14-dp-calibration.md section 4).
 *
 * <ul>
 *   <li>{@link #discreteLaplace(String)}: pure epsilon-DP. Noise scale {@code sensitivity / epsilon} where the
 *       sensitivity is the L1 bound one subject can contribute.</li>
 *   <li>{@link #discreteGaussian(double, double)}: rho-zCDP with {@code sigma^2 = sensitivity^2 / (2 rho)}, configured
 *       from a target (epsilon, delta) via {@code epsilon = rho + 2 sqrt(rho ln(1/delta))}. The L2 sensitivity is
 *       bounded by the L1 bound, which is used (conservatively).</li>
 * </ul>
 *
 * <p>Immutable and thread-safe; randomness comes from the caller.</p>
 */
public final class DpMechanism {

    /** Mechanism identifiers on the wire. */
    public static final String LAPLACE = "discrete-laplace";
    public static final String GAUSSIAN = "discrete-gaussian";

    private final String id;
    private final BigDecimal epsilon;     // Laplace
    private final double rho;             // Gaussian
    private final double delta;           // Gaussian (for reporting)

    private DpMechanism(String id, BigDecimal epsilon, double rho, double delta) {
        this.id = id;
        this.epsilon = epsilon;
        this.rho = rho;
        this.delta = delta;
    }

    /** Discrete Laplace with privacy parameter epsilon given as a decimal string, e.g. "0.5". */
    public static DpMechanism discreteLaplace(String epsilon) {
        BigDecimal e = new BigDecimal(Objects.requireNonNull(epsilon, "epsilon")).stripTrailingZeros();
        if (e.signum() <= 0) {
            throw new IllegalArgumentException("epsilon must be positive");
        }
        return new DpMechanism(LAPLACE, e, 0, 0);
    }

    /** Discrete Gaussian calibrated so that one release is (epsilon, delta)-DP through zCDP. */
    public static DpMechanism discreteGaussian(double epsilon, double delta) {
        if (!(epsilon > 0) || !(delta > 0 && delta < 1)) {
            throw new IllegalArgumentException("need epsilon > 0 and 0 < delta < 1");
        }
        double l = Math.log(1 / delta);
        double sqrtRho = -Math.sqrt(l) + Math.sqrt(l + epsilon);
        return new DpMechanism(GAUSSIAN, null, sqrtRho * sqrtRho, delta);
    }

    public String id() {
        return id;
    }

    /** Pure-DP epsilon of one release (Laplace), 0 for the Gaussian mechanism. */
    public double pureEpsilon() {
        return epsilon == null ? 0 : epsilon.doubleValue();
    }

    /** zCDP rho of one release (Gaussian), 0 for the Laplace mechanism. */
    public double rho() {
        return rho;
    }

    /** (epsilon, delta) of one release: epsilon itself for Laplace, the zCDP conversion for Gaussian. */
    public double epsilonAt(double delta) {
        return epsilon != null ? epsilon.doubleValue() : PrivacyBudgetLedger.zcdpToEpsilon(rho, delta);
    }

    /** Delta the Gaussian mechanism was configured for (0 for Laplace). */
    public double delta() {
        return delta;
    }

    /** Variance of the noise added to each count for the given sensitivity. */
    public double noiseVariance(int sensitivity) {
        if (epsilon != null) {
            double q = Math.exp(-epsilon.doubleValue() / sensitivity);
            return 2 * q / ((1 - q) * (1 - q));
        }
        return (double) sensitivity * sensitivity / (2 * rho);
    }

    /** Returns {@code counts} plus independent noise calibrated to {@code sensitivity}. */
    public long[] privatize(long[] counts, int sensitivity, Random rnd) {
        Objects.requireNonNull(counts, "counts");
        Objects.requireNonNull(rnd, "rnd");
        if (sensitivity < 1) {
            throw new IllegalArgumentException("sensitivity must be at least 1");
        }
        long[] out = new long[counts.length];
        if (epsilon != null) {
            // scale = sensitivity / epsilon = sensitivity * 10^scale / unscaled
            BigInteger unscaled = epsilon.unscaledValue();
            BigInteger s = BigInteger.valueOf(sensitivity);
            BigInteger t = BigInteger.ONE;
            if (epsilon.scale() >= 0) {
                s = s.multiply(BigInteger.TEN.pow(epsilon.scale()));
                t = unscaled;
            } else {
                t = unscaled.multiply(BigInteger.TEN.pow(-epsilon.scale()));
            }
            BigInteger g = s.gcd(t);
            long sl = s.divide(g).longValueExact();
            long tl = t.divide(g).longValueExact();
            for (int i = 0; i < counts.length; i++) {
                out[i] = counts[i] + ExactSampling.discreteLaplace(rnd, sl, tl);
            }
        } else {
            // sigma^2 = sensitivity^2 / (2 rho), rounded UP to a rational with 12 significant digits (more noise is safe)
            BigDecimal sigma2 = new BigDecimal((double) sensitivity * sensitivity).divide(new BigDecimal(2 * rho), new MathContext(12, RoundingMode.UP));
            BigInteger num = sigma2.unscaledValue();
            BigInteger den = BigInteger.ONE;
            if (sigma2.scale() > 0) {
                den = BigInteger.TEN.pow(sigma2.scale());
            } else {
                num = num.multiply(BigInteger.TEN.pow(-sigma2.scale()));
            }
            for (int i = 0; i < counts.length; i++) {
                out[i] = counts[i] + ExactSampling.discreteGaussian(rnd, num, den);
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return epsilon != null ? id + "(epsilon=" + epsilon.toPlainString() + ")" : id + "(rho=" + rho + ", delta=" + delta + ")";
    }
}
