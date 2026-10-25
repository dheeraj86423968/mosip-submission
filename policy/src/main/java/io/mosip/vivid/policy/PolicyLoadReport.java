package io.mosip.vivid.policy;

import java.util.List;
import java.util.Objects;

/**
 * Immutable account of how a policy was loaded, intended to be audited as {@code POLICY_LOADED}.
 *
 * @param source          where the document came from (file name, "global-params", "builtin")
 * @param version         version label of the document in force
 * @param signatureStatus result of signature verification
 * @param violations      validation or verification problems found in the delivered document
 * @param usedFallback    {@code true} when the delivered document was rejected and defaults are in force
 */
public final class PolicyLoadReport {
    private final String source;
    private final String version;
    private final SignatureStatus signatureStatus;
    private final List<String> violations;
    private final boolean usedFallback;

    /** Canonical constructor; copies the violation list. */
    public PolicyLoadReport(String source, String version, SignatureStatus signatureStatus, List<String> violations, boolean usedFallback) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(signatureStatus, "signatureStatus");
        version = version == null ? "unversioned" : version;
        violations = violations == null ? List.of() : List.copyOf(violations);
        this.source = source;
        this.version = version;
        this.signatureStatus = signatureStatus;
        this.violations = violations;
        this.usedFallback = usedFallback;
    }

    public String source() { return source; }
    public String version() { return version; }
    public SignatureStatus signatureStatus() { return signatureStatus; }
    public List<String> violations() { return violations; }
    public boolean usedFallback() { return usedFallback; }

    /** Result of detached-signature verification. */
    public enum SignatureStatus {
        /** Signature present and verified against the configured key. */
        VALID,
        /** Signature present but did not verify, or the document was altered. */
        INVALID,
        /** No signature delivered. */
        UNSIGNED
    }

    /** True when the delivered document was accepted as-is. */
    public boolean accepted() {
        return !usedFallback;
    }

    /** Convenience flag for the {@code POLICY_LOADED(version, signatureOk)} audit attribute. */
    public boolean signatureOk() {
        return signatureStatus == SignatureStatus.VALID;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PolicyLoadReport)) return false;
        PolicyLoadReport other = (PolicyLoadReport) o;
        return java.util.Objects.equals(source, other.source)
                && java.util.Objects.equals(version, other.version)
                && java.util.Objects.equals(signatureStatus, other.signatureStatus)
                && java.util.Objects.equals(violations, other.violations)
                && usedFallback == other.usedFallback;
    }

    @Override
    public int hashCode() {
        int h = 0;
        h = 31 * h + java.util.Objects.hashCode(source);
        h = 31 * h + java.util.Objects.hashCode(version);
        h = 31 * h + java.util.Objects.hashCode(signatureStatus);
        h = 31 * h + java.util.Objects.hashCode(violations);
        h = 31 * h + Boolean.hashCode(usedFallback);
        return h;
    }

    @Override
    public String toString() {
        return "PolicyLoadReport[source=" + source + ", version=" + version + ", signatureStatus=" + signatureStatus + ", violations=" + violations + ", usedFallback=" + usedFallback + "]";
    }
}
