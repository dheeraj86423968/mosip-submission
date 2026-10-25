package io.mosip.vivid.testkit.metrics;

/**
 * An error (or non-response) count {@code k} out of {@code n} presentations, with its point estimate and a Wilson
 * score confidence interval. The Wilson interval is used rather than the normal approximation because it stays
 * inside [0,1] and behaves sensibly for the small counts and zero-error cells of a self-evaluation corpus
 * (0 errors out of 20 gives an upper 95 % bound of about 16 %).
 */
public record Rate(int k, int n) {
    /** z for a two-sided 95 % interval. */
    public static final double Z95 = 1.959963984540054;

    public Rate {
        if (k < 0 || n < 0 || k > n) throw new IllegalArgumentException("need 0 <= k <= n, got k=" + k + " n=" + n);
    }

    /** k / n, or NaN when n = 0. */
    public double value() { return n == 0 ? Double.NaN : k / (double) n; }

    /** Wilson 95 % interval; both bounds NaN when n = 0. */
    public Interval wilson95() { return wilson(k, n, Z95); }

    /** Wilson score interval for k successes in n trials at the given z. */
    public static Interval wilson(int k, int n, double z) {
        if (n == 0) return new Interval(Double.NaN, Double.NaN);
        double p = k / (double) n, z2 = z * z;
        double denom = 1 + z2 / n;
        double centre = (p + z2 / (2.0 * n)) / denom;
        double half = z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / denom;
        // exact bounds at the edges (avoid -1e-17 and 1+1e-16 from rounding)
        double lo = k == 0 ? 0.0 : Math.max(0, centre - half);
        double hi = k == n ? 1.0 : Math.min(1, centre + half);
        return new Interval(lo, hi);
    }

    /** A closed interval [lower, upper] in [0,1]; NaN bounds mean "undefined" (no presentations). */
    public record Interval(double lower, double upper) {
        public boolean defined() { return !Double.isNaN(lower) && !Double.isNaN(upper); }
    }
}
