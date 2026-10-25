package io.mosip.vivid.device;

/** Requested stream parameters. Sources honour them best-effort and report actual values via {@link DeviceCapabilities}. */
public final class StreamOptions {
    private final int targetFps;
    private final int maxQueuedFrames;
    private final long stallTimeoutMillis;
    private final int reconnectAttempts;

    public StreamOptions(int targetFps, int maxQueuedFrames, long stallTimeoutMillis, int reconnectAttempts) {
        this.targetFps = targetFps;
        this.maxQueuedFrames = maxQueuedFrames;
        this.stallTimeoutMillis = stallTimeoutMillis;
        this.reconnectAttempts = reconnectAttempts;
    }

    public static StreamOptions defaults() { return new StreamOptions(15, 4, 3000, 5); }

    public int targetFps() { return targetFps; }
    public int maxQueuedFrames() { return maxQueuedFrames; }
    public long stallTimeoutMillis() { return stallTimeoutMillis; }
    public int reconnectAttempts() { return reconnectAttempts; }
}
