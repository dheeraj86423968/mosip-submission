package io.mosip.vivid.device;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;

/**
 * One frame from a face capture device. Immutable. Frames are never persisted by the engine;
 * callers that keep references must copy the buffers.
 */
public final class FaceFrame {
    private final long sequence;
    private final long timestampNanos;
    private final int width;
    private final int height;
    private final PixelFormat format;
    private final ByteBuffer pixels;
    private final ByteBuffer depth;     // nullable, GRAY16, same dimensions
    private final ByteBuffer ir;        // nullable, GRAY8 or GRAY16, same dimensions
    private final byte[] deviceSignature; // nullable; present only if the device signs frames

    private FaceFrame(Builder b) {
        this.sequence = b.sequence;
        this.timestampNanos = b.timestampNanos;
        this.width = b.width;
        this.height = b.height;
        this.format = Objects.requireNonNull(b.format, "format");
        this.pixels = Objects.requireNonNull(b.pixels, "pixels").asReadOnlyBuffer();
        this.depth = b.depth == null ? null : b.depth.asReadOnlyBuffer();
        this.ir = b.ir == null ? null : b.ir.asReadOnlyBuffer();
        this.deviceSignature = b.deviceSignature == null ? null : b.deviceSignature.clone();
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("frame dimensions must be positive");
    }

    public static Builder builder() { return new Builder(); }

    public long sequence() { return sequence; }
    public long timestampNanos() { return timestampNanos; }
    public int width() { return width; }
    public int height() { return height; }
    public PixelFormat format() { return format; }
    /** Read-only view positioned at 0. */
    public ByteBuffer pixels() { return pixels.duplicate().rewind(); }
    public Optional<ByteBuffer> depth() { return depth == null ? Optional.empty() : Optional.of(depth.duplicate().rewind()); }
    public Optional<ByteBuffer> ir() { return ir == null ? Optional.empty() : Optional.of(ir.duplicate().rewind()); }
    public Optional<byte[]> deviceSignature() { return deviceSignature == null ? Optional.empty() : Optional.of(deviceSignature.clone()); }

    @Override public String toString() {
        return "FaceFrame{seq=" + sequence + ", t=" + timestampNanos + ", " + width + "x" + height + " " + format + "}";
    }

    public static final class Builder {
        private long sequence;
        private long timestampNanos;
        private int width;
        private int height;
        private PixelFormat format;
        private ByteBuffer pixels;
        private ByteBuffer depth;
        private ByteBuffer ir;
        private byte[] deviceSignature;

        public Builder sequence(long v) { this.sequence = v; return this; }
        public Builder timestampNanos(long v) { this.timestampNanos = v; return this; }
        public Builder width(int v) { this.width = v; return this; }
        public Builder height(int v) { this.height = v; return this; }
        public Builder format(PixelFormat v) { this.format = v; return this; }
        public Builder pixels(ByteBuffer v) { this.pixels = v; return this; }
        public Builder pixels(byte[] v) { this.pixels = ByteBuffer.wrap(v); return this; }
        public Builder depth(ByteBuffer v) { this.depth = v; return this; }
        public Builder ir(ByteBuffer v) { this.ir = v; return this; }
        public Builder deviceSignature(byte[] v) { this.deviceSignature = v; return this; }
        public FaceFrame build() { return new FaceFrame(this); }
    }
}
