package io.mosip.vivid.telemetry.diagnostics;

import io.mosip.vivid.telemetry.json.Json;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class DiagnosticsTest {

    @Test
    void snapshotBuilderDefaultsAndJson() {
        Instant t = Instant.parse("2026-03-01T00:00:00Z");
        DiagnosticSnapshot s = DiagnosticSnapshot.builder().capturedAt(t).sessionId("s").phase("PASSIVE_EVALUATING")
                .fusedLivenessScore(0.9).signalScore("moire", 0.1).signalScores(Map.of("cnn_texture", 0.2))
                .framesPerSecond(14.5).framesAnalyzed(30).engineId("onnx").engineVersion("1.2").modelBundleId("mb")
                .modelBundleVersion("3").policyVersion("p1").deviceHealth("CONNECTED").extra("note", null).build();
        assertThat(s.lastSignalScores()).containsExactly(Map.entry("cnn_texture", 0.2), Map.entry("moire", 0.1));
        assertThat(s.extra()).containsEntry("note", "");
        Map<String, Object> json = Json.parseObject(s.toJson());
        assertThat(json.get("capturedAt")).isEqualTo(t.toString());
        assertThat(json.get("engineId")).isEqualTo("onnx");
        assertThat(json.get("framesAnalyzed")).isEqualTo(30L);
        assertThat(((Map<?, ?>) json.get("lastSignalScores")).get("moire")).isEqualTo(0.1);
        assertThat(s.toString()).isEqualTo(s.toJson());
        assertThatThrownBy(() -> s.lastSignalScores().put("x", 1.0)).isInstanceOf(UnsupportedOperationException.class);

        DiagnosticSnapshot d = DiagnosticSnapshot.builder().build();
        assertThat(d.engineId()).isEqualTo("unknown");
        assertThat(d.modelBundleVersion()).isEqualTo("none");
        assertThat(d.policyVersion()).isEqualTo("unversioned");
        assertThat(d.deviceHealth()).isEqualTo("UNKNOWN");
        assertThat(d.sessionId()).isEmpty();
        assertThat(d.phase()).isEmpty();
        assertThat(d.capturedAt()).isNotNull();
        DiagnosticSource src = () -> d;
        assertThat(src.captureDiagnostics()).isSameAs(d);
    }

    @Test
    void fpsMeterSlidingWindow() {
        FpsMeter m = new FpsMeter(1000);
        assertThat(m.fps(0)).isZero();
        m.tick(0);
        assertThat(m.fps(0)).isZero();
        for (int i = 1; i <= 10; i++) {
            m.tick(i * 100_000_000L);
        }
        assertThat(m.fps(1_000_000_000L)).isCloseTo(10.0, within(1e-9));
        // Window slides: frames older than 1 s before "now" are evicted.
        assertThat(m.fps(1_900_000_000L)).isCloseTo(10.0, within(1e-9));
        assertThat(m.fps(3_000_000_000L)).isZero();
        m.tick(5_000_000_000L);
        m.tick(5_000_000_000L);
        assertThat(m.fps(5_000_000_000L)).isZero();
        m.reset();
        assertThat(m.fps(5_000_000_000L)).isZero();
        assertThatThrownBy(() -> new FpsMeter(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
