package io.mosip.vivid.av.sync;

import java.util.Locale;

/** Outcome of {@link LipSyncScorer#score}. All correlations are Pearson r on the common grid. */
public final class SyncResult {
    public enum Status { SYNCED, NO_SPEECH, INSUFFICIENT_VIDEO, STATIC_MOUTH, OUT_OF_SYNC }

    private final Status status;
    private final double peak;
    private final int lagMillis;
    private final double baselineMean;
    private final double baselineSd;
    private final double baselineMax;
    private final double mouthStd;
    private final int windowMillis;

    public SyncResult(Status status, double peak, int lagMillis, double baselineMean, double baselineSd, double baselineMax,
                      double mouthStd, int windowMillis) {
        this.status = status; this.peak = peak; this.lagMillis = lagMillis; this.baselineMean = baselineMean;
        this.baselineSd = baselineSd; this.baselineMax = baselineMax; this.mouthStd = mouthStd; this.windowMillis = windowMillis;
    }

    static SyncResult of(Status s) { return new SyncResult(s, Double.NaN, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0); }

    public Status status() { return status; }
    public boolean synced() { return status == Status.SYNCED; }
    /** Highest correlation for lags within the window; negative lag = mouth leads the audio (the usual case). */
    public double peak() { return peak; }
    public int lagMillis() { return lagMillis; }
    public double baselineMean() { return baselineMean; }
    public double baselineSd() { return baselineSd; }
    public double baselineMax() { return baselineMax; }
    /** (peak - baseline mean) / baseline sd. */
    public double z() { return baselineSd > 0 ? (peak - baselineMean) / baselineSd : Double.NaN; }
    public double mouthStd() { return mouthStd; }
    public int windowMillis() { return windowMillis; }

    @Override public String toString() {
        return String.format(Locale.ROOT, "%s peak=%.3f lag=%dms base=%.3f±%.3f max=%.3f z=%.2f mouthSd=%.4f win=%dms",
                status, peak, lagMillis, baselineMean, baselineSd, baselineMax, z(), mouthStd, windowMillis);
    }
}
