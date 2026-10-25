package io.mosip.vivid.policy;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.EngineInfo;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.api.Workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Checks a {@link PolicyDocument} for semantic problems that JSON parsing cannot catch and returns them as
 * human-readable violations. An empty list means the document is safe to put in force.
 *
 * <p>When constructed with an {@link EngineInfo} the validator additionally verifies that the challenge pools
 * only reference challenges the engine can detect and that weighted signals exist.</p>
 *
 * <p>Instances are immutable and thread-safe.</p>
 */
public final class PolicyValidator {

    /** Signal ids computed by the orchestrator rather than the engine; always legal in a policy. */
    private static final Set<String> ORCHESTRATOR_SIGNALS = Set.of(Signals.CHALLENGE_TIMING, Signals.IDENTITY);

    /** Every signal id defined in {@link Signals}. */
    private static final Set<String> KNOWN_SIGNALS = Set.of(Signals.CNN_TEXTURE, Signals.MOIRE, Signals.COLOR_STATS,
            Signals.RPPG, Signals.PARALLAX, Signals.CHALLENGE_TIMING, Signals.IDENTITY, Signals.DEPTH, Signals.IR,
            Signals.QUALITY, Signals.INJECTION);

    private final EngineInfo engine;

    /** Validator that does not check engine support. */
    public PolicyValidator() {
        this(null);
    }

    /** Validator that also checks challenge and signal support against the given engine; {@code null} disables that. */
    public PolicyValidator(EngineInfo engine) {
        this.engine = engine;
    }

    /** Returns every violation found; empty when the document is acceptable. */
    public List<String> validate(PolicyDocument doc) {
        Objects.requireNonNull(doc, "document");
        List<String> out = new ArrayList<>();
        validateSchemaVersion(doc, out);
        for (Map.Entry<Workflow, WorkflowPolicy> e : doc.workflows().entrySet()) {
            if (e.getValue() == null) {
                out.add(e.getKey() + ": workflow block is null");
                continue;
            }
            validateWorkflow(e.getKey(), e.getValue(), out);
        }
        if (doc.device() != null) {
            validateDevice(doc.device(), out);
        }
        return List.copyOf(out);
    }

    private static void validateSchemaVersion(PolicyDocument doc, List<String> out) {
        String v = doc.schemaVersion();
        if (v == null || Compat.isBlank(v)) {
            out.add("schemaVersion is required");
            return;
        }
        String major = v.split("\\.")[0];
        String supportedMajor = PolicyDocument.CURRENT_SCHEMA_VERSION.split("\\.")[0];
        if (!major.equals(supportedMajor)) {
            out.add("schemaVersion " + v + " is not supported (expected major version " + supportedMajor + ")");
        }
    }

    private void validateWorkflow(Workflow wf, WorkflowPolicy w, List<String> out) {
        String p = wf + ".";
        unitInterval(p + "passiveThreshold", w.passiveThreshold(), out);
        unitInterval(p + "padHardThreshold", w.padHardThreshold(), out);
        unitInterval(p + "minFaceQuality", w.minFaceQuality(), out);
        positive(p + "passiveWindowMs", w.passiveWindowMs(), out);
        positive(p + "sessionTimeoutMs", w.sessionTimeoutMs(), out);
        if (w.passiveMinFrames() != null && w.passiveMinFrames() < 1) {
            out.add(p + "passiveMinFrames must be at least 1");
        }
        if (w.maxRetries() != null && w.maxRetries() < 1) {
            out.add(p + "maxRetries must be at least 1");
        }
        if (w.identity() != null && w.identity().driftThreshold() != null) {
            double d = w.identity().driftThreshold();
            if (d <= 0 || d > 2) {
                out.add(p + "identity.driftThreshold must be in (0,2]");
            }
        }
        if (w.signals() != null) {
            validateSignals(p, w.signals(), out);
        }
        // Merge over defaults so cross-field rules see the effective values, not just the overrides.
        LivenessPolicy effective = null;
        try {
            effective = new PolicyLoader(this).toLivenessPolicy(
                    new PolicyDocument(PolicyDocument.CURRENT_SCHEMA_VERSION, null, Map.of(wf, w), null, null), wf);
        } catch (PolicyException e) {
            out.add(p + e.getMessage());
        }
        validateActive(p + "active.", w.active() == null ? ActivePolicy.empty() : w.active(), effective, out);
    }

    private void validateActive(String p, ActivePolicy a, LivenessPolicy effective, List<String> out) {
        if (a.minChallenges() != null && a.minChallenges() < 0) {
            out.add(p + "minChallenges must be >= 0");
        }
        if (a.maxChallenges() != null && a.maxChallenges() < 0) {
            out.add(p + "maxChallenges must be >= 0");
        }
        positive(p + "timeoutMs", a.timeoutMs(), out);
        if (a.minActionLatencyMs() != null && a.minActionLatencyMs() < 0) {
            out.add(p + "minActionLatencyMs must be >= 0");
        }
        positive(p + "maxActionLatencyMs", a.maxActionLatencyMs(), out);
        if (effective == null) {
            return;
        }
        if (effective.minChallenges() > effective.maxChallenges()
                || (a.minChallenges() != null && a.maxChallenges() != null && a.minChallenges() > a.maxChallenges())) {
            out.add(p + "minChallenges must be <= maxChallenges");
        }
        boolean activeEnabled = effective.activeMode() != LivenessPolicy.ActiveMode.NEVER && effective.minChallenges() > 0;
        if (activeEnabled && effective.challengePool().isEmpty()) {
            out.add(p + "pool must not be empty when active challenges are enabled");
        }
        if (effective.minActionLatencyMillis() >= effective.maxActionLatencyMillis()) {
            out.add(p + "minActionLatencyMs must be < maxActionLatencyMs");
        }
        if (effective.maxActionLatencyMillis() > effective.challengeTimeoutMillis()) {
            out.add(p + "maxActionLatencyMs should not exceed timeoutMs");
        }
        if (activeEnabled && effective.requireDepthRevealingChallenge()
                && effective.challengePool().stream().noneMatch(ChallengeType::revealsDepth)) {
            out.add(p + "requireDepthRevealing is set but the pool has no depth-revealing challenge");
        }
        if (engine != null) {
            for (ChallengeType t : effective.challengePool()) {
                if (!engine.supportedChallenges().contains(t)) {
                    out.add(p + "pool contains " + t + " which engine " + engine.engineId() + " does not support");
                }
            }
            for (ChallengeType t : effective.accessibilityPool()) {
                if (!engine.supportedChallenges().contains(t)) {
                    out.add(p + "accessibilityPool contains " + t + " which engine " + engine.engineId() + " does not support");
                }
            }
        }
    }

    private void validateSignals(String p, Map<String, SignalPolicy> signals, List<String> out) {
        for (Map.Entry<String, SignalPolicy> e : signals.entrySet()) {
            String id = e.getKey();
            SignalPolicy s = e.getValue();
            if (id == null || Compat.isBlank(id)) {
                out.add(p + "signals: blank signal id");
                continue;
            }
            if (!KNOWN_SIGNALS.contains(id)) {
                out.add(p + "signals." + id + " is not a known signal id");
            } else if (engine != null && !ORCHESTRATOR_SIGNALS.contains(id) && !engine.signalIds().contains(id)) {
                out.add(p + "signals." + id + " is not produced by engine " + engine.engineId());
            }
            if (s != null && s.weight() != null && (s.weight() < 0 || Double.isNaN(s.weight()) || s.weight() > 10)) {
                out.add(p + "signals." + id + ".weight must be in [0,10]");
            }
        }
    }

    private static void validateDevice(DevicePolicy d, List<String> out) {
        if (d.minFps() != null && d.minFps() <= 0) {
            out.add("device.minFps must be > 0");
        }
        if (d.reconnectAttempts() != null && d.reconnectAttempts() < 0) {
            out.add("device.reconnectAttempts must be >= 0");
        }
        positive("device.stallTimeoutMs", d.stallTimeoutMs(), out);
    }

    private static void unitInterval(String name, Double v, List<String> out) {
        if (v != null && (Double.isNaN(v) || v < 0 || v > 1)) {
            out.add(name + " must be in [0,1]");
        }
    }

    private static void positive(String name, Long v, List<String> out) {
        if (v != null && v <= 0) {
            out.add(name + " must be > 0");
        }
    }

    /** The engine this validator checks against, if any. */
    public Optional<EngineInfo> engine() {
        return Optional.ofNullable(engine);
    }
}
