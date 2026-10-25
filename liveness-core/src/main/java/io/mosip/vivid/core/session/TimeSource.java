package io.mosip.vivid.core.session;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The orchestrator's only notion of time: a monotonic nanosecond counter for durations and timeouts plus a
 * wall-clock {@link Instant} for audit timestamps. Injected so that every timing rule can be tested
 * deterministically; production uses {@link #system()}.
 *
 * <p>Frame timestamps from the device are deliberately <em>not</em> used for timing decisions: a replayed or
 * injected stream can carry any timestamps it likes, whereas the session clock cannot be influenced by the
 * presentation.</p>
 */
public interface TimeSource {

    /** Monotonic nanoseconds; only differences are meaningful. */
    long nanoTime();

    /** Wall-clock time for audit records. */
    Instant now();

    /** Convenience: milliseconds elapsed since {@code startNanos}. */
    default long millisSince(long startNanos) {
        return (nanoTime() - startNanos) / 1_000_000L;
    }

    /** {@link System#nanoTime()} and {@link Instant#now()}. */
    static TimeSource system() {
        return new TimeSource() {
            @Override public long nanoTime() { return System.nanoTime(); }
            @Override public Instant now() { return Instant.now(); }
        };
    }

    /** Derives both monotonic and wall-clock time from a {@link Clock} (e.g. {@code Clock.fixed} or an offset clock in tests). */
    static TimeSource of(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        return new TimeSource() {
            @Override public long nanoTime() { return clock.millis() * 1_000_000L; }
            @Override public Instant now() { return clock.instant(); }
        };
    }

    /** Uses a nanosecond supplier for durations; wall-clock time is derived by treating the nanos as an epoch offset. */
    static TimeSource of(LongSupplier nanos) {
        Objects.requireNonNull(nanos, "nanos");
        return new TimeSource() {
            @Override public long nanoTime() { return nanos.getAsLong(); }
            @Override public Instant now() { long n = nanos.getAsLong(); return Instant.ofEpochSecond(n / 1_000_000_000L, n % 1_000_000_000L); }
        };
    }
}
