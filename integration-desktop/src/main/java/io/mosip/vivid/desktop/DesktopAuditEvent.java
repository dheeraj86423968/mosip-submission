package io.mosip.vivid.desktop;

/**
 * Audit events emitted by the desktop integration (PLAN section 10.1). Ids use their own {@code REG-LIV-} prefix so
 * they cannot collide with upstream additions to {@code AuditEvent}'s {@code REG-EVT-} range. The MOSIP patch adds
 * one {@code AuditEvent} constant per entry with exactly these ids and names.
 */
public enum DesktopAuditEvent {
    SESSION_STARTED("REG-LIV-001", "Liveness session started"),
    PASSIVE_PASSED("REG-LIV-002", "Passive liveness passed"),
    PASSIVE_BELOW_THRESHOLD("REG-LIV-003", "Passive liveness below threshold, active challenge issued"),
    CHALLENGE_ISSUED("REG-LIV-004", "Active challenge issued"),
    CHALLENGE_PASSED("REG-LIV-005", "Active challenge passed"),
    CHALLENGE_TIMEOUT("REG-LIV-006", "Active challenge timed out"),
    PAD_DETECTED("REG-LIV-007", "Presentation attack detected"),
    IDENTITY_DRIFT("REG-LIV-008", "Identity continuity failed"),
    SESSION_PASSED("REG-LIV-009", "Liveness session passed"),
    SESSION_FAILED("REG-LIV-010", "Liveness session failed"),
    MAX_RETRIES_REACHED("REG-LIV-011", "Liveness max retries reached"),
    SUPERVISOR_OVERRIDE("REG-LIV-012", "Supervisor override of liveness lock"),
    DEVICE_FAULT("REG-LIV-013", "Face device fault during liveness"),
    POLICY_LOADED("REG-LIV-014", "Liveness policy loaded"),
    MODEL_BUNDLE_LOADED("REG-LIV-015", "Liveness model bundle loaded"),
    MODEL_BUNDLE_REJECTED("REG-LIV-016", "Liveness model bundle rejected"),
    POLICY_DISABLED("REG-LIV-017", "Liveness disabled by policy, capture allowed without liveness"),
    CAPTURE_BOUND("REG-LIV-018", "Signed capture bound to liveness session");

    private final String id;
    private final String description;

    DesktopAuditEvent(String id, String description) { this.id = id; this.description = description; }

    /** Proposed {@code AuditEvent} id in the Registration Client. */
    public String id() { return id; }
    public String description() { return description; }
    /** Proposed {@code Components} id for all liveness events. */
    public static final String COMPONENT_ID = "REG-MOD-153";
    public static final String COMPONENT_NAME = "LIVENESS";
}
