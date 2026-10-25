package io.mosip.vivid.device.vendor;

import io.mosip.vivid.device.PixelFormat;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * One preview frame as a vendor SDK hands it over. The buffers must belong to this frame: if the SDK reuses its
 * buffer after the callback returns, copy it first ({@link #copyOf}).
 */
public final class VendorFrame {
    private final int width, height;
    private final PixelFormat format;
    private final ByteBuffer pixels, depth, ir;

    private VendorFrame(int width, int height, PixelFormat format, ByteBuffer pixels, ByteBuffer depth, ByteBuffer ir) {
        this.width = width; this.height = height; this.format = Objects.requireNonNull(format);
        this.pixels = Objects.requireNonNull(pixels); this.depth = depth; this.ir = ir;
    }

    /** A colour frame whose buffer the adapter may keep. */
    public static VendorFrame of(int width, int height, PixelFormat format, byte[] pixels) {
        return new VendorFrame(width, height, format, ByteBuffer.wrap(pixels), null, null);
    }

    /** A colour frame copied out of an SDK-owned buffer (from its position to its limit). */
    public static VendorFrame copyOf(int width, int height, PixelFormat format, ByteBuffer sdkOwned) {
        return new VendorFrame(width, height, format, copy(sdkOwned), null, null);
    }

    /** Adds a GRAY16 depth plane (little-endian unless the engine is configured otherwise); kept as given. */
    public VendorFrame withDepth(ByteBuffer depthPlane) { return new VendorFrame(width, height, format, pixels, depthPlane, ir); }
    /** Adds an infrared plane (GRAY8 or GRAY16); kept as given. */
    public VendorFrame withIr(ByteBuffer irPlane) { return new VendorFrame(width, height, format, pixels, depth, irPlane); }

    public int width() { return width; }
    public int height() { return height; }
    public PixelFormat format() { return format; }
    public ByteBuffer pixels() { return pixels; }
    /** Depth plane or null. */
    public ByteBuffer depth() { return depth; }
    /** IR plane or null. */
    public ByteBuffer ir() { return ir; }

    private static ByteBuffer copy(ByteBuffer src) {
        ByteBuffer d = src.duplicate();
        byte[] out = new byte[d.remaining()];
        d.get(out);
        return ByteBuffer.wrap(out);
    }
}
