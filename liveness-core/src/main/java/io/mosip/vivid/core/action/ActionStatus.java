package io.mosip.vivid.core.action;

/** Lifecycle of an {@link ActionDetector}. */
public enum ActionStatus {
    /** Prompt issued; no onset observed yet (the subject may still be settling into a neutral pose). */
    WAITING,
    /** Onset observed; the action is under way but not yet complete. */
    IN_PROGRESS,
    /** The action was verified. Terminal. */
    COMPLETED,
    /** The detector concluded the action cannot complete (reserved for detectors with a definitive negative). Terminal. */
    FAILED;

    /** True for {@link #COMPLETED} and {@link #FAILED}. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
