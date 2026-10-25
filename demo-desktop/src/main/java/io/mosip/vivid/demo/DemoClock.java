package io.mosip.vivid.demo;

import io.mosip.vivid.core.session.TimeSource;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Session clock that follows the frame timeline instead of the wall clock. The demo's frame source advances it to
 * each frame's timestamp just before the frame is fed, so challenge windows, passive windows and timeouts run on
 * the same timeline the simulated subject reacts on. This keeps the demo correct even when inference is slower than
 * the 15 fps the mock device produces, and makes headless recordings deterministic.
 * Audit timestamps are the session's wall-clock start plus the elapsed frame time.
 */
public final class DemoClock implements TimeSource {
    private final AtomicLong nanos = new AtomicLong();
    private final Instant origin = Instant.now();

    /** Moves the clock forward to {@code frameNanos}; never moves it back. */
    public void advanceTo(long frameNanos) { nanos.accumulateAndGet(frameNanos, Math::max); }

    @Override public long nanoTime() { return nanos.get(); }
    @Override public Instant now() { return origin.plusNanos(nanos.get()); }
    /** Elapsed session time in milliseconds. */
    public long millis() { return nanos.get() / 1_000_000L; }
}
