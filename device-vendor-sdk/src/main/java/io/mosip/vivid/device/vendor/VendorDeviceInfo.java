package io.mosip.vivid.device.vendor;

import io.mosip.vivid.device.DeviceCapabilities;
import io.mosip.vivid.device.PixelFormat;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** What a vendor SDK reports about an opened device; mapped onto {@link DeviceCapabilities}. */
public final class VendorDeviceInfo {
    private final String vendor, model, serial, sbiSpecVersion;
    private final DeviceCapabilities.CertificationLevel certification;
    private final int width, height;
    private final double fps;
    private final Set<PixelFormat> formats;
    private final boolean hasDepth, hasIr, signsFrames;

    private VendorDeviceInfo(Builder b) {
        vendor = b.vendor; model = b.model; serial = b.serial; sbiSpecVersion = b.sbiSpecVersion;
        certification = b.certification; width = b.width; height = b.height; fps = b.fps;
        formats = Collections.unmodifiableSet(EnumSet.copyOf(b.formats));
        hasDepth = b.hasDepth; hasIr = b.hasIr; signsFrames = b.signsFrames;
    }

    public static Builder builder(String vendor, String model) { return new Builder(vendor, model); }

    public String vendor() { return vendor; }
    public String model() { return model; }
    public String serial() { return serial; }
    public Set<PixelFormat> formats() { return formats; }

    /** The device-api view. {@code sbiSpecVersion} is empty for non-SBI devices. */
    public DeviceCapabilities toCapabilities() {
        return DeviceCapabilities.builder().vendor(vendor).model(model).deviceId(serial).sbiSpecVersion(sbiSpecVersion)
                .certificationLevel(certification).resolution(width, height).nominalFps(fps).formats(formats)
                .hasDepth(hasDepth).hasIr(hasIr).signsFrames(signsFrames).build();
    }

    @Override public String toString() { return vendor + " " + model + " (" + serial + ") " + width + "x" + height + "@" + fps + " " + formats; }

    public static final class Builder {
        private final String vendor, model;
        private String serial = "", sbiSpecVersion = "";
        private DeviceCapabilities.CertificationLevel certification = DeviceCapabilities.CertificationLevel.UNKNOWN;
        private int width, height;
        private double fps;
        private Set<PixelFormat> formats = EnumSet.of(PixelFormat.RGB888);
        private boolean hasDepth, hasIr, signsFrames;

        Builder(String vendor, String model) { this.vendor = Objects.requireNonNull(vendor); this.model = Objects.requireNonNull(model); }

        public Builder serial(String v) { serial = Objects.requireNonNull(v); return this; }
        /** Only if the device is also certified under an SBI version. */
        public Builder sbiSpecVersion(String v) { sbiSpecVersion = Objects.requireNonNull(v); return this; }
        public Builder certification(DeviceCapabilities.CertificationLevel v) { certification = Objects.requireNonNull(v); return this; }
        /** Preview resolution; 0x0 if the SDK only knows it once frames flow. */
        public Builder resolution(int w, int h) { width = w; height = h; return this; }
        public Builder fps(double v) { fps = v; return this; }
        /** Every format {@code VendorFrame}s will use; must not be empty. */
        public Builder formats(Set<PixelFormat> v) {
            if (v.isEmpty()) throw new IllegalArgumentException("formats must not be empty");
            formats = EnumSet.copyOf(v); return this;
        }
        public Builder hasDepth(boolean v) { hasDepth = v; return this; }
        public Builder hasIr(boolean v) { hasIr = v; return this; }
        /** True only if the SDK attaches a verifiable per-frame signature (none does today). */
        public Builder signsFrames(boolean v) { signsFrames = v; return this; }
        public VendorDeviceInfo build() { return new VendorDeviceInfo(this); }
    }
}
