package io.mosip.vivid.telemetry.audit;

import io.mosip.vivid.api.Workflow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditRecordTest {

    @Test
    void enumHasExactlyThePlannedEventsWithSeverityAndDescription() {
        assertThat(LivenessAuditEvent.values()).extracting(Enum::name).containsExactly(
                "SESSION_STARTED", "PASSIVE_PASSED", "PASSIVE_BELOW_THRESHOLD", "CHALLENGE_ISSUED", "CHALLENGE_PASSED",
                "CHALLENGE_TIMEOUT", "UNPROMPTED_ACTION", "PAD_DETECTED", "IDENTITY_DRIFT", "SESSION_PASSED", "SESSION_FAILED",
                "MAX_RETRIES_REACHED", "SUPERVISOR_OVERRIDE", "DEVICE_FAULT", "POLICY_LOADED", "MODEL_BUNDLE_LOADED",
                "MODEL_BUNDLE_REJECTED", "CALIBRATION_RELEASED", "CALIBRATION_BUDGET_EXHAUSTED");
        for (LivenessAuditEvent e : LivenessAuditEvent.values()) {
            assertThat(e.description()).isNotBlank();
            assertThat(e.severity()).isNotNull();
        }
        assertThat(LivenessAuditEvent.PAD_DETECTED.severity()).isEqualTo(LivenessAuditEvent.Severity.SECURITY);
        assertThat(LivenessAuditEvent.DEVICE_FAULT.severity()).isEqualTo(LivenessAuditEvent.Severity.ERROR);
    }

    @Test
    void builderRedactsBiometricKeysAndIsImmutable() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("fusedScore", 0.93);
        attrs.put("frameBytes", "...");
        attrs.put("faceImage", "...");
        attrs.put("PixelData", "...");
        attrs.put("anchorEmbedding", "...");
        attrs.put("nullValue", null);
        AuditRecord r = AuditRecord.builder(LivenessAuditEvent.SESSION_PASSED).sessionId("s1").workflow(Workflow.RESIDENT)
                .attributes(attrs).attribute("landmarks", "x").attribute("long", "x".repeat(1000)).evidenceHash("abc").build();
        assertThat(r.attributes()).containsKeys("fusedScore", "nullValue", "long")
                .doesNotContainKeys("frameBytes", "faceImage", "PixelData", "anchorEmbedding", "landmarks");
        assertThat(r.attributes().get("nullValue")).isEmpty();
        assertThat(r.attributes().get("long")).hasSize(AuditRedactor.MAX_VALUE_LENGTH + 3).endsWith("...");
        assertThatThrownBy(() -> r.attributes().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(r.evidenceHash()).contains("abc");
        assertThat(r.workflow()).contains(Workflow.RESIDENT);
        assertThat(r.toString()).contains("SESSION_PASSED").contains("s1");
        assertThat(AuditRedactor.isForbiddenKey(null)).isTrue();
        assertThat(AuditRedactor.redact(null)).isEmpty();
    }

    @Test
    void jsonRoundTrip() {
        Instant t = Instant.parse("2026-03-01T10:15:30Z");
        AuditRecord r = AuditRecord.builder(LivenessAuditEvent.CHALLENGE_ISSUED).sessionId("s\"1").workflow(Workflow.OPERATOR).at(t)
                .attribute("type", "BLINK").attribute("index", 0).attribute("nonce", "00ff").build();
        String json = r.toJson();
        assertThat(json).doesNotContain("\n");
        AuditRecord back = AuditRecord.fromJson(json);
        assertThat(back).isEqualTo(r);
        assertThat(back.hashCode()).isEqualTo(r.hashCode());
        assertThat(back.attributes().get("index")).isEqualTo("0");
        AuditRecord noWf = AuditRecord.builder(LivenessAuditEvent.POLICY_LOADED).at(t).build();
        AuditRecord noWfBack = AuditRecord.fromJson(noWf.toJson());
        assertThat(noWfBack.workflow()).isEmpty();
        assertThat(noWfBack.sessionId()).isEmpty();
        assertThat(noWfBack.evidenceHash()).isEmpty();
        assertThat(noWfBack).isEqualTo(noWf).isNotEqualTo(r).isNotEqualTo("x");
    }

    @Test
    void fromJsonRejectsIncompleteLines() {
        assertThatThrownBy(() -> AuditRecord.fromJson("{\"event\":\"SESSION_STARTED\"}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditRecord.fromJson("{\"event\":\"NOPE\",\"timestamp\":\"2026-01-01T00:00:00Z\"}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditRecord.fromJson("garbage")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditRecord.builder(null)).isInstanceOf(NullPointerException.class);
    }
}
