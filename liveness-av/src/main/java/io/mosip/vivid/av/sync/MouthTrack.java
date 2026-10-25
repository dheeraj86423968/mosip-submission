package io.mosip.vivid.av.sync;

import java.util.Arrays;

/**
 * Mouth-opening samples (engine {@code FaceObservation.mouthAspect()}) stamped with the session clock. Samples must
 * be added in non-decreasing time order; out-of-order samples are dropped.
 */
public final class MouthTrack {
    private long[] t = new long[64];
    private double[] v = new double[64];
    private int n;

    public synchronized void add(long nanos, double value) {
        if (Double.isNaN(value) || (n > 0 && nanos < t[n - 1])) return;
        if (n == t.length) {
            t = Arrays.copyOf(t, n * 2);
            v = Arrays.copyOf(v, n * 2);
        }
        t[n] = nanos;
        v[n] = value;
        n++;
    }

    public synchronized int size() { return n; }
    public synchronized long nanos(int i) { return t[i]; }
    public synchronized double value(int i) { return v[i]; }

    /** Linear interpolation at {@code nanos}; NaN outside the sampled span or across a gap longer than {@code maxGapNanos}. */
    public synchronized double at(long nanos, long maxGapNanos) {
        if (n == 0 || nanos < t[0] || nanos > t[n - 1]) return Double.NaN;
        int i = Arrays.binarySearch(t, 0, n, nanos);
        if (i >= 0) return v[i];
        int hi = -i - 1, lo = hi - 1;
        if (t[hi] - t[lo] > maxGapNanos) return Double.NaN;
        double f = (double) (nanos - t[lo]) / (t[hi] - t[lo]);
        return v[lo] + (v[hi] - v[lo]) * f;
    }

    public synchronized void clear() {
        Arrays.fill(v, 0, n, 0);
        n = 0;
    }
}
