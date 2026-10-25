package io.mosip.vivid.telemetry.diagnostics;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Sliding-window frame-rate estimator driven by caller-supplied timestamps, so it is deterministic in tests.
 * Not thread-safe; callers synchronise (the orchestrator holds its own lock).
 */
public final class FpsMeter {

    private final long windowNanos;
    private final Deque<Long> stamps = new ArrayDeque<>();

    /** Meter averaging over the given window. */
    public FpsMeter(long windowMillis) {
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("window must be positive");
        }
        this.windowNanos = windowMillis * 1_000_000L;
    }

    /** Records a frame observed at {@code nowNanos}. */
    public void tick(long nowNanos) {
        stamps.addLast(nowNanos);
        evict(nowNanos);
    }

    /** Frames per second over the window ending at {@code nowNanos}; 0 with fewer than two frames. */
    public double fps(long nowNanos) {
        evict(nowNanos);
        if (stamps.size() < 2) {
            return 0.0;
        }
        long span = stamps.peekLast() - stamps.peekFirst();
        return span <= 0 ? 0.0 : (stamps.size() - 1) * 1_000_000_000.0 / span;
    }

    /** Forgets all frames. */
    public void reset() {
        stamps.clear();
    }

    private void evict(long nowNanos) {
        while (!stamps.isEmpty() && nowNanos - stamps.peekFirst() > windowNanos) {
            stamps.pollFirst();
        }
    }
}
