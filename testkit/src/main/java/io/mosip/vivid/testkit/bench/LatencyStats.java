package io.mosip.vivid.testkit.bench;

import java.util.Arrays;
import java.util.Locale;

/** Percentiles of a sample of durations in nanoseconds. */
public final class LatencyStats {
    private final long[] sorted;
    private final double mean;

    public LatencyStats(long[] samples) {
        if (samples.length == 0) throw new IllegalArgumentException("no samples");
        sorted = samples.clone();
        Arrays.sort(sorted);
        mean = Arrays.stream(sorted).average().orElse(0);
    }

    public int count() { return sorted.length; }
    public long min() { return sorted[0]; }
    public long max() { return sorted[sorted.length - 1]; }
    public double mean() { return mean; }
    public long p50() { return percentile(50); }
    public long p95() { return percentile(95); }
    public long p99() { return percentile(99); }

    /** Nearest-rank percentile. */
    public long percentile(double p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

    @Override public String toString() {
        return String.format(Locale.ROOT, "n=%d p50=%.2fms p95=%.2fms p99=%.2fms mean=%.2fms max=%.2fms",
                count(), p50() / 1e6, p95() / 1e6, p99() / 1e6, mean / 1e6, max() / 1e6);
    }
}
