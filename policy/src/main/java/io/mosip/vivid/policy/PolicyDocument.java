package io.mosip.vivid.policy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.mosip.vivid.api.Workflow;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Root of the liveness policy document (PLAN.md section 5.1), mapped one-to-one onto JSON by Jackson.
 *
 * <p>Every field other than {@code schemaVersion} is optional so that a partial document can be merged over the
 * compiled-in defaults by {@link PolicyLoader}. Unknown JSON properties are rejected at parse time so that a typo
 * in a configuration key cannot silently disable a safeguard.</p>
 *
 * <p>Instances are immutable; the maps they expose are unmodifiable copies.</p>
 *
 * @param schemaVersion version of the document schema, e.g. {@code "1.0"}; required
 * @param version       free-form version label of this policy instance (surfaced as {@code LivenessPolicy.policyVersion})
 * @param workflows     per-workflow overrides keyed by {@link Workflow}
 * @param device        device-level settings shared by all workflows
 * @param diagnostics   diagnostic-mode settings
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class PolicyDocument {
    private final String schemaVersion;
    private final String version;
    private final Map<Workflow, WorkflowPolicy> workflows;
    private final DevicePolicy device;
    private final DiagnosticsPolicy diagnostics;

    /** Canonical constructor; defensively copies the workflow map. */
    @JsonCreator
    public PolicyDocument(@JsonProperty("schemaVersion") String schemaVersion,
                          @JsonProperty("version") String version,
                          @JsonProperty("workflows") Map<Workflow, WorkflowPolicy> workflows,
                          @JsonProperty("device") DevicePolicy device,
                          @JsonProperty("diagnostics") DiagnosticsPolicy diagnostics) {
        if (workflows == null || workflows.isEmpty()) {
            workflows = Collections.emptyMap();
        } else {
            Map<Workflow, WorkflowPolicy> copy = new EnumMap<>(Workflow.class);
            copy.putAll(workflows);
            workflows = Collections.unmodifiableMap(copy);
        }
        this.schemaVersion = schemaVersion;
        this.version = version;
        this.workflows = workflows;
        this.device = device;
        this.diagnostics = diagnostics;
    }

    @JsonProperty public String schemaVersion() { return schemaVersion; }
    @JsonProperty public String version() { return version; }
    @JsonProperty public Map<Workflow, WorkflowPolicy> workflows() { return workflows; }
    @JsonProperty public DevicePolicy device() { return device; }
    @JsonProperty public DiagnosticsPolicy diagnostics() { return diagnostics; }

    /** Schema version produced by this code base and accepted by {@link PolicyValidator}. */
    public static final String CURRENT_SCHEMA_VERSION = "1.0";

    /** The override block for one workflow, if the document defines one. */
    public Optional<WorkflowPolicy> workflow(Workflow workflow) {
        return Optional.ofNullable(workflows.get(workflow));
    }

    /** Returns a copy of this document with the given version label. */
    public PolicyDocument withVersion(String newVersion) {
        return new PolicyDocument(schemaVersion, newVersion, workflows, device, diagnostics);
    }

    /** Returns a copy of this document with one workflow block replaced. */
    public PolicyDocument withWorkflow(Workflow workflow, WorkflowPolicy policy) {
        Map<Workflow, WorkflowPolicy> copy = new EnumMap<>(Workflow.class);
        copy.putAll(workflows);
        copy.put(workflow, policy);
        return new PolicyDocument(schemaVersion, version, copy, device, diagnostics);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PolicyDocument)) return false;
        PolicyDocument other = (PolicyDocument) o;
        return java.util.Objects.equals(schemaVersion, other.schemaVersion)
                && java.util.Objects.equals(version, other.version)
                && java.util.Objects.equals(workflows, other.workflows)
                && java.util.Objects.equals(device, other.device)
                && java.util.Objects.equals(diagnostics, other.diagnostics);
    }

    @Override
    public int hashCode() {
        int h = 0;
        h = 31 * h + java.util.Objects.hashCode(schemaVersion);
        h = 31 * h + java.util.Objects.hashCode(version);
        h = 31 * h + java.util.Objects.hashCode(workflows);
        h = 31 * h + java.util.Objects.hashCode(device);
        h = 31 * h + java.util.Objects.hashCode(diagnostics);
        return h;
    }

    @Override
    public String toString() {
        return "PolicyDocument[schemaVersion=" + schemaVersion + ", version=" + version + ", workflows=" + workflows + ", device=" + device + ", diagnostics=" + diagnostics + "]";
    }
}
