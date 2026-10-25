package io.mosip.vivid.policy;

import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.Workflow;

import java.util.Objects;

/**
 * Outcome of {@link PolicyLoader#loadVerified}: the document that is actually in force (which may be the
 * compiled-in defaults) together with the report explaining why.
 */
public final class LoadedPolicy {

    private final PolicyDocument document;
    private final PolicyLoadReport report;
    private final PolicyLoader loader;

    LoadedPolicy(PolicyDocument document, PolicyLoadReport report, PolicyLoader loader) {
        this.document = Objects.requireNonNull(document);
        this.report = Objects.requireNonNull(report);
        this.loader = Objects.requireNonNull(loader);
    }

    /** The document in force. */
    public PolicyDocument document() {
        return document;
    }

    /** Why this document is in force. */
    public PolicyLoadReport report() {
        return report;
    }

    /** The effective policy for one workflow, merged over compiled-in defaults. */
    public LivenessPolicy effective(Workflow workflow) {
        return loader.toLivenessPolicy(document, workflow);
    }
}
