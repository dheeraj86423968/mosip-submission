package io.mosip.vivid.telemetry.calibration;

import java.util.Locale;
import java.util.Objects;

/**
 * Fixed binning of fused liveness scores for calibration histograms (design 14-dp-calibration.md section 3).
 *
 * <p>Bins are uniform in logit space, {@code z = ln(s / (1 - s))}, between {@code lo} and {@code hi}; scores
 * outside are clamped into the first or last bin. Fused scores saturate close to 1 for live subjects (0.99+ in the
 * proxy evaluation) while thresholds sit around 0.8 to 0.9, so a uniform grid on [0, 1] would put nearly every bona
 * fide sample into one bin. The binning is public, data-independent and fixed per release, which the DP argument
 * needs: only the counts carry information about residents.</p>
 *
 * <p>Immutable and thread-safe.</p>
 */
public final class ScoreBinning {

    /** 60 bins of 0.2 logits from z = -4 (score 0.018) to z = 8 (score 0.99966). */
    public static final ScoreBinning DEFAULT = new ScoreBinning(-4.0, 8.0, 60);

    private final double lo;
    private final double hi;
    private final int bins;

    public ScoreBinning(double lo, double hi, int bins) {
        if (!(hi > lo) || bins < 2) {
            throw new IllegalArgumentException("need hi > lo and at least two bins");
        }
        this.lo = lo;
        this.hi = hi;
        this.bins = bins;
    }

    /** Number of bins. */
    public int bins() {
        return bins;
    }

    /** Bin index of a score in [0, 1]; NaN is rejected. */
    public int bin(double score) {
        if (Double.isNaN(score)) {
            throw new IllegalArgumentException("score is NaN");
        }
        double z = logit(score);
        int i = (int) Math.floor((z - lo) / width());
        return Math.max(0, Math.min(bins - 1, i));
    }

    /** Score at fractional position {@code frac} in [0, 1] inside bin {@code i} (linear in logit space). */
    public double scoreAt(int i, double frac) {
        return sigmoid(lo + (i + frac) * width());
    }

    /** Lower edge of bin {@code i} as a score. */
    public double lowerEdge(int i) {
        return scoreAt(i, 0);
    }

    /** Upper edge of bin {@code i} as a score. */
    public double upperEdge(int i) {
        return scoreAt(i, 1);
    }

    /** Fractional bin coordinate of a score (inverse of {@link #scoreAt}), clamped to [0, bins]. */
    public double position(double score) {
        double p = (logit(score) - lo) / width();
        return Math.max(0, Math.min(bins, p));
    }

    /** Wire identifier, e.g. {@code logit:-4.0:8.0:60}. */
    public String id() {
        return String.format(Locale.ROOT, "logit:%.1f:%.1f:%d", lo, hi, bins);
    }

    /** Parses {@link #id()}. */
    public static ScoreBinning parse(String id) {
        Objects.requireNonNull(id, "id");
        String[] p = id.split(":");
        if (p.length != 4 || !p[0].equals("logit")) {
            throw new IllegalArgumentException("unknown binning " + id);
        }
        return new ScoreBinning(Double.parseDouble(p[1]), Double.parseDouble(p[2]), Integer.parseInt(p[3]));
    }

    private double width() {
        return (hi - lo) / bins;
    }

    static double logit(double s) {
        double c = Math.max(1e-12, Math.min(1 - 1e-12, s));
        return Math.log(c / (1 - c));
    }

    static double sigmoid(double z) {
        return 1 / (1 + Math.exp(-z));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ScoreBinning && ((ScoreBinning) o).id().equals(id());
    }

    @Override
    public int hashCode() {
        return id().hashCode();
    }

    @Override
    public String toString() {
        return id();
    }
}
