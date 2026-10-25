package io.mosip.vivid.telemetry.metrics;

import io.mosip.vivid.api.FailureReason;
import io.mosip.vivid.telemetry.json.Json;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class LivenessMetricsTest {

    @Test
    void emptySnapshotHasZeroRates() {
        MetricsSnapshot s = new LivenessMetrics().snapshot();
        assertThat(s.sessionsStarted()).isZero();
        assertThat(s.retryRate()).isZero();
        assertThat(s.failureRate()).isZero();
        assertThat(s.padRate()).isZero();
        assertThat(s.deviceFaultRate()).isZero();
        assertThat(s.averagePassiveMillis()).isZero();
        assertThat(s.averageChallengeCompletionMillis()).isZero();
        assertThat(s.failuresByReason()).isEmpty();
    }

    @Test
    void aggregatesCountersAndAverages() {
        LivenessMetrics m = new LivenessMetrics();
        m.sessionStarted(); m.sessionStarted(); m.sessionStarted(); m.sessionStarted();
        m.sessionPassed(); m.sessionPassed();
        m.sessionFailed(FailureReason.PAD_DETECTED); m.sessionFailed(FailureReason.CHALLENGE_TIMEOUT); m.sessionFailed(FailureReason.PAD_DETECTED);
        m.retry();
        m.passiveDuration(2000); m.passiveDuration(3000); m.passiveDuration(-5);
        m.challengeCompletion(1000); m.challengeCompletion(500);
        m.padDetected("PRINT"); m.padDetected("PRINT"); m.padDetected(null); m.padDetected(" ");
        m.deviceFault("DISCONNECTED"); m.deviceFault(null);
        MetricsSnapshot s = m.snapshot();
        assertThat(s.sessionsStarted()).isEqualTo(4);
        assertThat(s.sessionsPassed()).isEqualTo(2);
        assertThat(s.sessionsFailed()).isEqualTo(3);
        assertThat(s.retries()).isEqualTo(1);
        assertThat(s.retryRate()).isEqualTo(0.25);
        assertThat(s.failureRate()).isEqualTo(0.75);
        assertThat(s.padDetections()).isEqualTo(4);
        assertThat(s.padRate()).isEqualTo(1.0);
        assertThat(s.deviceFaults()).isEqualTo(2);
        assertThat(s.deviceFaultRate()).isEqualTo(0.5);
        assertThat(s.passiveSamples()).isEqualTo(3);
        assertThat(s.averagePassiveMillis()).isCloseTo(5000.0 / 3, within(1e-9));
        assertThat(s.challengeSamples()).isEqualTo(2);
        assertThat(s.averageChallengeCompletionMillis()).isEqualTo(750.0);
        assertThat(s.failuresByReason()).containsEntry("PAD_DETECTED", 2L).containsEntry("CHALLENGE_TIMEOUT", 1L);
        assertThat(s.padByAttackClass()).containsEntry("PRINT", 2L).containsEntry("UNKNOWN", 2L);
        assertThat(s.deviceFaultsByKind()).containsEntry("DISCONNECTED", 1L).containsEntry("UNKNOWN", 1L);
        assertThatThrownBy(() -> s.failuresByReason().put("x", 1L)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> m.sessionFailed(null)).isInstanceOf(NullPointerException.class);

        Map<String, Object> json = Json.parseObject(m.toJson());
        assertThat(json.get("sessionsStarted")).isEqualTo(4L);
        assertThat(json.get("failureRate")).isEqualTo(0.75);
        assertThat(((Map<?, ?>) json.get("padByAttackClass")).get("PRINT")).isEqualTo(2L);
        assertThat(json).doesNotContainKeys("sessionId", "residentId");
        assertThat(s.toString()).isEqualTo(s.toJson());

        m.reset();
        assertThat(m.snapshot().sessionsStarted()).isZero();
        assertThat(m.snapshot().padByAttackClass()).isEmpty();
    }

    @Test
    void isThreadSafe() throws Exception {
        LivenessMetrics m = new LivenessMetrics();
        int threads = 8;
        int per = 5000;
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(() -> {
                for (int i = 0; i < per; i++) {
                    m.sessionStarted();
                    m.sessionFailed(FailureReason.FACE_NOT_DETECTED);
                    m.passiveDuration(10);
                    m.padDetected("SCREEN_REPLAY");
                }
            });
            ts.add(th);
            th.start();
        }
        for (Thread th : ts) {
            th.join();
        }
        MetricsSnapshot s = m.snapshot();
        assertThat(s.sessionsStarted()).isEqualTo((long) threads * per);
        assertThat(s.failuresByReason().get("FACE_NOT_DETECTED")).isEqualTo((long) threads * per);
        assertThat(s.averagePassiveMillis()).isEqualTo(10.0);
        assertThat(s.padByAttackClass().get("SCREEN_REPLAY")).isEqualTo((long) threads * per);
    }
}
