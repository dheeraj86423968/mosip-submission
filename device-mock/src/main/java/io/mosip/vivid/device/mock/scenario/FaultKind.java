package io.mosip.vivid.device.mock.scenario;

/**
 * Device faults the mock can inject at a point in scenario time. Each kind maps to a concrete behaviour in
 * both {@code MockFaceFrameSource} (in-process) and {@code MockSbiServer} (over HTTP), described per constant.
 */
public enum FaultKind {
    /**
     * The device goes away. In-process: health becomes DISCONNECTED and no frames are delivered until a
     * {@link #RECONNECT} fault; if none follows, the source ends. Over HTTP: the stream socket is closed and
     * further STREAM requests fail with SBI error 106 until a RECONNECT fault.
     * Param {@code permanent} (boolean, default false) forbids reconnection entirely.
     */
    DISCONNECT,
    /**
     * Frames stop for {@code durationMs} (default 2000) while the connection stays open. In-process: health
     * becomes DEGRADED for the duration, then CONNECTED again. Over HTTP: the socket simply goes quiet.
     */
    STALL,
    /**
     * The next {@code count} (default 1) frames are byte-corrupted: the payload is truncated to
     * {@code fraction} (default 0.5) of its length. For MJPEG this yields undecodable JPEGs; for RGB888 a
     * short buffer that engines must reject as INVALID_FRAME_DATA.
     */
    CORRUPT_FRAME,
    /** Delivery of the next frame is delayed by {@code delayMs} (default 500); later frames arrive late. */
    LATENCY_SPIKE,
    /** Ends a {@link #DISCONNECT}: in-process health goes RECONNECTING then CONNECTED; over HTTP the device accepts STREAM again. */
    RECONNECT
}
