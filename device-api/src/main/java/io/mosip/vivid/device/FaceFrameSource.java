package io.mosip.vivid.device;

/**
 * SPI for anything that can deliver face frames: an SBI /stream endpoint, an Android camera,
 * a vendor SDK, or a mock replaying a corpus. Implementations must be safe to start/stop repeatedly.
 */
public interface FaceFrameSource extends AutoCloseable {
    /** Stable identifier of the adapter implementation, e.g. "sbi-stream", "android-camerax", "mock". */
    String adapterId();

    /** Discover capabilities. May block briefly to query the device. */
    DeviceCapabilities capabilities();

    /** Begin delivering frames to the sink on a dedicated thread. Returns immediately. */
    void start(FrameSink sink, StreamOptions options);

    /** Stop delivery. Idempotent. The sink's onEnd is called exactly once per start. */
    void stop();

    DeviceHealth health();

    @Override default void close() { stop(); }
}
