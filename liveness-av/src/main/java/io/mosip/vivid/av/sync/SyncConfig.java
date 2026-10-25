package io.mosip.vivid.av.sync;

/**
 * Parameters of the lip-audio synchrony test. Defaults were set on synthetic data (TTS audio with synthetic mouth
 * tracks, see docs/testing/av-liveness.md) and must be recalibrated on real speakers before production use.
 */
public final class SyncConfig {
    public final int gridMillis;
    public final int maxLagMillis;
    public final int marginMillis;
    public final int baselineCount;
    public final int minShiftMillis;
    public final long maxVideoGapMillis;
    public final int minWindowMillis;
    /** Minimum peak Pearson correlation inside the lag window. */
    public final double minPeak;
    /** Minimum (peak - baseline mean) / baseline sd. */
    public final double minZ;
    /** Minimum margin of the peak over the largest shuffled-offset baseline. */
    public final double minMarginOverBaselineMax;
    /** Minimum standard deviation of mouth opening across the utterance (static-face test), in mouthAspect units. */
    public final double minMouthStd;

    private SyncConfig(Builder b) {
        gridMillis = b.gridMillis; maxLagMillis = b.maxLagMillis; marginMillis = b.marginMillis; baselineCount = b.baselineCount;
        minShiftMillis = b.minShiftMillis; maxVideoGapMillis = b.maxVideoGapMillis; minWindowMillis = b.minWindowMillis;
        minPeak = b.minPeak; minZ = b.minZ; minMarginOverBaselineMax = b.minMarginOverBaselineMax; minMouthStd = b.minMouthStd;
    }

    public static SyncConfig defaults() { return builder().build(); }
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private int gridMillis = 20, maxLagMillis = 200, marginMillis = 300, baselineCount = 24, minShiftMillis = 500;
        private long maxVideoGapMillis = 250;
        private int minWindowMillis = 1200;
        private double minPeak = 0.6, minZ = 2.0, minMarginOverBaselineMax = 0.1, minMouthStd = 0.015;
        public Builder gridMillis(int v) { gridMillis = v; return this; }
        public Builder maxLagMillis(int v) { maxLagMillis = v; return this; }
        public Builder marginMillis(int v) { marginMillis = v; return this; }
        public Builder baselineCount(int v) { baselineCount = v; return this; }
        public Builder minShiftMillis(int v) { minShiftMillis = v; return this; }
        public Builder maxVideoGapMillis(long v) { maxVideoGapMillis = v; return this; }
        public Builder minWindowMillis(int v) { minWindowMillis = v; return this; }
        public Builder minPeak(double v) { minPeak = v; return this; }
        public Builder minZ(double v) { minZ = v; return this; }
        public Builder minMarginOverBaselineMax(double v) { minMarginOverBaselineMax = v; return this; }
        public Builder minMouthStd(double v) { minMouthStd = v; return this; }
        public SyncConfig build() {
            if (gridMillis <= 0 || maxLagMillis < 0 || baselineCount < 4) throw new IllegalArgumentException("invalid sync config");
            return new SyncConfig(this);
        }
    }
}
