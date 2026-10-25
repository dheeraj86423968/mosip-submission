package io.mosip.vivid.policy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Diagnostic-mode settings (PLAN.md section 10.2).
 *
 * @param mode who may open the diagnostic screen
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class DiagnosticsPolicy {
    private final Mode mode;

    @JsonCreator
    public DiagnosticsPolicy(@JsonProperty("mode") Mode mode) {
        this.mode = mode;
    }

    @JsonProperty public Mode mode() { return mode; }

    /** Availability of the live diagnostic screen. */
    public enum Mode {
        /** Diagnostic screen disabled. */
        OFF,
        /** Only a logged-in supervisor may open the diagnostic screen. */
        SUPERVISOR_ONLY,
        /** Any operator may open the diagnostic screen (lab and pilot use). */
        ON
    }

    /** Compiled-in default: diagnostics off. */
    public static DiagnosticsPolicy defaults() {
        return new DiagnosticsPolicy(Mode.OFF);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DiagnosticsPolicy)) return false;
        DiagnosticsPolicy other = (DiagnosticsPolicy) o;
        return java.util.Objects.equals(mode, other.mode);
    }

    @Override
    public int hashCode() {
        int h = 0;
        h = 31 * h + java.util.Objects.hashCode(mode);
        return h;
    }

    @Override
    public String toString() {
        return "DiagnosticsPolicy[mode=" + mode + "]";
    }
}
