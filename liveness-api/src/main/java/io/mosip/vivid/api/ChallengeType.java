package io.mosip.vivid.api;

/** Active liveness challenges. Engines advertise which they support via EngineInfo; policy selects a pool. */
public enum ChallengeType {
    BLINK(false, false), BLINK_TWICE(false, false), SMILE(false, false), OPEN_MOUTH(false, false), RAISE_EYEBROWS(false, false),
    TURN_LEFT(true, true), TURN_RIGHT(true, true), LOOK_UP(true, true), LOOK_DOWN(true, true), NOD(true, true),
    HOLD_STILL(false, false);

    private final boolean revealsDepth;
    private final boolean requiresMotorAbility;
    ChallengeType(boolean revealsDepth, boolean requiresMotorAbility) {
        this.revealsDepth = revealsDepth; this.requiresMotorAbility = requiresMotorAbility;
    }
    /** True if the motion produces parallax usable by a 3D-structure signal. */
    public boolean revealsDepth() { return revealsDepth; }
    /** True if the challenge needs head movement; excluded from the accessibility pool. */
    public boolean requiresMotorAbility() { return requiresMotorAbility; }
}
