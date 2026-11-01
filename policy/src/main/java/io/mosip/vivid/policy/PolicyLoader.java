package io.mosip.vivid.policy;

import com.fasterxml.jackson.databind.JsonMappingException;
import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.EngineInfo;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.policy.signing.PolicyVerifier;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Parses policy documents and converts them into the effective {@link LivenessPolicy} per workflow.
 *
 * <p>Conversion merges the document over {@link LivenessPolicy#defaults(Workflow)}: every field the document
 * leaves {@code null} keeps its compiled-in value, so a two-line document that only tightens a threshold is valid.</p>
 *
 * <p>{@link #loadVerified} implements the trust rule from PLAN.md section 5.2: a document that fails signature
 * verification or validation is discarded and the compiled-in defaults are used instead, with a
 * {@link PolicyLoadReport} that the caller must audit as {@code POLICY_LOADED(version, signatureOk)}.</p>
 *
 * <p>Instances are immutable and thread-safe.</p>
 */
public final class PolicyLoader {

    /** Classpath location of the compiled-in default document. */
    public static final String DEFAULT_RESOURCE = "/policy-default.json";

    private final PolicyValidator validator;

    /** Loader validating against no particular engine. */
    public PolicyLoader() {
        this(new PolicyValidator());
    }

    /** Loader using the supplied validator (for example one bound to an {@link EngineInfo}). */
    public PolicyLoader(PolicyValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    /** Parses a JSON string. */
    public PolicyDocument parse(String json) {
        Objects.requireNonNull(json, "json");
        try {
            PolicyDocument doc = PolicyJson.mapper().readValue(json, PolicyDocument.class);
            if (doc == null) {
                throw new PolicyException("policy document is empty");
            }
            return doc;
        } catch (JsonMappingException e) {
            throw new PolicyException("invalid policy document: " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new PolicyException("unreadable policy document: " + e.getMessage(), e);
        }
    }

    /** Parses a UTF-8 JSON stream. The stream is fully read but not closed. */
    public PolicyDocument parse(InputStream in) {
        Objects.requireNonNull(in, "in");
        try {
            return parse(new String(Compat.readAllBytes(in), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new PolicyException("cannot read policy stream", e);
        }
    }

    /** Parses a UTF-8 JSON file. */
    public PolicyDocument parse(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            return parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new PolicyException("cannot read policy file " + path.getFileName(), e);
        }
    }

    /** The compiled-in default document shipped as {@value #DEFAULT_RESOURCE}. */
    public static PolicyDocument builtinDefaults() {
        try (InputStream in = PolicyLoader.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + DEFAULT_RESOURCE);
            }
            return new PolicyLoader().parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The raw JSON text of the compiled-in default document. */
    public static String builtinDefaultsJson() {
        try (InputStream in = PolicyLoader.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + DEFAULT_RESOURCE);
            }
            return new String(Compat.readAllBytes(in), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Converts one workflow block of a document into the effective policy, merging over the compiled-in defaults.
     *
     * @throws PolicyException if the merged policy is internally inconsistent
     */
    public LivenessPolicy toLivenessPolicy(PolicyDocument document, Workflow workflow) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(workflow, "workflow");
        LivenessPolicy base = LivenessPolicy.defaults(workflow);
        LivenessPolicy.Builder b = copy(base);
        if (document.version() != null) {
            b.policyVersion(document.version());
        }
        document.workflow(workflow).ifPresent(w -> applyWorkflow(b, w));
        try {
            return b.build();
        } catch (IllegalArgumentException e) {
            throw new PolicyException("inconsistent policy for " + workflow + ": " + e.getMessage(), e);
        }
    }

    /**
     * Parses, verifies and validates a document, falling back to compiled-in defaults when anything is wrong.
     *
     * <p>Trust rule: when a {@code publicKey} is configured the document must carry a valid signature;
     * an unsigned document is then rejected. When no key is configured the document is accepted as
     * {@link PolicyLoadReport.SignatureStatus#UNSIGNED} and trust is delegated to the transport that delivered it.</p>
     *
     * @param json            document text, may be {@code null} to mean "no document delivered"
     * @param signatureBase64 detached signature, may be {@code null}
     * @param publicKey       verification key, may be {@code null} if signatures are not enforced
     * @param source          human-readable origin for the report, e.g. a file name or "global-params"
     */
    public LoadedPolicy loadVerified(String json, String signatureBase64, PublicKey publicKey, String source) {
        String src = Objects.requireNonNullElse(source, "unknown");
        if (json == null || Compat.isBlank(json)) {
            return fallback(src, PolicyLoadReport.SignatureStatus.UNSIGNED, List.of("no policy document delivered"));
        }
        PolicyLoadReport.SignatureStatus status;
        if (signatureBase64 == null || Compat.isBlank(signatureBase64)) {
            status = PolicyLoadReport.SignatureStatus.UNSIGNED;
            if (publicKey != null) {
                return fallback(src, status, List.of("policy is unsigned but a verification key is configured"));
            }
        } else if (publicKey == null) {
            status = PolicyLoadReport.SignatureStatus.UNSIGNED;
        } else {
            status = PolicyVerifier.verify(json, signatureBase64, publicKey)
                    ? PolicyLoadReport.SignatureStatus.VALID
                    : PolicyLoadReport.SignatureStatus.INVALID;
            if (status == PolicyLoadReport.SignatureStatus.INVALID) {
                return fallback(src, status, List.of("policy signature does not verify"));
            }
        }
        PolicyDocument doc;
        try {
            doc = parse(json);
        } catch (PolicyException e) {
            return fallback(src, status, List.of(e.getMessage()));
        }
        List<String> violations = validator.validate(doc);
        if (!violations.isEmpty()) {
            return fallback(src, status, violations);
        }
        return new LoadedPolicy(doc, new PolicyLoadReport(src, doc.version(), status, List.of(), false), this);
    }

    private LoadedPolicy fallback(String source, PolicyLoadReport.SignatureStatus status, List<String> violations) {
        PolicyDocument defaults = builtinDefaults();
        return new LoadedPolicy(defaults, new PolicyLoadReport(source, defaults.version(), status, violations, true), this);
    }

    private static void applyWorkflow(LivenessPolicy.Builder b, WorkflowPolicy w) {
        if (w.enabled() != null) b.enabled(w.enabled());
        if (w.passiveThreshold() != null) b.passiveThreshold(w.passiveThreshold());
        if (w.padHardThreshold() != null) b.padHardThreshold(w.padHardThreshold());
        if (w.passiveWindowMs() != null) b.passiveWindowMillis(w.passiveWindowMs());
        if (w.passiveMinFrames() != null) b.passiveMinFrames(w.passiveMinFrames());
        if (w.maxRetries() != null) b.maxRetries(w.maxRetries());
        if (w.onMaxRetries() != null) b.onMaxRetries(w.onMaxRetries());
        if (w.sessionTimeoutMs() != null) b.sessionTimeoutMillis(w.sessionTimeoutMs());
        if (w.minFaceQuality() != null) b.minFaceQuality(w.minFaceQuality());
        if (w.identity() != null) {
            if (w.identity().required() != null) b.requireIdentityContinuity(w.identity().required());
            if (w.identity().driftThreshold() != null) b.identityDriftThreshold(w.identity().driftThreshold());
        }
        if (w.active() != null) {
            applyActive(b, w.active());
        }
        if (w.signals() != null) {
            for (Map.Entry<String, SignalPolicy> e : w.signals().entrySet()) {
                b.signalWeight(e.getKey(), e.getValue() == null ? 1.0 : e.getValue().effectiveWeight());
            }
        }
    }

    private static void applyActive(LivenessPolicy.Builder b, ActivePolicy a) {
        if (a.mode() != null) b.activeMode(a.mode());
        if (a.minChallenges() != null) b.minChallenges(a.minChallenges());
        if (a.maxChallenges() != null) b.maxChallenges(a.maxChallenges());
        if (a.pool() != null) b.challengePool(toSet(a.pool()));
        if (a.accessibilityPool() != null) b.accessibilityPool(toSet(a.accessibilityPool()));
        if (a.requireDepthRevealing() != null) b.requireDepthRevealingChallenge(a.requireDepthRevealing());
        if (a.timeoutMs() != null) b.challengeTimeoutMillis(a.timeoutMs());
        if (a.minActionLatencyMs() != null) b.minActionLatencyMillis(a.minActionLatencyMs());
        if (a.maxActionLatencyMs() != null) b.maxActionLatencyMillis(a.maxActionLatencyMs());
    }

    private static Set<ChallengeType> toSet(List<ChallengeType> list) {
        return list.isEmpty() ? EnumSet.noneOf(ChallengeType.class) : EnumSet.copyOf(list);
    }

    /** Copies every field of a policy into a fresh builder (the API offers no {@code toBuilder}). */
    static LivenessPolicy.Builder copy(LivenessPolicy p) {
        return LivenessPolicy.builder(p.workflow())
                .policyVersion(p.policyVersion())
                .enabled(p.enabled())
                .passiveThreshold(p.passiveThreshold())
                .padHardThreshold(p.padHardThreshold())
                .passiveWindowMillis(p.passiveWindowMillis())
                .passiveMinFrames(p.passiveMinFrames())
                .activeMode(p.activeMode())
                .minChallenges(p.minChallenges())
                .maxChallenges(p.maxChallenges())
                .challengePool(p.challengePool().isEmpty() ? EnumSet.noneOf(ChallengeType.class) : EnumSet.copyOf(p.challengePool()))
                .accessibilityPool(p.accessibilityPool().isEmpty() ? EnumSet.noneOf(ChallengeType.class) : EnumSet.copyOf(p.accessibilityPool()))
                .requireDepthRevealingChallenge(p.requireDepthRevealingChallenge())
                .challengeTimeoutMillis(p.challengeTimeoutMillis())
                .minActionLatencyMillis(p.minActionLatencyMillis())
                .maxActionLatencyMillis(p.maxActionLatencyMillis())
                .maxRetries(p.maxRetries())
                .onMaxRetries(p.onMaxRetries())
                .sessionTimeoutMillis(p.sessionTimeoutMillis())
                .acquisitionTimeoutMillis(p.acquisitionTimeoutMillis())
                .minFaceQuality(p.minFaceQuality())
                .identityDriftThreshold(p.identityDriftThreshold())
                .signalWeights(p.signalWeights())
                .requireIdentityContinuity(p.requireIdentityContinuity());
    }
}
