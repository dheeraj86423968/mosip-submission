package io.mosip.vivid.device;

/** Raised by sources for discovery and connection failures. Maps onto FailureReason.DEVICE_* in the liveness API. */
public class DeviceException extends RuntimeException {
    public enum Kind { UNAVAILABLE, CONNECTION_FAILED, CAMERA_UNAVAILABLE, PROTOCOL_ERROR, INVALID_FRAME }
    private final Kind kind;
    public DeviceException(Kind kind, String message) { super(message); this.kind = kind; }
    public DeviceException(Kind kind, String message, Throwable cause) { super(message, cause); this.kind = kind; }
    public Kind kind() { return kind; }
}
