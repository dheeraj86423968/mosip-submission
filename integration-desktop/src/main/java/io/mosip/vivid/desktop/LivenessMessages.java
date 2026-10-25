package io.mosip.vivid.desktop;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.FailureReason;
import io.mosip.vivid.api.SessionState;
import io.mosip.vivid.device.DeviceHealth;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Maps every enum the UI can receive to an i18n <em>key</em> in {@code i18n/liveness_*.properties}.
 * No user-visible text lives in code. Security-sensitive failures ({@link FailureReason#isSecuritySensitive()})
 * always map to {@link #GENERIC_FAILURE}; the detailed reason goes to audit only.
 */
public final class LivenessMessages {
    private LivenessMessages() {}

    public static final String BUNDLE = "i18n.liveness";
    public static final String PREFIX = "liveness.";

    /** "Face verification could not be completed. Please try again." */
    public static final String GENERIC_FAILURE = "liveness.failure.generic";
    /** "We could not verify face liveness. Please try again." */
    public static final String LIVENESS_FAILURE = "liveness.failure.liveness";
    public static final String CHECKING = "liveness.hint.checking_liveness";
    public static final String CAPTURED_OK = "liveness.hint.captured_ok";
    public static final String RETRY_COUNTER = "liveness.retry.counter";
    public static final String CHALLENGE_PROGRESS = "liveness.challenge.progress";
    public static final String LOCKED_TITLE = "liveness.locked.title";
    public static final String LOCKED_GUIDANCE = "liveness.locked.guidance";
    public static final String DIAGNOSTICS_TITLE = "liveness.diagnostics.title";
    public static final String DIAGNOSTICS_TOGGLE = "liveness.diagnostics.toggle";
    public static final String ONLINE = "liveness.network.online";
    public static final String OFFLINE = "liveness.network.offline";
    public static final String FAILURE_GUIDANCE = "liveness.failure.guidance";
    public static final String CANCEL = "liveness.action.cancel";
    public static final String RETRY_ACTION = "liveness.action.retry";
    /** Spoken-code challenge prompt (liveness-av); argument {0} is the code with spaced digits, e.g. "4 8 1 7". */
    public static final String SPEAK_CODE = "liveness.challenge.speak_code";

    private static final Map<SessionState.Phase, String> PHASES = new EnumMap<>(SessionState.Phase.class);
    private static final Map<SessionState.Hint, String> HINTS = new EnumMap<>(SessionState.Hint.class);
    private static final Map<ChallengeType, String> CHALLENGES = new EnumMap<>(ChallengeType.class);
    private static final Map<FailureReason, String> FAILURES = new EnumMap<>(FailureReason.class);
    private static final Map<DeviceHealth.State, String> DEVICE = new EnumMap<>(DeviceHealth.State.class);

    static {
        for (SessionState.Phase p : SessionState.Phase.values()) PHASES.put(p, PREFIX + "state." + lower(p.name()));
        for (SessionState.Hint h : SessionState.Hint.values()) HINTS.put(h, PREFIX + "hint." + lower(h.name()));
        for (ChallengeType c : ChallengeType.values()) CHALLENGES.put(c, PREFIX + "challenge." + lower(c.name()));
        for (DeviceHealth.State s : DeviceHealth.State.values()) DEVICE.put(s, PREFIX + "device." + lower(s.name()));
        for (FailureReason f : FailureReason.values()) {
            if (f.isSecuritySensitive()) {
                FAILURES.put(f, GENERIC_FAILURE);
            } else if (f == FailureReason.LIVENESS_BELOW_THRESHOLD || f == FailureReason.ACTIVE_CHALLENGE_FAILED
                    || f == FailureReason.CHALLENGE_TIMEOUT) {
                FAILURES.put(f, LIVENESS_FAILURE);
            } else {
                FAILURES.put(f, PREFIX + "failure." + lower(f.name()));
            }
        }
    }

    private static String lower(String s) { return s.toLowerCase(java.util.Locale.ROOT); }

    public static String keyForPhase(SessionState.Phase phase) { return PHASES.get(phase); }
    public static String keyForHint(SessionState.Hint hint) { return HINTS.get(hint); }
    public static String keyForChallenge(ChallengeType type) { return CHALLENGES.get(type); }
    public static String keyForDevice(DeviceHealth.State state) { return DEVICE.get(state); }

    /** Never returns a key that reveals a PAD or identity-drift decision. */
    public static String keyForFailure(FailureReason reason) {
        if (reason == null) return GENERIC_FAILURE;
        return FAILURES.get(reason);
    }

    /** The primary text key for a snapshot: challenge prompt if active, otherwise the hint, otherwise the phase. */
    public static String primaryKey(SessionState state) {
        if (state.phase() == SessionState.Phase.ACTIVE_CHALLENGE && state.challenge().isPresent()
                && state.hint() == SessionState.Hint.NONE) {
            return keyForChallenge(state.challenge().get().type());
        }
        if (state.phase() == SessionState.Phase.FAILED) return keyForFailure(state.failure().orElse(null));
        if (state.hint() != SessionState.Hint.NONE) return keyForHint(state.hint());
        return keyForPhase(state.phase());
    }

    /** Every key this module can emit; used by the completeness test and by bundle validation at startup. */
    public static Set<String> allKeys() {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(PHASES.values()); keys.addAll(HINTS.values()); keys.addAll(CHALLENGES.values());
        keys.addAll(FAILURES.values()); keys.addAll(DEVICE.values());
        keys.add(GENERIC_FAILURE); keys.add(LIVENESS_FAILURE); keys.add(RETRY_COUNTER); keys.add(CHALLENGE_PROGRESS);
        keys.add(LOCKED_TITLE); keys.add(LOCKED_GUIDANCE); keys.add(DIAGNOSTICS_TITLE); keys.add(DIAGNOSTICS_TOGGLE);
        keys.add(ONLINE); keys.add(OFFLINE); keys.add(FAILURE_GUIDANCE); keys.add(CANCEL); keys.add(RETRY_ACTION);
        keys.add(SPEAK_CODE);
        return Collections.unmodifiableSet(keys);
    }
}
