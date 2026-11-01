package io.mosip.vivid.policy;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.policy.signing.PolicyKeys;
import io.mosip.vivid.policy.signing.PolicySigner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyLoaderTest {

    private final PolicyLoader loader = new PolicyLoader();

    @Test
    void builtinDefaultsMatchApiDefaultsForEveryWorkflow() {
        PolicyDocument doc = PolicyLoader.builtinDefaults();
        assertThat(new PolicyValidator().validate(doc)).isEmpty();
        for (Workflow wf : Workflow.values()) {
            LivenessPolicy fromDoc = loader.toLivenessPolicy(doc, wf);
            LivenessPolicy api = LivenessPolicy.defaults(wf);
            assertThat(fromDoc.passiveThreshold()).isEqualTo(api.passiveThreshold());
            assertThat(fromDoc.activeMode()).isEqualTo(api.activeMode());
            assertThat(fromDoc.minChallenges()).isEqualTo(api.minChallenges());
            assertThat(fromDoc.maxChallenges()).isEqualTo(api.maxChallenges());
            assertThat(fromDoc.maxRetries()).isEqualTo(api.maxRetries());
            assertThat(fromDoc.onMaxRetries()).isEqualTo(api.onMaxRetries());
            assertThat(fromDoc.challengePool()).isEqualTo(api.challengePool());
            assertThat(fromDoc.policyVersion()).isEqualTo("builtin-1.1");
        }
        assertThat(PolicyLoader.builtinDefaultsJson()).contains("\"schemaVersion\"");
    }

    @Test
    void partialDocumentMergesOverDefaults() {
        PolicyDocument doc = loader.parse("{\"schemaVersion\":\"1.0\",\"version\":\"centre-42\",\"workflows\":{\"RESIDENT\":{\"passiveThreshold\":0.95}}}");
        LivenessPolicy p = loader.toLivenessPolicy(doc, Workflow.RESIDENT);
        LivenessPolicy d = LivenessPolicy.defaults(Workflow.RESIDENT);
        assertThat(p.passiveThreshold()).isEqualTo(0.95);
        assertThat(p.policyVersion()).isEqualTo("centre-42");
        assertThat(p.activeMode()).isEqualTo(d.activeMode());
        assertThat(p.challengePool()).isEqualTo(d.challengePool());
        assertThat(p.accessibilityPool()).isEqualTo(d.accessibilityPool());
        assertThat(p.challengeTimeoutMillis()).isEqualTo(d.challengeTimeoutMillis());
        assertThat(p.identityDriftThreshold()).isEqualTo(d.identityDriftThreshold());
        // Workflows absent from the document get pure defaults, but the document version.
        LivenessPolicy op = loader.toLivenessPolicy(doc, Workflow.OPERATOR);
        assertThat(op.passiveThreshold()).isEqualTo(LivenessPolicy.defaults(Workflow.OPERATOR).passiveThreshold());
        assertThat(op.policyVersion()).isEqualTo("centre-42");
    }

    @Test
    void everyFieldIsApplied() {
        String json = """
            {"schemaVersion":"1.0","workflows":{"SUPERVISOR":{
              "enabled":false,"passiveThreshold":0.5,"padHardThreshold":0.7,"passiveWindowMs":1000,"passiveMinFrames":3,
              "active":{"mode":"NEVER","minChallenges":0,"maxChallenges":1,"pool":["BLINK"],"accessibilityPool":["HOLD_STILL"],
                        "requireDepthRevealing":false,"timeoutMs":5000,"minActionLatencyMs":100,"maxActionLatencyMs":4000},
              "maxRetries":9,"onMaxRetries":"FALLBACK_TO_EXCEPTION_FLOW","sessionTimeoutMs":30000,"minFaceQuality":0.6,
              "identity":{"required":false,"driftThreshold":0.3},
              "signals":{"moire":{"enabled":false,"weight":2.0},"rppg":{"weight":0.5},"cnn_texture":{}}}}}
            """;
        LivenessPolicy p = loader.toLivenessPolicy(loader.parse(json), Workflow.SUPERVISOR);
        assertThat(p.enabled()).isFalse();
        assertThat(p.passiveThreshold()).isEqualTo(0.5);
        assertThat(p.padHardThreshold()).isEqualTo(0.7);
        assertThat(p.passiveWindowMillis()).isEqualTo(1000);
        assertThat(p.passiveMinFrames()).isEqualTo(3);
        assertThat(p.activeMode()).isEqualTo(LivenessPolicy.ActiveMode.NEVER);
        assertThat(p.minChallenges()).isZero();
        assertThat(p.maxChallenges()).isEqualTo(1);
        assertThat(p.challengePool()).containsExactly(ChallengeType.BLINK);
        assertThat(p.accessibilityPool()).containsExactly(ChallengeType.HOLD_STILL);
        assertThat(p.requireDepthRevealingChallenge()).isFalse();
        assertThat(p.challengeTimeoutMillis()).isEqualTo(5000);
        assertThat(p.minActionLatencyMillis()).isEqualTo(100);
        assertThat(p.maxActionLatencyMillis()).isEqualTo(4000);
        assertThat(p.maxRetries()).isEqualTo(9);
        assertThat(p.onMaxRetries()).isEqualTo(LivenessPolicy.OnMaxRetries.FALLBACK_TO_EXCEPTION_FLOW);
        assertThat(p.sessionTimeoutMillis()).isEqualTo(30000);
        assertThat(p.minFaceQuality()).isEqualTo(0.6);
        assertThat(p.requireIdentityContinuity()).isFalse();
        assertThat(p.identityDriftThreshold()).isEqualTo(0.3);
        assertThat(p.signalWeight(Signals.MOIRE)).isEqualTo(0.0);
        assertThat(p.signalWeight(Signals.RPPG)).isEqualTo(0.5);
        assertThat(p.signalWeight(Signals.CNN_TEXTURE)).isEqualTo(1.0);
        assertThat(p.signalWeight(Signals.PARALLAX)).isEqualTo(1.0);
    }

    @Test
    void inconsistentMergeRaisesPolicyException() {
        PolicyDocument doc = loader.parse("{\"schemaVersion\":\"1.0\",\"workflows\":{\"RESIDENT\":{\"active\":{\"pool\":[]}}}}");
        assertThatThrownBy(() -> loader.toLivenessPolicy(doc, Workflow.RESIDENT))
                .isInstanceOf(PolicyException.class)
                .hasMessageContaining("RESIDENT");
    }

    @Test
    void parsesFromStreamAndPath(@TempDir Path dir) throws Exception {
        String json = "{\"schemaVersion\":\"1.0\",\"version\":\"file\"}";
        assertThat(loader.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))).version()).isEqualTo("file");
        Path f = dir.resolve("p.json");
        Files.writeString(f, json);
        assertThat(loader.parse(f).version()).isEqualTo("file");
        assertThatThrownBy(() -> loader.parse(dir.resolve("missing.json"))).isInstanceOf(PolicyException.class);
    }

    @Test
    void loadVerifiedAcceptsValidSignature() {
        KeyPair kp = PolicyKeys.generateEc();
        String json = "{\"schemaVersion\":\"1.0\",\"version\":\"signed-1\",\"workflows\":{\"RESIDENT\":{\"passiveThreshold\":0.9}}}";
        String sig = PolicySigner.sign(json, kp.getPrivate());
        LoadedPolicy loaded = loader.loadVerified(json, sig, kp.getPublic(), "global-params");
        assertThat(loaded.report().accepted()).isTrue();
        assertThat(loaded.report().signatureOk()).isTrue();
        assertThat(loaded.report().signatureStatus()).isEqualTo(PolicyLoadReport.SignatureStatus.VALID);
        assertThat(loaded.report().version()).isEqualTo("signed-1");
        assertThat(loaded.report().source()).isEqualTo("global-params");
        assertThat(loaded.effective(Workflow.RESIDENT).passiveThreshold()).isEqualTo(0.9);
    }

    @Test
    void loadVerifiedFallsBackOnTamperedDocument() {
        KeyPair kp = PolicyKeys.generateEc();
        String json = "{\"schemaVersion\":\"1.0\",\"version\":\"signed-1\",\"workflows\":{\"RESIDENT\":{\"enabled\":true}}}";
        String sig = PolicySigner.sign(json, kp.getPrivate());
        String tampered = json.replace("\"enabled\":true", "\"enabled\":false");
        LoadedPolicy loaded = loader.loadVerified(tampered, sig, kp.getPublic(), "global-params");
        assertThat(loaded.report().usedFallback()).isTrue();
        assertThat(loaded.report().signatureStatus()).isEqualTo(PolicyLoadReport.SignatureStatus.INVALID);
        assertThat(loaded.report().violations()).anyMatch(v -> v.contains("signature"));
        assertThat(loaded.report().version()).isEqualTo("builtin-1.1");
        assertThat(loaded.effective(Workflow.RESIDENT).enabled()).isTrue();
    }

    @Test
    void loadVerifiedRejectsUnsignedWhenKeyConfigured() {
        KeyPair kp = PolicyKeys.generateEc();
        LoadedPolicy loaded = loader.loadVerified("{\"schemaVersion\":\"1.0\"}", null, kp.getPublic(), "file");
        assertThat(loaded.report().usedFallback()).isTrue();
        assertThat(loaded.report().signatureStatus()).isEqualTo(PolicyLoadReport.SignatureStatus.UNSIGNED);
    }

    @Test
    void loadVerifiedAcceptsUnsignedWhenNoKeyConfigured() {
        LoadedPolicy loaded = loader.loadVerified("{\"schemaVersion\":\"1.0\",\"version\":\"plain\"}", null, null, "file");
        assertThat(loaded.report().accepted()).isTrue();
        assertThat(loaded.report().signatureStatus()).isEqualTo(PolicyLoadReport.SignatureStatus.UNSIGNED);
        assertThat(loaded.document().version()).isEqualTo("plain");
        // A signature without a key is ignored, not an error.
        LoadedPolicy loaded2 = loader.loadVerified("{\"schemaVersion\":\"1.0\",\"version\":\"plain\"}", "Zm9v", null, "file");
        assertThat(loaded2.report().accepted()).isTrue();
    }

    @Test
    void loadVerifiedFallsBackOnMissingMalformedOrInvalidDocument() {
        assertThat(loader.loadVerified(null, null, null, null).report().usedFallback()).isTrue();
        assertThat(loader.loadVerified("  ", null, null, "x").report().source()).isEqualTo("x");
        LoadedPolicy malformed = loader.loadVerified("{not json", null, null, "x");
        assertThat(malformed.report().usedFallback()).isTrue();
        assertThat(malformed.report().violations()).isNotEmpty();
        LoadedPolicy invalid = loader.loadVerified("{\"schemaVersion\":\"1.0\",\"workflows\":{\"RESIDENT\":{\"passiveThreshold\":7}}}", null, null, "x");
        assertThat(invalid.report().usedFallback()).isTrue();
        assertThat(invalid.report().violations()).anyMatch(v -> v.contains("passiveThreshold"));
    }

    @Test
    void reportDefaultsAndImmutability() {
        PolicyLoadReport r = new PolicyLoadReport("s", null, PolicyLoadReport.SignatureStatus.UNSIGNED, null, false);
        assertThat(r.version()).isEqualTo("unversioned");
        assertThat(r.violations()).isEmpty();
        assertThat(r.accepted()).isTrue();
        assertThat(r.signatureOk()).isFalse();
    }
}
