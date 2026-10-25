package io.mosip.vivid.engine.image;

import java.util.Objects;

/** Single-channel float raster (grayscale in 0..255 unless stated otherwise), row-major. */
public final class FloatImage {
    private final int width;
    private final int height;
    private final float[] data;

    public FloatImage(int width, int height, float[] data) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("dimensions must be positive");
        Objects.requireNonNull(data);
        if (data.length != width * height) throw new IllegalArgumentException("expected " + (width * height) + " values, got " + data.length);
        this.width = width; this.height = height; this.data = data;
    }

    public FloatImage(int width, int height) { this(width, height, new float[width * height]); }

    public int width() { return width; }
    public int height() { return height; }
    public float[] data() { return data; }
    public float get(int x, int y) { return data[y * width + x]; }
    public void set(int x, int y, float v) { data[y * width + x] = v; }

    @Override public String toString() { return "FloatImage(" + width + "x" + height + ")"; }
}
