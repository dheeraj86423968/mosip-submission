package io.mosip.vivid.device;

import java.util.Objects;

/** Current connection state of a {@link FaceFrameSource}. */
public final class DeviceHealth {
    public enum State { CONNECTING, CONNECTED, DEGRADED, RECONNECTING, DISCONNECTED, FAILED }

    private final State state;
    private final String reason;

    private DeviceHealth(State state, String reason) { this.state = state; this.reason = reason; }

    public static DeviceHealth connecting() { return new DeviceHealth(State.CONNECTING, ""); }
    public static DeviceHealth connected() { return new DeviceHealth(State.CONNECTED, ""); }
    public static DeviceHealth degraded(String reason) { return new DeviceHealth(State.DEGRADED, Objects.requireNonNull(reason)); }
    public static DeviceHealth reconnecting(String reason) { return new DeviceHealth(State.RECONNECTING, Objects.requireNonNull(reason)); }
    public static DeviceHealth disconnected(String reason) { return new DeviceHealth(State.DISCONNECTED, Objects.requireNonNull(reason)); }
    public static DeviceHealth failed(String reason) { return new DeviceHealth(State.FAILED, Objects.requireNonNull(reason)); }

    public State state() { return state; }
    public String reason() { return reason; }
    public boolean isUsable() { return state == State.CONNECTED || state == State.DEGRADED; }

    @Override public String toString() { return state + (reason.isEmpty() ? "" : "(" + reason + ")"); }
}
