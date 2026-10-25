package io.mosip.vivid.device.sbi;

import java.util.Objects;
import java.util.Optional;

/**
 * Static configuration of {@link SbiStreamFrameSource}: where to look for devices, which one to use and how
 * to time out. Dynamic per-start parameters (fps, queue depth, stall timeout, reconnect attempts) come from
 * {@link io.mosip.vivid.device.StreamOptions}.
 */
public final class SbiStreamConfig {
    private final String host;
    private final int portFrom;
    private final int portTo;
    private final SbiEndpoint endpoint;
    private final String deviceId;
    private final Integer deviceSubId;
    private final boolean preferRegistration;
    private final int connectTimeoutMs;
    private final int discoveryReadTimeoutMs;
    private final int discoveryParallelism;
    private final long streamRequestTimeoutMs;
    private final long backoffInitialMs;
    private final long backoffMaxMs;
    private final SignedPreview signedPreview;
    private final String trustedDeviceKey;

    /** Use of the signed preview-stream extension (docs/design/02-frame-streaming.md §6). */
    public enum SignedPreview {
        /** Never request it; the stream is unsigned (SBI 0.9.5 behaviour). */
        OFF,
        /** Request it when the device advertises {@code streamSigning} in {@code /info}. */
        IF_SUPPORTED,
        /** Require it: a device that does not sign makes every frame count as unsigned, i.e. injected. */
        REQUIRED
    }

    private SbiStreamConfig(Builder b) {
        host = b.host; portFrom = b.portFrom; portTo = b.portTo; endpoint = b.endpoint; deviceId = b.deviceId;
        deviceSubId = b.deviceSubId; preferRegistration = b.preferRegistration; connectTimeoutMs = b.connectTimeoutMs;
        discoveryReadTimeoutMs = b.discoveryReadTimeoutMs; discoveryParallelism = b.discoveryParallelism;
        streamRequestTimeoutMs = b.streamRequestTimeoutMs; backoffInitialMs = b.backoffInitialMs; backoffMaxMs = b.backoffMaxMs;
        signedPreview = b.signedPreview; trustedDeviceKey = b.trustedDeviceKey;
        if (portFrom > portTo) throw new IllegalArgumentException("portFrom > portTo");
        if (backoffInitialMs <= 0 || backoffMaxMs < backoffInitialMs) throw new IllegalArgumentException("bad backoff range");
    }

    public static Builder builder() { return new Builder(); }
    /** Scan 127.0.0.1:4501-4600, first Ready registration face device. */
    public static SbiStreamConfig defaults() { return builder().build(); }

    public String host() { return host; }
    public int portFrom() { return portFrom; }
    public int portTo() { return portTo; }
    /** If set, discovery is skipped and only this endpoint is probed. */
    public Optional<SbiEndpoint> endpoint() { return Optional.ofNullable(endpoint); }
    /** Preferred deviceId (or deviceCode); empty = first available. */
    public Optional<String> deviceId() { return Optional.ofNullable(deviceId); }
    /** Sub-id to stream; empty = the device's first advertised sub-id (usually 0 for face). */
    public Optional<Integer> deviceSubId() { return Optional.ofNullable(deviceSubId); }
    public boolean preferRegistration() { return preferRegistration; }
    public int connectTimeoutMs() { return connectTimeoutMs; }
    public int discoveryReadTimeoutMs() { return discoveryReadTimeoutMs; }
    public int discoveryParallelism() { return discoveryParallelism; }
    /** Value of the {@code timeout} field sent with STREAM (how long the device streams before closing). */
    public long streamRequestTimeoutMs() { return streamRequestTimeoutMs; }
    public long backoffInitialMs() { return backoffInitialMs; }
    public long backoffMaxMs() { return backoffMaxMs; }
    public SignedPreview signedPreview() { return signedPreview; }
    /** Pinned device public key (base64 SPKI); when absent the key advertised in {@code /info} is trusted on first use. */
    public Optional<String> trustedDeviceKey() { return Optional.ofNullable(trustedDeviceKey); }

    /** Builder for {@link SbiStreamConfig}. */
    public static final class Builder {
        private String host = SbiVerbs.HOST;
        private int portFrom = SbiVerbs.PORT_MIN, portTo = SbiVerbs.PORT_MAX;
        private SbiEndpoint endpoint; private String deviceId; private Integer deviceSubId;
        private boolean preferRegistration = true;
        private int connectTimeoutMs = 1000, discoveryReadTimeoutMs = 1500, discoveryParallelism = 16;
        private long streamRequestTimeoutMs = 5 * 60 * 1000L, backoffInitialMs = 250, backoffMaxMs = 5000;
        private SignedPreview signedPreview = SignedPreview.OFF; private String trustedDeviceKey;

        public Builder host(String v) { host = Objects.requireNonNull(v); return this; }
        public Builder portRange(int from, int to) { portFrom = from; portTo = to; return this; }
        public Builder endpoint(SbiEndpoint v) { endpoint = v; return this; }
        public Builder deviceId(String v) { deviceId = v; return this; }
        public Builder deviceSubId(Integer v) { deviceSubId = v; return this; }
        public Builder preferRegistration(boolean v) { preferRegistration = v; return this; }
        public Builder connectTimeoutMs(int v) { connectTimeoutMs = v; return this; }
        public Builder discoveryReadTimeoutMs(int v) { discoveryReadTimeoutMs = v; return this; }
        public Builder discoveryParallelism(int v) { discoveryParallelism = v; return this; }
        public Builder streamRequestTimeoutMs(long v) { streamRequestTimeoutMs = v; return this; }
        public Builder backoff(long initialMs, long maxMs) { backoffInitialMs = initialMs; backoffMaxMs = maxMs; return this; }
        public Builder signedPreview(SignedPreview v) { signedPreview = Objects.requireNonNull(v); return this; }
        public Builder trustedDeviceKey(String base64Spki) { trustedDeviceKey = base64Spki; return this; }
        public SbiStreamConfig build() { return new SbiStreamConfig(this); }
    }
}
