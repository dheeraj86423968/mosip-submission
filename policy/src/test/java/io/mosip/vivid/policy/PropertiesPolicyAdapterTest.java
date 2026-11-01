package io.mosip.vivid.policy;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.Workflow;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PropertiesPolicyAdapterTest {

    private final PropertiesPolicyAdapter adapter = new PropertiesPolicyAdapter();

    @Test
    void flattensToDocumentedKeys() {
        Properties p = adapter.toProperties(PolicyLoader.builtinDefaults());
        assertThat(p.getProperty("mosip.registration.liveness.resident.passive.threshold")).isEqualTo("0.997");
        assertThat(p.getProperty("mosip.registration.liveness.resident.active.pool")).isEqualTo("BLINK,SMILE,TURN_LEFT,TURN_RIGHT,OPEN_MOUTH");
        assertThat(p.getProperty("mosip.registration.liveness.resident.active.mode")).isEqualTo("ALWAYS");
        assertThat(p.getProperty("mosip.registration.liveness.resident.signals.rppg.weight")).isEqualTo("0.8");
        assertThat(p.getProperty("mosip.registration.liveness.resident.retries.action")).isEqualTo("ASSISTED_CAPTURE_WITH_SUPERVISOR");
        assertThat(p.getProperty("mosip.registration.liveness.supervisor.retries.max")).isEqualTo("2");
        assertThat(p.getProperty("mosip.registration.liveness.device.min.fps")).isEqualTo("10");
        assertThat(p.getProperty("mosip.registration.liveness.diagnostics.mode")).isEqualTo("OFF");
        assertThat(p.getProperty("mosip.registration.liveness.schema.version")).isEqualTo("1.0");
        assertThat(p.stringPropertyNames()).allMatch(k -> k.startsWith(PropertiesPolicyAdapter.PREFIX));
        assertThat(p.getProperty("mosip.registration.liveness.operator.passive.window.ms")).isNull();
    }

    @Test
    void roundTripPreservesDocument() {
        PolicyDocument doc = PolicyLoader.builtinDefaults();
        PolicyDocument back = adapter.fromProperties(adapter.toProperties(doc)).orElseThrow();
        assertThat(back).isEqualTo(doc);
    }

    @Test
    void unflattensPartialKeys() {
        Properties p = new Properties();
        p.setProperty("mosip.registration.liveness.resident.passive.threshold", "0.75");
        p.setProperty("mosip.registration.liveness.resident.active.pool", "blink, smile ,TURN_LEFT,");
        p.setProperty("mosip.registration.liveness.operator.enabled", "false");
        p.setProperty("mosip.registration.liveness.version", "sync-9");
        PolicyDocument doc = adapter.fromProperties(p).orElseThrow();
        assertThat(doc.schemaVersion()).isEqualTo("1.0");
        assertThat(doc.version()).isEqualTo("sync-9");
        assertThat(doc.workflows()).containsOnlyKeys(Workflow.RESIDENT, Workflow.OPERATOR);
        WorkflowPolicy r = doc.workflow(Workflow.RESIDENT).orElseThrow();
        assertThat(r.passiveThreshold()).isEqualTo(0.75);
        assertThat(r.active().pool()).containsExactly(ChallengeType.BLINK, ChallengeType.SMILE, ChallengeType.TURN_LEFT);
        assertThat(r.active().mode()).isNull();
        assertThat(r.signals()).isNull();
        assertThat(doc.workflow(Workflow.OPERATOR).orElseThrow().enabled()).isFalse();
        assertThat(doc.device()).isNull();
        LivenessPolicy eff = new PolicyLoader().toLivenessPolicy(doc, Workflow.RESIDENT);
        assertThat(eff.passiveThreshold()).isEqualTo(0.75);
        assertThat(eff.minChallenges()).isEqualTo(2);
    }

    @Test
    void jsonKeyTakesPrecedence() {
        Properties p = new Properties();
        p.setProperty("mosip.registration.liveness.resident.passive.threshold", "0.1");
        adapter.putJson(p, "{\"schemaVersion\":\"1.0\",\"workflows\":{\"RESIDENT\":{\"passiveThreshold\":0.99}}}", "c2ln");
        PolicyDocument doc = adapter.fromProperties(p).orElseThrow();
        assertThat(doc.workflow(Workflow.RESIDENT).orElseThrow().passiveThreshold()).isEqualTo(0.99);
        assertThat(adapter.signature(p)).contains("c2ln");
        adapter.putJson(p, "{\"schemaVersion\":\"1.0\"}", null);
        assertThat(adapter.signature(p)).isEmpty();
    }

    @Test
    void emptyPropertiesYieldNoDocument() {
        Properties p = new Properties();
        p.setProperty("mosip.registration.other", "x");
        assertThat(adapter.fromProperties(p)).isEmpty();
        assertThat(adapter.signature(p)).isEmpty();
    }

    @Test
    void malformedValuesAreReportedWithKey() {
        Properties p = new Properties();
        p.setProperty("mosip.registration.liveness.resident.passive.threshold", "high");
        assertThatThrownBy(() -> adapter.fromProperties(p)).isInstanceOf(PolicyException.class).hasMessageContaining("passive.threshold");
        p.clear();
        p.setProperty("mosip.registration.liveness.resident.enabled", "yes");
        assertThatThrownBy(() -> adapter.fromProperties(p)).hasMessageContaining("enabled");
        p.clear();
        p.setProperty("mosip.registration.liveness.resident.active.mode", "SOMETIMES");
        assertThatThrownBy(() -> adapter.fromProperties(p)).hasMessageContaining("SOMETIMES");
        p.clear();
        p.setProperty("mosip.registration.liveness.resident.active.pool", "WINK");
        assertThatThrownBy(() -> adapter.fromProperties(p)).hasMessageContaining("WINK");
        p.clear();
        p.setProperty("mosip.registration.liveness.resident.retries.max", "three");
        assertThatThrownBy(() -> adapter.fromProperties(p)).hasMessageContaining("retries.max");
        p.clear();
        p.setProperty("mosip.registration.liveness.resident.session.timeout.ms", "1e3");
        assertThatThrownBy(() -> adapter.fromProperties(p)).hasMessageContaining("session.timeout.ms");
    }

    @Test
    void deviceAndDiagnosticsRoundTrip() {
        Properties p = new Properties();
        p.setProperty("mosip.registration.liveness.device.reconnect.attempts", "2");
        p.setProperty("mosip.registration.liveness.diagnostics.mode", "supervisor_only");
        PolicyDocument doc = adapter.fromProperties(p).orElseThrow();
        assertThat(doc.device().reconnectAttempts()).isEqualTo(2);
        assertThat(doc.device().minFps()).isNull();
        assertThat(doc.diagnostics().mode()).isEqualTo(DiagnosticsPolicy.Mode.SUPERVISOR_ONLY);
        assertThat(doc.workflows()).isEmpty();
    }
}
