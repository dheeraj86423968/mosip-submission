package io.mosip.vivid.telemetry.audit;

/**
 * Every audit event emitted by the liveness subsystem (PLAN.md section 10.1), to be recorded under the
 * Registration Client audit component {@code LIVENESS}. Attribute names listed per event are the keys the
 * orchestrator puts in {@link AuditRecord#attributes()}.
 */
public enum LivenessAuditEvent {

    /** A session (or a retry attempt) began. Attributes: attempt, retry. */
    SESSION_STARTED(Severity.INFO, "Liveness session started"),
    /** Passive fusion reached the workflow threshold. Attributes: fusedScore, frames, passiveMs. */
    PASSIVE_PASSED(Severity.INFO, "Passive liveness score above threshold"),
    /** Passive fusion fell short; active challenges follow if the policy allows. Attributes: fusedScore, threshold. */
    PASSIVE_BELOW_THRESHOLD(Severity.INFO, "Passive liveness score below threshold"),
    /** An active challenge was shown to the user. Attributes: type, index, total, nonce. */
    CHALLENGE_ISSUED(Severity.INFO, "Active challenge issued"),
    /** The requested action was verified. Attributes: type, index, latencyMs, durationMs. */
    CHALLENGE_PASSED(Severity.INFO, "Active challenge passed"),
    /** The user did not complete the action in time. Attributes: type, index, timeoutMs. */
    CHALLENGE_TIMEOUT(Severity.WARN, "Active challenge timed out"),
    /** A different distinctive action was completed before the requested one. Attributes: type, index, unprompted. */
    UNPROMPTED_ACTION(Severity.SECURITY, "Unprompted action during challenge"),
    /** A presentation attack was detected. Attributes: attackClass, fusedScore, vetoSignal. */
    PAD_DETECTED(Severity.SECURITY, "Presentation attack detected"),
    /** The face identity drifted from the session anchor. Attributes: distance, threshold. */
    IDENTITY_DRIFT(Severity.SECURITY, "Identity continuity violated"),
    /** The session concluded with a live decision. Attributes: fusedScore, per-signal scores, challenges. */
    SESSION_PASSED(Severity.INFO, "Liveness session passed"),
    /** The attempt failed. Attributes: reason, attempt, decision. */
    SESSION_FAILED(Severity.WARN, "Liveness session failed"),
    /** No more attempts allowed. Attributes: action (the policy's onMaxRetries). */
    MAX_RETRIES_REACHED(Severity.WARN, "Maximum liveness attempts reached"),
    /** A supervisor attested an assisted capture. Attributes: supervisorId, reason. */
    SUPERVISOR_OVERRIDE(Severity.SECURITY, "Supervisor override applied"),
    /** The frame source failed or degraded. Attributes: kind, detail. */
    DEVICE_FAULT(Severity.ERROR, "Capture device fault"),
    /** A policy document was put in force. Attributes: version, signatureOk, source, fallback. */
    POLICY_LOADED(Severity.INFO, "Liveness policy loaded"),
    /** A model bundle was verified and loaded. Attributes: bundleId, version. */
    MODEL_BUNDLE_LOADED(Severity.INFO, "Model bundle loaded"),
    /** A model bundle failed verification or self-test. Attributes: reason. */
    MODEL_BUNDLE_REJECTED(Severity.SECURITY, "Model bundle rejected"),
    /**
     * Differentially private calibration histograms of one workflow were released for the central calibration job
     * (design 14-dp-calibration.md). Attributes: releaseId, client, centre, mechanism, epsilon, rho, delta,
     * sensitivity, binning, threshold, policyVersion and the noisy counts {@code bf.*}, {@code rej.*}.
     */
    CALIBRATION_RELEASED(Severity.INFO, "Calibration histograms released"),
    /** A calibration release was refused because the client's privacy budget is spent. Attributes: spentEpsilon, budgetEpsilon. */
    CALIBRATION_BUDGET_EXHAUSTED(Severity.WARN, "Calibration privacy budget exhausted");

    /** How an audit consumer should treat the event. */
    public enum Severity {
        /** Routine lifecycle information. */
        INFO,
        /** Operational problem that does not by itself indicate an attack. */
        WARN,
        /** Operational failure of a component. */
        ERROR,
        /** Possible attack or integrity violation; must never be dropped. */
        SECURITY
    }

    private final Severity severity;
    private final String description;

    LivenessAuditEvent(Severity severity, String description) {
        this.severity = severity;
        this.description = description;
    }

    /** Severity of the event. */
    public Severity severity() {
        return severity;
    }

    /** Short human-readable description. */
    public String description() {
        return description;
    }
}
