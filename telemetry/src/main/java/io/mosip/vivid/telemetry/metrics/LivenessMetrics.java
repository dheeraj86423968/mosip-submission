package io.mosip.vivid.telemetry.metrics;

import io.mosip.vivid.api.FailureReason;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Thread-safe aggregator of the anonymised "good-to-have" metrics from PLAN.md section 10.3: average passive time,
 * challenge completion time, retry rate, failure rate by reason, PAD rate by attack class and device fault rate.
 * Only counters and sums are kept; no identifiers ever enter this class.
 *
 * <p>One instance per client process is expected; {@link #snapshot()} is cheap and can be exported through the
 * existing sync, {@link #reset()} starts a new reporting period.</p>
 */
public final class LivenessMetrics {

    private final Object lock = new Object();
    private long sessionsStarted;
    private long sessionsPassed;
    private long sessionsFailed;
    private long retries;
    private long padDetections;
    private long deviceFaults;
    private long passiveSamples;
    private double passiveMillisSum;
    private long challengeSamples;
    private double challengeMillisSum;
    private final Map<String, Long> failuresByReason = new HashMap<>();
    private final Map<String, Long> padByAttackClass = new HashMap<>();
    private final Map<String, Long> deviceFaultsByKind = new HashMap<>();

    /** A session was started (first attempt only). */
    public void sessionStarted() {
        synchronized (lock) {
            sessionsStarted++;
        }
    }

    /** A session concluded with a live decision. */
    public void sessionPassed() {
        synchronized (lock) {
            sessionsPassed++;
        }
    }

    /** An attempt failed for the given reason. */
    public void sessionFailed(FailureReason reason) {
        Objects.requireNonNull(reason, "reason");
        synchronized (lock) {
            sessionsFailed++;
            failuresByReason.merge(reason.name(), 1L, Long::sum);
        }
    }

    /** A retry attempt was started. */
    public void retry() {
        synchronized (lock) {
            retries++;
        }
    }

    /** The passive phase of an attempt completed in {@code millis}. */
    public void passiveDuration(long millis) {
        synchronized (lock) {
            passiveSamples++;
            passiveMillisSum += Math.max(0, millis);
        }
    }

    /** A challenge was completed {@code millis} after being issued. */
    public void challengeCompletion(long millis) {
        synchronized (lock) {
            challengeSamples++;
            challengeMillisSum += Math.max(0, millis);
        }
    }

    /** A presentation attack of the given class (or "UNKNOWN") was detected. */
    public void padDetected(String attackClass) {
        synchronized (lock) {
            padDetections++;
            padByAttackClass.merge(attackClass == null || isBlank(attackClass) ? "UNKNOWN" : attackClass, 1L, Long::sum);
        }
    }

    /** A device fault of the given kind occurred. */
    public void deviceFault(String kind) {
        synchronized (lock) {
            deviceFaults++;
            deviceFaultsByKind.merge(kind == null || isBlank(kind) ? "UNKNOWN" : kind, 1L, Long::sum);
        }
    }

    /** Immutable view of the current counters. */
    public MetricsSnapshot snapshot() {
        synchronized (lock) {
            return new MetricsSnapshot(sessionsStarted, sessionsPassed, sessionsFailed, retries, padDetections, deviceFaults,
                    passiveSamples, passiveSamples == 0 ? 0.0 : passiveMillisSum / passiveSamples,
                    challengeSamples, challengeSamples == 0 ? 0.0 : challengeMillisSum / challengeSamples,
                    failuresByReason, padByAttackClass, deviceFaultsByKind);
        }
    }

    /** JSON of {@link #snapshot()}. */
    public String toJson() {
        return snapshot().toJson();
    }

    /** Clears every counter, starting a new reporting period. */
    public void reset() {
        synchronized (lock) {
            sessionsStarted = sessionsPassed = sessionsFailed = retries = padDetections = deviceFaults = 0;
            passiveSamples = challengeSamples = 0;
            passiveMillisSum = challengeMillisSum = 0;
            failuresByReason.clear();
            padByAttackClass.clear();
            deviceFaultsByKind.clear();
        }
    }

    /** {@code String.isBlank()} without the Java 11 API, which Android API 28 lacks. */
    private static boolean isBlank(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (!Character.isWhitespace(cp)) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }
}
