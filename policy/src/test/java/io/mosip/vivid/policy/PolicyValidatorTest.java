package io.mosip.vivid.policy;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.EngineInfo;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.api.Workflow;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyValidatorTest {

    private final PolicyLoader loader = new PolicyLoader();
    private final PolicyValidator validator = new PolicyValidator();

    private List<String> validate(String json) {
        return validator.validate(loader.parse(json));
    }

    @Test
    void defaultsAreValid() {
        assertThat(validator.validate(PolicyLoader.builtinDefaults())).isEmpty();
        assertThat(validator.engine()).isEmpty();
    }

    @Test
    void schemaVersionRules() {
        assertThat(validator.validate(new PolicyDocument(null, null, null, null, null))).anyMatch(v -> v.contains("schemaVersion is required"));
        assertThat(validate("{\"schemaVersion\":\"2.0\"}")).anyMatch(v -> v.contains("not supported"));
        assertThat(validate("{\"schemaVersion\":\"1.3\"}")).isEmpty();
    }

    @Test
    void thresholdsMustBeInRange() {
        List<String> v = validate("{\"schemaVersion\":\"1.0\",\"workflows\":{\"RESIDENT\":{\"passiveThreshold\":1.5,\"padHardThreshold\":-0.1,\"minFaceQuality\":2}}}");
        assertThat(v).anyMatch(s -> s.contains("RESIDENT.passiveThreshold"))
                .anyMatch(s -> s.contains("RESIDENT.padHardThreshold"))
                .anyMatch(s -> s.contains("RESIDENT.minFaceQuality"));
    }

    @Test
    void timeoutsAndCountsMustBePositive() {
        List<String> v = validate("{\"schemaVersion\":\"1.0\",\"workflows\":{\"OPERATOR\":{\"passiveWindowMs\":0,\"passiveMinFrames\":0,\"sessionTimeoutMs\":-5,\"maxRetries\":0,"
                + "\"active\":{\"timeoutMs\":0,\"maxActionLatencyMs\":0,\"minActionLatencyMs\":-1,\"minChallenges\":-1,\"maxChallenges\":-2}}}}");
        assertThat(v).anyMatch(s -> s.contains("passiveWindowMs"))
                .anyMatch(s -> s.contains("passiveMinFrames"))
                .anyMatch(s -> s.contains("sessionTimeoutMs"))
                .anyMatch(s -> s.contains("maxRetries"))
                .anyMatch(s -> s.contains("active.timeoutMs"))
                .anyMatch(s -> s.contains("maxActionLatencyMs must be > 0"))
                .anyMatch(s -> s.contains("minActionLatencyMs must be >= 0"))
                .anyMatch(s -> s.contains("minChallenges must be >= 0"))
                .anyMatch(s -> s.contains("maxChallenges must be >= 0"));
    }

    @Test
    void minChallengesMustNotExceedMax() {
        assertThat(validate("{\"schemaVersion\":\"1.0\",\"workflows\":{\"RESIDENT\":{\"active\":{\"minChallenges\":3,\"maxChallenges\":2}}}}"))
                .anyMatch(s -> s.contains("minChallenges must be <= maxChallenges"));
    }

    @Test
    void poolMustNotBeEmptyWhenActive() {
        assertThat(validate("{\"schemaVersion\":\"1.0\",\"workflows\":{\"RESIDENT\":{\"active\":{\"pool\":[]}}}}"))
                .anyMatch(s -> s.contains("pool must not be empty") || s.contains("challengePool empty"));
        assertThat(validate("{\"schemaVersion\":\"1.0\",\"workflows\":{\"RESIDENT\":{\"active\":{\"mode\":\"NEVER\",\"pool\":[]}}}}")).isEmpty();
    }

    @Test
    void latencyWindowAndDepthRules() {
        assertThat(validate("{\"schemaVersion\":\"1.0\",\"workflows\":{\"RESIDENT\":{\"active\":{\"minActionLatencyMs\":7000,\"maxActionLatencyMs\":6000}}}}"))
                .anyMatch(s -> s.contains("minActionLatencyMs must be < maxActionLatencyMs"));
        assertThat(validate("{\"schemaVersion\":\"1.0\",\"workflows\":{\"RESIDENT\":{\"active\":{\"maxActionLatencyMs\":9000,\"timeoutMs\":8000}}}}"))
                .anyMatch(s -> s.contains("should not exceed timeoutMs"));
        assertThat(validate("{\"schemaVersion\":\"1.0\",\"workflows\":{\"RESIDENT\":{\"active\":{\"pool\":[\"BLINK\",\"SMILE\"],\"requireDepthRevealing\":true}}}}"))
                .anyMatch(s -> s.contains("no depth-revealing challenge"));
        assertThat(validate("{\"schemaVersion\":\"1.0\",\"workflows\":{\"RESIDENT\":{\"active\":{\"pool\":[\"BLINK\",\"SMILE\"],\"requireDepthRevealing\":false}}}}")).isEmpty();
    }

    @Test
    void identityAndSignalRules() {
        List<String> v = validate("{\"schemaVersion\":\"1.0\",\"workflows\":{\"RESIDENT\":{\"identity\":{\"driftThreshold\":0},"
                + "\"signals\":{\"bogus\":{\"weight\":1},\"rppg\":{\"weight\":-1},\"\":{}}}}}");
        assertThat(v).anyMatch(s -> s.contains("identity.driftThreshold"))
                .anyMatch(s -> s.contains("signals.bogus is not a known signal id"))
                .anyMatch(s -> s.contains("signals.rppg.weight"))
                .anyMatch(s -> s.contains("blank signal id"));
    }

    @Test
    void deviceRules() {
        List<String> v = validate("{\"schemaVersion\":\"1.0\",\"device\":{\"minFps\":0,\"reconnectAttempts\":-1,\"stallTimeoutMs\":0}}");
        assertThat(v).hasSize(3);
    }

    @Test
    void engineBoundValidatorChecksChallengeAndSignalSupport() {
        EngineInfo engine = new EngineInfo("test-engine", "1", "bundle", "1", EnumSet.of(ChallengeType.BLINK, ChallengeType.SMILE, ChallengeType.NOD),
                Set.of(Signals.CNN_TEXTURE), false);
        PolicyValidator bound = new PolicyValidator(engine);
        assertThat(bound.engine()).contains(engine);
        PolicyDocument doc = loader.parse("{\"schemaVersion\":\"1.0\",\"workflows\":{\"RESIDENT\":{\"active\":{\"pool\":[\"BLINK\",\"TURN_LEFT\",\"NOD\"],\"accessibilityPool\":[\"HOLD_STILL\"]},"
                + "\"signals\":{\"moire\":{\"weight\":1},\"challenge_timing\":{\"weight\":1},\"cnn_texture\":{\"weight\":1}}}}}");
        List<String> v = bound.validate(doc);
        assertThat(v).anyMatch(s -> s.contains("pool contains TURN_LEFT"))
                .anyMatch(s -> s.contains("accessibilityPool contains HOLD_STILL"))
                .anyMatch(s -> s.contains("signals.moire is not produced by engine"))
                .noneMatch(s -> s.contains("challenge_timing"))
                .noneMatch(s -> s.contains("cnn_texture"))
                .noneMatch(s -> s.contains("NOD"));
    }

    @Test
    void nullWorkflowBlockIsReported() {
        java.util.Map<Workflow, WorkflowPolicy> m = new java.util.HashMap<>();
        m.put(Workflow.RESIDENT, null);
        PolicyDocument doc = new PolicyDocument("1.0", null, m, null, null);
        assertThat(validator.validate(doc)).anyMatch(s -> s.contains("workflow block is null"));
    }

    @Test
    void validProgrammaticDocumentHasNoViolations() {
        PolicyDocument doc = new PolicyDocument("1.0", "v", Map.of(Workflow.OPERATOR, WorkflowPolicy.builder()
                .active(new ActivePolicy(LivenessPolicy.ActiveMode.ALWAYS, 1, 2, List.of(ChallengeType.NOD, ChallengeType.BLINK), null, true, 8000L, 100L, 5000L))
                .signals(Map.of(Signals.RPPG, new SignalPolicy(true, 0.5))).build()), DevicePolicy.defaults(), DiagnosticsPolicy.defaults());
        assertThat(validator.validate(doc)).isEmpty();
    }
}
