package io.mosip.vivid.telemetry.metrics;

import io.mosip.vivid.telemetry.json.Json;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable point-in-time view of {@link LivenessMetrics}. Contains only counters, averages and rates aggregated
 * per centre; no session, resident or operator identifiers (PLAN.md section 10.3).
 */
public final class MetricsSnapshot {

    private final long sessionsStarted;
    private final long sessionsPassed;
    private final long sessionsFailed;
    private final long retries;
    private final long padDetections;
    private final long deviceFaults;
    private final long passiveSamples;
    private final double averagePassiveMillis;
    private final long challengeSamples;
    private final double averageChallengeCompletionMillis;
    private final Map<String, Long> failuresByReason;
    private final Map<String, Long> padByAttackClass;
    private final Map<String, Long> deviceFaultsByKind;

    MetricsSnapshot(long sessionsStarted, long sessionsPassed, long sessionsFailed, long retries, long padDetections,
                    long deviceFaults, long passiveSamples, double averagePassiveMillis, long challengeSamples,
                    double averageChallengeCompletionMillis, Map<String, Long> failuresByReason,
                    Map<String, Long> padByAttackClass, Map<String, Long> deviceFaultsByKind) {
        this.sessionsStarted = sessionsStarted;
        this.sessionsPassed = sessionsPassed;
        this.sessionsFailed = sessionsFailed;
        this.retries = retries;
        this.padDetections = padDetections;
        this.deviceFaults = deviceFaults;
        this.passiveSamples = passiveSamples;
        this.averagePassiveMillis = averagePassiveMillis;
        this.challengeSamples = challengeSamples;
        this.averageChallengeCompletionMillis = averageChallengeCompletionMillis;
        this.failuresByReason = Collections.unmodifiableMap(new TreeMap<>(failuresByReason));
        this.padByAttackClass = Collections.unmodifiableMap(new TreeMap<>(padByAttackClass));
        this.deviceFaultsByKind = Collections.unmodifiableMap(new TreeMap<>(deviceFaultsByKind));
    }

    public long sessionsStarted() { return sessionsStarted; }
    public long sessionsPassed() { return sessionsPassed; }
    /** Failed attempts (a session with retries counts each failed attempt). */
    public long sessionsFailed() { return sessionsFailed; }
    public long retries() { return retries; }
    public long padDetections() { return padDetections; }
    public long deviceFaults() { return deviceFaults; }
    public long passiveSamples() { return passiveSamples; }
    /** Mean passive-phase duration, or 0 when no sample. */
    public double averagePassiveMillis() { return averagePassiveMillis; }
    public long challengeSamples() { return challengeSamples; }
    /** Mean prompt-to-completion time of passed challenges, or 0 when no sample. */
    public double averageChallengeCompletionMillis() { return averageChallengeCompletionMillis; }
    /** Failed attempts keyed by {@code FailureReason} name. */
    public Map<String, Long> failuresByReason() { return failuresByReason; }
    /** Presentation attacks keyed by attack class. */
    public Map<String, Long> padByAttackClass() { return padByAttackClass; }
    /** Device faults keyed by kind. */
    public Map<String, Long> deviceFaultsByKind() { return deviceFaultsByKind; }

    /** Retries per started session. */
    public double retryRate() { return rate(retries, sessionsStarted); }
    /** Failed attempts per started session. */
    public double failureRate() { return rate(sessionsFailed, sessionsStarted); }
    /** Presentation attacks per started session. */
    public double padRate() { return rate(padDetections, sessionsStarted); }
    /** Device faults per started session. */
    public double deviceFaultRate() { return rate(deviceFaults, sessionsStarted); }

    private static double rate(long num, long den) {
        return den == 0 ? 0.0 : (double) num / den;
    }

    /** Compact JSON for export through the existing sync. */
    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionsStarted", sessionsStarted);
        m.put("sessionsPassed", sessionsPassed);
        m.put("sessionsFailed", sessionsFailed);
        m.put("retries", retries);
        m.put("padDetections", padDetections);
        m.put("deviceFaults", deviceFaults);
        m.put("averagePassiveMillis", averagePassiveMillis);
        m.put("averageChallengeCompletionMillis", averageChallengeCompletionMillis);
        m.put("retryRate", retryRate());
        m.put("failureRate", failureRate());
        m.put("padRate", padRate());
        m.put("deviceFaultRate", deviceFaultRate());
        m.put("failuresByReason", failuresByReason);
        m.put("padByAttackClass", padByAttackClass);
        m.put("deviceFaultsByKind", deviceFaultsByKind);
        return Json.write(m);
    }

    @Override
    public String toString() {
        return toJson();
    }
}
