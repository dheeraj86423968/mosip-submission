package io.mosip.vivid.policy;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.Workflow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyDocumentTest {

    private final PolicyLoader loader = new PolicyLoader();

    @Test
    void parsesThePlanExampleDocument() {
        String json = """
            {
              "schemaVersion": "1.0",
              "workflows": {
                "RESIDENT":   { "enabled": true, "passiveThreshold": 0.80, "active": { "mode": "ON_LOW_CONFIDENCE", "minChallenges": 2, "maxChallenges": 3, "pool": ["BLINK","SMILE","TURN_LEFT","TURN_RIGHT","NOD"], "timeoutMs": 8000 }, "maxRetries": 3, "onMaxRetries": "ASSISTED_CAPTURE_WITH_SUPERVISOR", "signals": { "rppg": { "enabled": true, "weight": 0.8 }, "parallax": { "enabled": true, "weight": 1.2 } } },
                "OPERATOR":   { "enabled": true, "passiveThreshold": 0.85, "active": { "mode": "ALWAYS", "minChallenges": 1 }, "maxRetries": 3, "onMaxRetries": "LOCK_AND_ALERT" },
                "SUPERVISOR": { "enabled": true, "passiveThreshold": 0.90, "active": { "mode": "ALWAYS", "minChallenges": 2 }, "maxRetries": 2, "onMaxRetries": "LOCK_AND_ALERT" }
              },
              "device": { "minFps": 10, "reconnectAttempts": 5 },
              "diagnostics": { "mode": "OFF" }
            }
            """;
        PolicyDocument doc = loader.parse(json);
        assertThat(doc.schemaVersion()).isEqualTo("1.0");
        assertThat(doc.workflows()).containsOnlyKeys(Workflow.RESIDENT, Workflow.OPERATOR, Workflow.SUPERVISOR);
        WorkflowPolicy resident = doc.workflow(Workflow.RESIDENT).orElseThrow();
        assertThat(resident.passiveThreshold()).isEqualTo(0.80);
        assertThat(resident.active().pool()).containsExactly(ChallengeType.BLINK, ChallengeType.SMILE, ChallengeType.TURN_LEFT,
                ChallengeType.TURN_RIGHT, ChallengeType.NOD);
        assertThat(resident.active().mode()).isEqualTo(LivenessPolicy.ActiveMode.ON_LOW_CONFIDENCE);
        assertThat(resident.signals().get("rppg").weight()).isEqualTo(0.8);
        assertThat(resident.onMaxRetries()).isEqualTo(LivenessPolicy.OnMaxRetries.ASSISTED_CAPTURE_WITH_SUPERVISOR);
        assertThat(doc.device().minFps()).isEqualTo(10);
        assertThat(doc.device().stallTimeoutMs()).isNull();
        assertThat(doc.diagnostics().mode()).isEqualTo(DiagnosticsPolicy.Mode.OFF);
    }

    @Test
    void rejectsUnknownTopLevelField() {
        assertThatThrownBy(() -> loader.parse("{\"schemaVersion\":\"1.0\",\"livenes\":{}}"))
                .isInstanceOf(PolicyException.class)
                .hasMessageContaining("livenes");
    }

    @Test
    void rejectsUnknownNestedField() {
        assertThatThrownBy(() -> loader.parse("{\"schemaVersion\":\"1.0\",\"workflows\":{\"RESIDENT\":{\"passiveTreshold\":0.5}}}"))
                .isInstanceOf(PolicyException.class)
                .hasMessageContaining("passiveTreshold");
    }

    @Test
    void rejectsUnknownWorkflowKey() {
        assertThatThrownBy(() -> loader.parse("{\"schemaVersion\":\"1.0\",\"workflows\":{\"CITIZEN\":{}}}"))
                .isInstanceOf(PolicyException.class);
    }

    @Test
    void rejectsUnknownChallengeType() {
        assertThatThrownBy(() -> loader.parse("{\"schemaVersion\":\"1.0\",\"workflows\":{\"RESIDENT\":{\"active\":{\"pool\":[\"WINK\"]}}}}"))
                .isInstanceOf(PolicyException.class);
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> loader.parse("{\"schemaVersion\": ")).isInstanceOf(PolicyException.class);
        assertThatThrownBy(() -> loader.parse("")).isInstanceOf(PolicyException.class);
    }

    @Test
    void serialisationRoundTripsAndOmitsNulls() {
        PolicyDocument doc = new PolicyDocument("1.0", "v7",
                Map.of(Workflow.OPERATOR, WorkflowPolicy.builder().passiveThreshold(0.7).build()), null, null);
        String json = PolicyJson.write(doc);
        assertThat(json).doesNotContain("null").contains("\"version\":\"v7\"");
        assertThat(loader.parse(json)).isEqualTo(doc);
        assertThat(PolicyJson.writePretty(doc)).contains("\n");
    }

    @Test
    void documentsAreImmutable() {
        PolicyDocument doc = loader.parse("{\"schemaVersion\":\"1.0\",\"workflows\":{\"RESIDENT\":{\"active\":{\"pool\":[\"BLINK\"]}}}}");
        assertThatThrownBy(() -> doc.workflows().put(Workflow.OPERATOR, WorkflowPolicy.empty()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> doc.workflow(Workflow.RESIDENT).orElseThrow().active().pool().add(ChallengeType.NOD))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void withersProduceModifiedCopies() {
        PolicyDocument base = new PolicyDocument("1.0", null, Map.of(), null, null);
        PolicyDocument v2 = base.withVersion("2").withWorkflow(Workflow.SUPERVISOR, WorkflowPolicy.builder().maxRetries(1).build());
        assertThat(base.version()).isNull();
        assertThat(v2.version()).isEqualTo("2");
        assertThat(v2.workflow(Workflow.SUPERVISOR).orElseThrow().maxRetries()).isEqualTo(1);
        assertThat(base.workflows()).isEmpty();
    }

    @Test
    void signalPolicyEffectiveWeight() {
        assertThat(new SignalPolicy(null, null).effectiveWeight()).isEqualTo(1.0);
        assertThat(new SignalPolicy(true, 0.3).effectiveWeight()).isEqualTo(0.3);
        assertThat(new SignalPolicy(false, 0.3).effectiveWeight()).isEqualTo(0.0);
        assertThat(new ActivePolicy(null, null, null, null, null, null, null, null, null).poolOrEmpty()).isEmpty();
        assertThat(new ActivePolicy(null, null, null, List.of(ChallengeType.NOD), null, null, null, null, null).poolOrEmpty()).containsExactly(ChallengeType.NOD);
    }
}
