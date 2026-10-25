package io.mosip.vivid.av.testing;

import io.mosip.vivid.core.session.TimeSource;

import java.util.concurrent.atomic.AtomicLong;

/** Manually advanced session clock shared by the orchestrator, the AV analyzer and the test audio source. */
public final class FakeClock implements TimeSource {
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    @Override public long nanoTime() { return nanos.get(); }
    @Override public java.time.Instant now() { long n = nanos.get(); return java.time.Instant.ofEpochSecond(n / 1_000_000_000L, n % 1_000_000_000L); }
    public void advanceMillis(long ms) { nanos.addAndGet(ms * 1_000_000L); }
    public long millis() { return nanos.get() / 1_000_000L; }
}
