package io.mosip.vivid.device;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/** What a connected device can do. Discovered once per session and used to constrain the engine and challenge pool. */
public final class DeviceCapabilities {
    public enum CertificationLevel { L0, L1, UNKNOWN }

    private final String vendor;
    private final String model;
    private final String deviceId;
    private final String sbiSpecVersion;
    private final CertificationLevel certificationLevel;
    private final int width;
    private final int height;
    private final double nominalFps;
    private final Set<PixelFormat> formats;
    private final boolean hasDepth;
    private final boolean hasIr;
    private final boolean signsFrames;

    private DeviceCapabilities(Builder b) {
        vendor = Objects.requireNonNullElse(b.vendor, "unknown");
        model = Objects.requireNonNullElse(b.model, "unknown");
        deviceId = Objects.requireNonNullElse(b.deviceId, "unknown");
        sbiSpecVersion = Objects.requireNonNullElse(b.sbiSpecVersion, "unknown");
        certificationLevel = Objects.requireNonNullElse(b.certificationLevel, CertificationLevel.UNKNOWN);
        width = b.width; height = b.height; nominalFps = b.nominalFps;
        formats = b.formats == null ? Set.of(PixelFormat.RGB888) : Collections.unmodifiableSet(b.formats);
        hasDepth = b.hasDepth; hasIr = b.hasIr; signsFrames = b.signsFrames;
    }

    public static Builder builder() { return new Builder(); }

    public String vendor() { return vendor; }
    public String model() { return model; }
    public String deviceId() { return deviceId; }
    public String sbiSpecVersion() { return sbiSpecVersion; }
    public CertificationLevel certificationLevel() { return certificationLevel; }
    public int width() { return width; }
    public int height() { return height; }
    public double nominalFps() { return nominalFps; }
    public Set<PixelFormat> formats() { return formats; }
    public boolean hasDepth() { return hasDepth; }
    public boolean hasIr() { return hasIr; }
    public boolean signsFrames() { return signsFrames; }

    public static final class Builder {
        private String vendor, model, deviceId, sbiSpecVersion;
        private CertificationLevel certificationLevel;
        private int width = 640, height = 480;
        private double nominalFps = 15;
        private Set<PixelFormat> formats;
        private boolean hasDepth, hasIr, signsFrames;
        public Builder vendor(String v) { vendor = v; return this; }
        public Builder model(String v) { model = v; return this; }
        public Builder deviceId(String v) { deviceId = v; return this; }
        public Builder sbiSpecVersion(String v) { sbiSpecVersion = v; return this; }
        public Builder certificationLevel(CertificationLevel v) { certificationLevel = v; return this; }
        public Builder resolution(int w, int h) { width = w; height = h; return this; }
        public Builder nominalFps(double v) { nominalFps = v; return this; }
        public Builder formats(Set<PixelFormat> v) { formats = v; return this; }
        public Builder hasDepth(boolean v) { hasDepth = v; return this; }
        public Builder hasIr(boolean v) { hasIr = v; return this; }
        public Builder signsFrames(boolean v) { signsFrames = v; return this; }
        public DeviceCapabilities build() { return new DeviceCapabilities(this); }
    }
}
