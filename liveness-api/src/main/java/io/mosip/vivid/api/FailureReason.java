package io.mosip.vivid.api;

/** Every way a session can fail. Covers all thirteen error cases named in the problem statement plus security vetoes. */
public enum FailureReason {
    DEVICE_UNAVAILABLE, DEVICE_CONNECTION_FAILURE, CAMERA_UNAVAILABLE,
    FACE_NOT_DETECTED, MULTIPLE_FACES, POOR_FACE_QUALITY,
    LIVENESS_BELOW_THRESHOLD, PAD_DETECTED,
    ACTIVE_CHALLENGE_FAILED, CHALLENGE_TIMEOUT, MAX_RETRIES_EXCEEDED,
    INVALID_FRAME_DATA, ENGINE_ERROR,
    IDENTITY_DRIFT, SESSION_TIMEOUT, CANCELLED, POLICY_DISABLED;

    /** Reasons that must be shown to the user generically, with detail only in audit. */
    public boolean isSecuritySensitive() { return this == PAD_DETECTED || this == IDENTITY_DRIFT; }
}
