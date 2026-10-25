package io.mosip.vivid.desktop;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.Workflow;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static io.mosip.vivid.desktop.DesktopLivenessConfig.Keys.ROOT;
import static org.assertj.core.api.Assertions.assertThat;

class DesktopLivenessConfigTest {

    @Test
    void emptyMapYieldsCompiledDefaultsPerWorkflow() {
        DesktopLivenessConfig c = DesktopLivenessConfig.fromApplicationMap(Map.of());
        for (Workflow w : Workflow.values()) {
            LivenessPolicy d = LivenessPolicy.defaults(w), p = c.policy(w);
            assertThat(c.enabled(w)).isTrue();
            assertThat(p.passiveThreshold()).isEqualTo(d.passiveThreshold());
            assertThat(p.activeMode()).isEqualTo(d.activeMode());
            assertThat(p.minChallenges()).isEqualTo(d.minChallenges());
            assertThat(p.maxChallenges()).isEqualTo(d.maxChallenges());
            assertThat(p.maxRetries()).isEqualTo(d.maxRetries());
            assertThat(p.onMaxRetries()).isEqualTo(d.onMaxRetries());
            assertThat(p.challengePool()).isEqualTo(d.challengePool());
            assertThat(p.policyVersion()).isEqualTo(d.policyVersion());
        }
        assertThat(c.locale()).isEqualTo(Locale.ENGLISH);
        assertThat(c.retryDelayMillis(Workflow.RESIDENT)).isEqualTo(1500);
        assertThat(c.diagnosticsEnabled()).isFalse();
    }

    @Test
    void globalAndWorkflowOverridesAreAppliedInOrder() {
        Map<String, Object> m = new HashMap<>();
        m.put(ROOT + "passive.threshold", "0.70");
        m.put(ROOT + "supervisor.passive.threshold", "0.95");
        m.put(ROOT + "challenge.pool", "BLINK, smile ;turn-left");
        m.put(ROOT + "active.mode", "always");
        m.put(ROOT + "on_max_retries", "lock-and-alert");
        m.put(ROOT + "challenge.timeout_ms", "5000");
        m.put(ROOT + "signal.weight.rppg", "0");
        m.put(ROOT + "operator.signal.weight.rppg", "0.5");
        m.put(ROOT + "retries", "4");
        m.put(ROOT + "policy.version", "centre-7");
        m.put("mosip.registration.face_threshold", "80"); // unrelated key must be ignored
        DesktopLivenessConfig c = DesktopLivenessConfig.fromApplicationMap(m);

        assertThat(c.policy(Workflow.RESIDENT).passiveThreshold()).isEqualTo(0.70);
        assertThat(c.policy(Workflow.SUPERVISOR).passiveThreshold()).isEqualTo(0.95);
        assertThat(c.policy(Workflow.RESIDENT).challengePool()).containsExactlyInAnyOrder(ChallengeType.BLINK, ChallengeType.SMILE, ChallengeType.TURN_LEFT);
        assertThat(c.policy(Workflow.RESIDENT).activeMode()).isEqualTo(LivenessPolicy.ActiveMode.ALWAYS);
        assertThat(c.policy(Workflow.RESIDENT).onMaxRetries()).isEqualTo(LivenessPolicy.OnMaxRetries.LOCK_AND_ALERT);
        assertThat(c.policy(Workflow.RESIDENT).challengeTimeoutMillis()).isEqualTo(5000);
        assertThat(c.policy(Workflow.RESIDENT).signalWeight("rppg")).isEqualTo(0.0);
        assertThat(c.policy(Workflow.OPERATOR).signalWeight("rppg")).isEqualTo(0.5);
        assertThat(c.policy(Workflow.OPERATOR).maxRetries()).isEqualTo(4);
        assertThat(c.policy(Workflow.OPERATOR).policyVersion()).isEqualTo("centre-7");
        assertThat(c.rawEntries()).doesNotContainKey("mosip.registration.face_threshold");
    }

    @Test
    void retriesFallBackToExistingMosipFaceRetriesForResidentOnly() {
        DesktopLivenessConfig c = DesktopLivenessConfig.fromApplicationMap(Map.of("mosip.registration.num_of_face_retries", "5"));
        assertThat(c.maxRetries(Workflow.RESIDENT)).isEqualTo(5);
        assertThat(c.maxRetries(Workflow.OPERATOR)).isEqualTo(LivenessPolicy.defaults(Workflow.OPERATOR).maxRetries());
        DesktopLivenessConfig own = DesktopLivenessConfig.fromApplicationMap(Map.of("mosip.registration.num_of_face_retries", "5", ROOT + "retries", "2"));
        assertThat(own.maxRetries(Workflow.RESIDENT)).isEqualTo(2);
    }

    @Test
    void enabledFlagsAcceptMosipStyleYAndN() {
        assertThat(DesktopLivenessConfig.fromApplicationMap(Map.of(ROOT + "enabled", "N")).enabled(Workflow.RESIDENT)).isFalse();
        assertThat(DesktopLivenessConfig.fromApplicationMap(Map.of(ROOT + "enabled", "Y")).enabled(Workflow.RESIDENT)).isTrue();
        DesktopLivenessConfig perWf = DesktopLivenessConfig.fromApplicationMap(Map.of(ROOT + "operator.enabled", "false"));
        assertThat(perWf.enabled(Workflow.OPERATOR)).isFalse();
        assertThat(perWf.enabled(Workflow.RESIDENT)).isTrue();
        assertThat(perWf.policy(Workflow.OPERATOR).enabled()).isFalse();
        // global off wins over workflow on
        DesktopLivenessConfig off = DesktopLivenessConfig.fromApplicationMap(Map.of(ROOT + "enabled", "false", ROOT + "resident.enabled", "true"));
        assertThat(off.enabled(Workflow.RESIDENT)).isFalse();
    }

    @Test
    void badValuesAreIgnoredAndNeverWeakenThePolicy() {
        Map<String, Object> m = new HashMap<>();
        m.put(ROOT + "passive.threshold", "ninety");
        m.put(ROOT + "pad.hard_threshold", "7");          // out of [0,1]
        m.put(ROOT + "challenge.min", "x");
        m.put(ROOT + "challenge.pool", "DANCE");          // no valid entries
        m.put(ROOT + "active.mode", "SOMETIMES");
        m.put(ROOT + "enabled", "maybe");
        DesktopLivenessConfig c = DesktopLivenessConfig.fromApplicationMap(m);
        LivenessPolicy d = LivenessPolicy.defaults(Workflow.RESIDENT), p = c.policy(Workflow.RESIDENT);
        assertThat(p.passiveThreshold()).isEqualTo(d.passiveThreshold());
        assertThat(p.padHardThreshold()).isEqualTo(d.padHardThreshold());
        assertThat(p.minChallenges()).isEqualTo(d.minChallenges());
        assertThat(p.challengePool()).isEqualTo(d.challengePool());
        assertThat(p.activeMode()).isEqualTo(d.activeMode());
        assertThat(c.enabled(Workflow.RESIDENT)).isTrue();
    }

    @Test
    void nonStringValuesAndLocaleCodesAreHandled() {
        Map<String, Object> m = new HashMap<>();
        m.put(ROOT + "passive.min_frames", 12);
        m.put(ROOT + "diagnostics.enabled", Boolean.TRUE);
        m.put("mosip.registration.application_language", "fra");
        DesktopLivenessConfig c = DesktopLivenessConfig.fromApplicationMap(m);
        assertThat(c.policy(Workflow.RESIDENT).passiveMinFrames()).isEqualTo(12);
        assertThat(c.diagnosticsEnabled()).isTrue();
        assertThat(c.locale()).isEqualTo(Locale.FRENCH);
        assertThat(DesktopLivenessConfig.fromApplicationMap(Map.of(ROOT + "locale", "ara")).locale().getLanguage()).isEqualTo("ar");
        assertThat(DesktopLivenessConfig.fromApplicationMap(Map.of(ROOT + "locale", "eng")).locale()).isEqualTo(Locale.ENGLISH);
    }

    @Test
    void streamOptionsAndDocumentedDefaults() {
        DesktopLivenessConfig c = DesktopLivenessConfig.fromApplicationMap(Map.of(ROOT + "stream.target_fps", "10", ROOT + "stream.stall_timeout_ms", "2500"));
        assertThat(c.streamOptions().targetFps()).isEqualTo(10);
        assertThat(c.streamOptions().stallTimeoutMillis()).isEqualTo(2500);
        assertThat(c.streamOptions().reconnectAttempts()).isEqualTo(5);
        assertThat(DesktopLivenessConfig.documentedDefaults()).containsKeys(ROOT + "enabled", ROOT + "retries", ROOT + "challenge.pool");
    }
}
