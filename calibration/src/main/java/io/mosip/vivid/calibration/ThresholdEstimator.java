package io.mosip.vivid.calibration;

import io.mosip.vivid.telemetry.calibration.ScoreBinning;

/**
 * Re-estimates the passive threshold that achieves a target BPCER from an aggregated, noisy bona fide histogram
 * (design 14-dp-calibration.md section 6). Pure post-processing of DP outputs, so it costs no privacy.
 *
 * <p>The empirical CDF is formed from the raw noisy cumulative sums divided by the noisy total (both unbiased), made
 * monotone by a running maximum, and inverted at the target with linear interpolation in logit space inside the
 * crossing bin. The standard error of the BPCER estimate at the chosen threshold combines the DP noise of the
 * cumulative sum ({@code k * noiseVariancePerBin} for k bins) with binomial sampling error.</p>
 */
public final class ThresholdEstimator {

    /** Result of one estimate. */
    public record Estimate(double threshold, double bonaFideTotal, double bpcerStdError, double rejectedTotal) {
        /** True when there was too little data to estimate anything. */
        public boolean empty() {
            return Double.isNaN(threshold);
        }
    }

    private ThresholdEstimator() {}

    public static Estimate estimate(AggregatedHistogram h, double targetBpcer) {
        if (!(targetBpcer > 0 && targetBpcer < 1)) {
            throw new IllegalArgumentException("targetBpcer must be in (0,1)");
        }
        double[] c = h.bonaFide();
        double total = 0;
        for (double v : c) total += v;
        double rejTotal = 0;
        for (double v : h.rejected()) rejTotal += v;
        if (total <= 0) {
            return new Estimate(Double.NaN, total, Double.NaN, rejTotal);
        }
        ScoreBinning b = h.binning();
        double want = targetBpcer * total;
        double cum = 0;
        double monotone = 0;
        for (int i = 0; i < c.length; i++) {
            double before = monotone;
            cum += c[i];
            monotone = Math.max(monotone, cum);
            if (monotone >= want) {
                double inBin = monotone - before;
                double frac = inBin <= 0 ? 0 : (want - before) / inBin;
                double t = b.scoreAt(i, Math.max(0, Math.min(1, frac)));
                double k = i + frac;
                double noiseVar = k * h.noiseVariancePerBin();
                double samplingVar = total * targetBpcer * (1 - targetBpcer);
                return new Estimate(t, total, Math.sqrt(noiseVar + samplingVar) / total, rejTotal);
            }
        }
        return new Estimate(b.upperEdge(c.length - 1), total, Double.NaN, rejTotal);
    }

    /** Estimated BPCER at a given threshold: fraction of the bona fide mass below it (interpolated, clamped to [0,1]). */
    public static double bpcerAt(AggregatedHistogram h, double threshold) {
        return massBelow(h.bonaFide(), h.binning(), threshold);
    }

    /**
     * Fraction of the <em>rejected</em> mass whose score lies in [lo, hi): when loosening from hi to lo, the share of
     * sessions rejected today that the new threshold would have let through passive evaluation. Attacks are hidden in
     * that mass, so the rules cap it.
     */
    public static double rejectedShareBetween(AggregatedHistogram h, double lo, double hi) {
        if (hi <= lo) {
            return 0;
        }
        return Math.max(0, massBelow(h.rejected(), h.binning(), hi) - massBelow(h.rejected(), h.binning(), lo));
    }

    static double massBelow(double[] c, ScoreBinning b, double threshold) {
        double total = 0;
        for (double v : c) total += v;
        if (total <= 0) {
            return Double.NaN;
        }
        double pos = b.position(threshold);
        double below = 0;
        int whole = (int) Math.floor(pos);
        for (int i = 0; i < Math.min(whole, c.length); i++) {
            below += c[i];
        }
        if (whole < c.length) {
            below += (pos - whole) * c[whole];
        }
        return Math.max(0, Math.min(1, below / total));
    }
}
