package io.mosip.vivid.desktop;

import io.mosip.vivid.api.Challenge;
import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.FailureReason;
import io.mosip.vivid.api.SessionState;
import io.mosip.vivid.device.DeviceHealth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class LivenessMessagesTest {

    private static ResourceBundle bundle(String lang) {
        return ResourceBundle.getBundle(LivenessMessages.BUNDLE, new Locale(lang), ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    @ParameterizedTest @EnumSource(SessionState.Hint.class)
    void everyHintHasAKey(SessionState.Hint h) {
        assertThat(LivenessMessages.keyForHint(h)).startsWith("liveness.hint.");
        assertThat(bundle("en").containsKey(LivenessMessages.keyForHint(h))).as(h.name()).isTrue();
    }

    @ParameterizedTest @EnumSource(ChallengeType.class)
    void everyChallengeHasAKey(ChallengeType c) {
        assertThat(LivenessMessages.keyForChallenge(c)).startsWith("liveness.challenge.");
        assertThat(bundle("en").getString(LivenessMessages.keyForChallenge(c))).isNotBlank();
    }

    @ParameterizedTest @EnumSource(FailureReason.class)
    void everyFailureHasAKey(FailureReason f) {
        String key = LivenessMessages.keyForFailure(f);
        assertThat(key).startsWith("liveness.failure.");
        assertThat(bundle("en").getString(key)).isNotBlank();
    }

    @ParameterizedTest @EnumSource(SessionState.Phase.class)
    void everyPhaseHasAKey(SessionState.Phase p) {
        assertThat(bundle("en").getString(LivenessMessages.keyForPhase(p))).isNotNull();
    }

    @ParameterizedTest @EnumSource(DeviceHealth.State.class)
    void everyDeviceStateHasAKey(DeviceHealth.State s) {
        assertThat(bundle("en").getString(LivenessMessages.keyForDevice(s))).isNotBlank();
    }

    @Test
    void securitySensitiveReasonsMapToTheGenericMessage() {
        for (FailureReason f : FailureReason.values()) {
            if (f.isSecuritySensitive()) assertThat(LivenessMessages.keyForFailure(f)).isEqualTo(LivenessMessages.GENERIC_FAILURE);
        }
        assertThat(LivenessMessages.keyForFailure(FailureReason.PAD_DETECTED)).isEqualTo(LivenessMessages.GENERIC_FAILURE);
        assertThat(LivenessMessages.keyForFailure(FailureReason.IDENTITY_DRIFT)).isEqualTo(LivenessMessages.GENERIC_FAILURE);
        assertThat(LivenessMessages.keyForFailure(null)).isEqualTo(LivenessMessages.GENERIC_FAILURE);
        String generic = bundle("en").getString(LivenessMessages.GENERIC_FAILURE);
        assertThat(generic).isEqualTo("Face verification could not be completed. Please try again.");
        assertThat(generic.toLowerCase(Locale.ROOT)).doesNotContain("attack", "spoof", "fake", "identity");
    }

    @Test
    void problemStatementTextsArePresentInEnglish() {
        ResourceBundle en = bundle("en");
        assertThat(en.getString(LivenessMessages.CHECKING)).isEqualTo("Checking face liveness…");
        assertThat(en.getString(LivenessMessages.keyForChallenge(ChallengeType.BLINK))).isEqualTo("Please blink");
        assertThat(en.getString(LivenessMessages.keyForChallenge(ChallengeType.SMILE))).isEqualTo("Please smile");
        assertThat(en.getString(LivenessMessages.keyForChallenge(ChallengeType.TURN_LEFT))).isEqualTo("Please turn your head to the left");
        assertThat(en.getString(LivenessMessages.keyForHint(SessionState.Hint.PLEASE_CONTINUE))).isEqualTo("Please continue");
        assertThat(en.getString(LivenessMessages.keyForHint(SessionState.Hint.ACTION_DETECTED))).isEqualTo("Action detected");
        assertThat(en.getString(LivenessMessages.keyForHint(SessionState.Hint.HOLD_STILL))).isEqualTo("Hold still");
        assertThat(en.getString(LivenessMessages.CAPTURED_OK)).isEqualTo("Good, face captured successfully");
        assertThat(en.getString(LivenessMessages.LIVENESS_FAILURE)).isEqualTo("We could not verify face liveness. Please try again.");
        assertThat(en.getString(LivenessMessages.keyForFailure(FailureReason.LIVENESS_BELOW_THRESHOLD))).isEqualTo("We could not verify face liveness. Please try again.");
    }

    @Test
    void allBundlesContainEveryKeyAndNothingIsMissingInTranslations() {
        Set<String> expected = LivenessMessages.allKeys();
        for (String lang : new String[] { "en", "fr", "ar" }) {
            ResourceBundle b = bundle(lang);
            Set<String> missing = new TreeSet<>();
            for (String k : expected) if (!b.containsKey(k)) missing.add(k);
            assertThat(missing).as("missing in liveness_" + lang).isEmpty();
            Set<String> extra = new TreeSet<>(b.keySet());
            extra.removeAll(expected);
            assertThat(extra).as("unused keys in liveness_" + lang).isEmpty();
        }
    }

    @Test
    void primaryKeyPrefersChallengeThenHintThenPhase() {
        Challenge c = new Challenge(ChallengeType.SMILE, 0, 2, 0, 8000);
        SessionState s1 = new SessionState(SessionState.Phase.ACTIVE_CHALLENGE, SessionState.Hint.NONE, c, null, 1, 3, 0.5, 0.7, 8000);
        assertThat(LivenessMessages.primaryKey(s1)).isEqualTo("liveness.challenge.smile");
        SessionState s2 = new SessionState(SessionState.Phase.ACTIVE_CHALLENGE, SessionState.Hint.ACTION_DETECTED, c, null, 1, 3, 0.5, 0.7, 8000);
        assertThat(LivenessMessages.primaryKey(s2)).isEqualTo("liveness.hint.action_detected");
        SessionState s3 = new SessionState(SessionState.Phase.FAILED, SessionState.Hint.TRY_AGAIN, null, FailureReason.PAD_DETECTED, 1, 3, 0, 0.1, 0);
        assertThat(LivenessMessages.primaryKey(s3)).isEqualTo(LivenessMessages.GENERIC_FAILURE);
        SessionState s4 = new SessionState(SessionState.Phase.PASSIVE_EVALUATING, SessionState.Hint.NONE, null, null, 1, 3, 0.2, 0.5, 0);
        assertThat(LivenessMessages.primaryKey(s4)).isEqualTo("liveness.state.passive_evaluating");
    }
}
