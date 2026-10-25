package io.mosip.vivid.core.testing;

import io.mosip.vivid.core.session.TimeSource;

import java.time.Instant;

/** Manually advanced clock so every timeout and latency rule is tested deterministically. */
public final class FakeTime implements TimeSource {

    private long nanos = 1_000_000_000L;

    @Override
    public long nanoTime() {
        return nanos;
    }

    @Override
    public Instant now() {
        return Instant.ofEpochSecond(1_700_000_000L).plusNanos(nanos);
    }

    /** Moves the clock forward. */
    public FakeTime advance(long millis) {
        nanos += millis * 1_000_000L;
        return this;
    }

    public long millis() {
        return nanos / 1_000_000L;
    }
}
