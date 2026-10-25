package io.mosip.vivid.engine.image;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.Objects;

/**
 * 8-bit interleaved RGB raster, row-major, no padding: {@code data[(y * width + x) * 3 + c]} with c = 0 (R), 1 (G), 2 (B).
 * Immutable by convention; callers must not modify {@link #data()} after construction.
 */
public final class RgbImage {
    private final int width;
    private final int height;
    private final byte[] data;

    public RgbImage(int width, int height, byte[] data) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("dimensions must be positive");
        Objects.requireNonNull(data, "data");
        if (data.length != width * height * 3) {
            throw new IllegalArgumentException("expected " + (width * height * 3) + " bytes for " + width + "x" + height + " RGB, got " + data.length);
        }
        this.width = width; this.height = height; this.data = data;
    }

    public int width() { return width; }
    public int height() { return height; }
    /** Backing array; treat as read-only. */
    public byte[] data() { return data; }

    public int index(int x, int y) { return (y * width + x) * 3; }
    public int r(int x, int y) { return data[index(x, y)] & 0xFF; }
    public int g(int x, int y) { return data[index(x, y) + 1] & 0xFF; }
    public int b(int x, int y) { return data[index(x, y) + 2] & 0xFF; }

    /** Copies any {@link BufferedImage} into RGB; fast path for {@code TYPE_3BYTE_BGR}. */
    public static RgbImage fromBufferedImage(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        byte[] out = new byte[w * h * 3];
        if (img.getType() == BufferedImage.TYPE_3BYTE_BGR && img.getRaster().getDataBuffer() instanceof DataBufferByte) {
            byte[] src = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
            if (src.length == out.length) {
                for (int i = 0; i < out.length; i += 3) {
                    out[i] = src[i + 2]; out[i + 1] = src[i + 1]; out[i + 2] = src[i];
                }
                return new RgbImage(w, h, out);
            }
        }
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0, j = 0; i < argb.length; i++, j += 3) {
            int p = argb[i];
            out[j] = (byte) (p >> 16); out[j + 1] = (byte) (p >> 8); out[j + 2] = (byte) p;
        }
        return new RgbImage(w, h, out);
    }

    /** Converts to a {@code TYPE_3BYTE_BGR} image (debugging, self-test image export). */
    public BufferedImage toBufferedImage() {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        byte[] dst = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < data.length; i += 3) {
            dst[i] = data[i + 2]; dst[i + 1] = data[i + 1]; dst[i + 2] = data[i];
        }
        return img;
    }

    @Override public String toString() { return "RgbImage(" + width + "x" + height + ")"; }
}
