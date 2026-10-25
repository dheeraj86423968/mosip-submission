package io.mosip.vivid.telemetry.diagnostics;

import io.mosip.vivid.telemetry.json.Json;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable snapshot for the supervisor-gated diagnostic screen (PLAN.md section 10.2): last per-signal scores,
 * frame rate, engine and model-bundle versions, device health and session phase. Never contains frames.
 */
public final class DiagnosticSnapshot {

    private final Instant capturedAt;
    private final String sessionId;
    private final String phase;
    private final double fusedLivenessScore;
    private final Map<String, Double> lastSignalScores;
    private final double framesPerSecond;
    private final int framesAnalyzed;
    private final String engineId;
    private final String engineVersion;
    private final String modelBundleId;
    private final String modelBundleVersion;
    private final String policyVersion;
    private final String deviceHealth;
    private final Map<String, String> extra;

    private DiagnosticSnapshot(Builder b) {
        capturedAt = Objects.requireNonNull(b.capturedAt, "capturedAt");
        sessionId = Objects.requireNonNullElse(b.sessionId, "");
        phase = Objects.requireNonNullElse(b.phase, "");
        fusedLivenessScore = b.fusedLivenessScore;
        lastSignalScores = Collections.unmodifiableMap(new TreeMap<>(b.lastSignalScores));
        framesPerSecond = b.framesPerSecond;
        framesAnalyzed = b.framesAnalyzed;
        engineId = Objects.requireNonNullElse(b.engineId, "unknown");
        engineVersion = Objects.requireNonNullElse(b.engineVersion, "unknown");
        modelBundleId = Objects.requireNonNullElse(b.modelBundleId, "none");
        modelBundleVersion = Objects.requireNonNullElse(b.modelBundleVersion, "none");
        policyVersion = Objects.requireNonNullElse(b.policyVersion, "unversioned");
        deviceHealth = Objects.requireNonNullElse(b.deviceHealth, "UNKNOWN");
        extra = Collections.unmodifiableMap(new LinkedHashMap<>(b.extra));
    }

    public static Builder builder() {
        return new Builder();
    }

    public Instant capturedAt() { return capturedAt; }
    public String sessionId() { return sessionId; }
    public String phase() { return phase; }
    public double fusedLivenessScore() { return fusedLivenessScore; }
    /** Last attack probability per signal id, sorted by id. */
    public Map<String, Double> lastSignalScores() { return lastSignalScores; }
    public double framesPerSecond() { return framesPerSecond; }
    public int framesAnalyzed() { return framesAnalyzed; }
    public String engineId() { return engineId; }
    public String engineVersion() { return engineVersion; }
    public String modelBundleId() { return modelBundleId; }
    public String modelBundleVersion() { return modelBundleVersion; }
    public String policyVersion() { return policyVersion; }
    public String deviceHealth() { return deviceHealth; }
    /** Free-form additional diagnostics (never biometric). */
    public Map<String, String> extra() { return extra; }

    /** Compact JSON for saving a diagnostic snapshot. */
    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("capturedAt", capturedAt.toString());
        m.put("sessionId", sessionId);
        m.put("phase", phase);
        m.put("fusedLivenessScore", fusedLivenessScore);
        m.put("lastSignalScores", lastSignalScores);
        m.put("framesPerSecond", framesPerSecond);
        m.put("framesAnalyzed", framesAnalyzed);
        m.put("engineId", engineId);
        m.put("engineVersion", engineVersion);
        m.put("modelBundleId", modelBundleId);
        m.put("modelBundleVersion", modelBundleVersion);
        m.put("policyVersion", policyVersion);
        m.put("deviceHealth", deviceHealth);
        m.put("extra", extra);
        return Json.write(m);
    }

    @Override
    public String toString() {
        return toJson();
    }

    /** Mutable builder. */
    public static final class Builder {
        private Instant capturedAt = Instant.now();
        private String sessionId;
        private String phase;
        private double fusedLivenessScore;
        private final Map<String, Double> lastSignalScores = new TreeMap<>();
        private double framesPerSecond;
        private int framesAnalyzed;
        private String engineId;
        private String engineVersion;
        private String modelBundleId;
        private String modelBundleVersion;
        private String policyVersion;
        private String deviceHealth;
        private final Map<String, String> extra = new LinkedHashMap<>();

        private Builder() {}

        public Builder capturedAt(Instant v) { capturedAt = v; return this; }
        public Builder sessionId(String v) { sessionId = v; return this; }
        public Builder phase(String v) { phase = v; return this; }
        public Builder fusedLivenessScore(double v) { fusedLivenessScore = v; return this; }
        public Builder signalScore(String id, double attackProbability) { lastSignalScores.put(Objects.requireNonNull(id), attackProbability); return this; }
        public Builder signalScores(Map<String, Double> v) { if (v != null) lastSignalScores.putAll(v); return this; }
        public Builder framesPerSecond(double v) { framesPerSecond = v; return this; }
        public Builder framesAnalyzed(int v) { framesAnalyzed = v; return this; }
        public Builder engineId(String v) { engineId = v; return this; }
        public Builder engineVersion(String v) { engineVersion = v; return this; }
        public Builder modelBundleId(String v) { modelBundleId = v; return this; }
        public Builder modelBundleVersion(String v) { modelBundleVersion = v; return this; }
        public Builder policyVersion(String v) { policyVersion = v; return this; }
        public Builder deviceHealth(String v) { deviceHealth = v; return this; }
        public Builder extra(String key, String value) { extra.put(Objects.requireNonNull(key), value == null ? "" : value); return this; }
        public DiagnosticSnapshot build() { return new DiagnosticSnapshot(this); }
    }
}
