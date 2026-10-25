package io.mosip.vivid.core.action;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.FaceObservation;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Watches for distinctive pool actions other than the one requested during an active challenge.
 * <p>
 * Challenge-response alone can be passed by a gesture-rich recording whose own schedule happens to put the
 * requested gesture inside the response window. Such a recording almost always performs other gestures too, while a
 * person following the prompt does not. This monitor runs a detector for every other distinctive action in the pool
 * and reports the first one that completes.
 * <p>
 * Exempt: blinks and holding still (people do both without being asked), the requested action itself, and actions
 * that a person commonly produces while performing the requested one (a smile while opening the mouth, any pitch
 * movement while nodding or looking up or down).
 */
public final class UnpromptedActionMonitor {
    private static final Set<ChallengeType> SPONTANEOUS = EnumSet.of(ChallengeType.BLINK, ChallengeType.BLINK_TWICE, ChallengeType.HOLD_STILL);
    private static final Set<ChallengeType> MOUTH = EnumSet.of(ChallengeType.SMILE, ChallengeType.OPEN_MOUTH);
    private static final Set<ChallengeType> PITCH = EnumSet.of(ChallengeType.NOD, ChallengeType.LOOK_UP, ChallengeType.LOOK_DOWN);

    private final Map<ChallengeType, ActionDetector> sentinels = new EnumMap<>(ChallengeType.class);

    /**
     * @param requested the action the subject was asked to perform
     * @param pool      the challenge pool in force; only these actions are watched
     */
    public UnpromptedActionMonitor(ChallengeType requested, Set<ChallengeType> pool, ActionThresholds thresholds, long issuedAtNanos) {
        Objects.requireNonNull(requested, "requested");
        for (ChallengeType t : pool) {
            if (watched(requested, t)) {
                sentinels.put(t, ActionDetectors.create(t, thresholds, issuedAtNanos));
            }
        }
    }

    /**
     * True for actions people perform without being asked (blinking, holding still). A recording contains them
     * too, so they prove nothing against a replay and do not count toward a policy's minimum challenge count.
     */
    public static boolean spontaneous(ChallengeType type) {
        return SPONTANEOUS.contains(type);
    }

    /** True if completing {@code other} while {@code requested} is pending counts as an unprompted action. */
    static boolean watched(ChallengeType requested, ChallengeType other) {
        if (other == requested || SPONTANEOUS.contains(other)) return false;
        if (MOUTH.contains(requested) && MOUTH.contains(other)) return false;
        if (PITCH.contains(requested) && PITCH.contains(other)) return false;
        return true;
    }

    /** The actions being watched. */
    public Set<ChallengeType> watching() {
        return sentinels.isEmpty() ? EnumSet.noneOf(ChallengeType.class) : EnumSet.copyOf(sentinels.keySet());
    }

    /** Feeds one observation to every sentinel; returns the first unprompted action that completed, if any. */
    public Optional<ChallengeType> update(FaceObservation face, long nowNanos) {
        for (Map.Entry<ChallengeType, ActionDetector> e : sentinels.entrySet()) {
            if (e.getValue().update(face, nowNanos) == ActionStatus.COMPLETED) {
                return Optional.of(e.getKey());
            }
        }
        return Optional.empty();
    }
}
