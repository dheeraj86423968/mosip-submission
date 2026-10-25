package io.mosip.vivid.engine.image;

/**
 * Pure-Java image operations used by the engine. Everything works on {@link RgbImage} / {@link FloatImage};
 * coordinates are pixels with (0,0) at the top-left and pixel centres at integer positions.
 */
public final class ImageOps {
    private ImageOps() {}

    /** Out-of-bounds handling for crops. */
    public enum Padding { ZERO, CLAMP, REFLECT }

    /** Rec.601 luma, 0..255. */
    public static FloatImage toGray(RgbImage img) {
        int n = img.width() * img.height();
        float[] g = new float[n];
        byte[] d = img.data();
        for (int i = 0, j = 0; i < n; i++, j += 3) {
            g[i] = 0.299f * (d[j] & 0xFF) + 0.587f * (d[j + 1] & 0xFF) + 0.114f * (d[j + 2] & 0xFF);
        }
        return new FloatImage(img.width(), img.height(), g);
    }

    /** Bilinear resize. Downscaling by large factors aliases; faces are downscaled at most ~4x in this engine. */
    public static RgbImage resize(RgbImage src, int w, int h) {
        if (w == src.width() && h == src.height()) return src;
        byte[] out = new byte[w * h * 3];
        byte[] s = src.data();
        int sw = src.width(), sh = src.height();
        float sx = (float) sw / w, sy = (float) sh / h;
        for (int y = 0; y < h; y++) {
            float fy = (y + 0.5f) * sy - 0.5f;
            int y0 = (int) Math.floor(fy); float wy = fy - y0;
            int ya = clampi(y0, 0, sh - 1), yb = clampi(y0 + 1, 0, sh - 1);
            for (int x = 0; x < w; x++) {
                float fx = (x + 0.5f) * sx - 0.5f;
                int x0 = (int) Math.floor(fx); float wx = fx - x0;
                int xa = clampi(x0, 0, sw - 1), xb = clampi(x0 + 1, 0, sw - 1);
                int ia = (ya * sw + xa) * 3, ib = (ya * sw + xb) * 3, ic = (yb * sw + xa) * 3, id = (yb * sw + xb) * 3;
                int o = (y * w + x) * 3;
                for (int c = 0; c < 3; c++) {
                    float top = (s[ia + c] & 0xFF) * (1 - wx) + (s[ib + c] & 0xFF) * wx;
                    float bot = (s[ic + c] & 0xFF) * (1 - wx) + (s[id + c] & 0xFF) * wx;
                    out[o + c] = (byte) Math.round(top * (1 - wy) + bot * wy);
                }
            }
        }
        return new RgbImage(w, h, out);
    }

    /** Bilinear resize of a float raster. */
    public static FloatImage resize(FloatImage src, int w, int h) {
        if (w == src.width() && h == src.height()) return src;
        float[] out = new float[w * h];
        float[] s = src.data();
        int sw = src.width(), sh = src.height();
        float sx = (float) sw / w, sy = (float) sh / h;
        for (int y = 0; y < h; y++) {
            float fy = (y + 0.5f) * sy - 0.5f;
            int y0 = (int) Math.floor(fy); float wy = fy - y0;
            int ya = clampi(y0, 0, sh - 1), yb = clampi(y0 + 1, 0, sh - 1);
            for (int x = 0; x < w; x++) {
                float fx = (x + 0.5f) * sx - 0.5f;
                int x0 = (int) Math.floor(fx); float wx = fx - x0;
                int xa = clampi(x0, 0, sw - 1), xb = clampi(x0 + 1, 0, sw - 1);
                float top = s[ya * sw + xa] * (1 - wx) + s[ya * sw + xb] * wx;
                float bot = s[yb * sw + xa] * (1 - wx) + s[yb * sw + xb] * wx;
                out[y * w + x] = top * (1 - wy) + bot * wy;
            }
        }
        return new FloatImage(w, h, out);
    }

    /** Crops a rectangle that may extend beyond the image; outside pixels follow the padding rule. */
    public static RgbImage crop(RgbImage src, int x0, int y0, int w, int h, Padding pad) {
        if (w <= 0 || h <= 0) throw new IllegalArgumentException("crop size must be positive");
        byte[] out = new byte[w * h * 3];
        byte[] s = src.data();
        int sw = src.width(), sh = src.height();
        for (int y = 0; y < h; y++) {
            int sy = map(y0 + y, sh, pad);
            for (int x = 0; x < w; x++) {
                int sx = map(x0 + x, sw, pad);
                int o = (y * w + x) * 3;
                if (sx < 0 || sy < 0) continue; // ZERO padding
                int i = (sy * sw + sx) * 3;
                out[o] = s[i]; out[o + 1] = s[i + 1]; out[o + 2] = s[i + 2];
            }
        }
        return new RgbImage(w, h, out);
    }

    /** Float-raster crop with padding. */
    public static FloatImage crop(FloatImage src, int x0, int y0, int w, int h, Padding pad) {
        if (w <= 0 || h <= 0) throw new IllegalArgumentException("crop size must be positive");
        float[] out = new float[w * h];
        float[] s = src.data();
        int sw = src.width(), sh = src.height();
        for (int y = 0; y < h; y++) {
            int sy = map(y0 + y, sh, pad);
            for (int x = 0; x < w; x++) {
                int sx = map(x0 + x, sw, pad);
                if (sx < 0 || sy < 0) continue;
                out[y * w + x] = s[sy * sw + sx];
            }
        }
        return new FloatImage(w, h, out);
    }

    /** Square crop of side {@code size} centred on (cx, cy). */
    public static RgbImage cropSquare(RgbImage src, double cx, double cy, double size, Padding pad) {
        int s = Math.max(2, (int) Math.round(size));
        int x0 = (int) Math.round(cx - s / 2.0), y0 = (int) Math.round(cy - s / 2.0);
        return crop(src, x0, y0, s, s, pad);
    }

    /** Square crop around a box centre with the box's longer side scaled by {@code scale} (the usual face-crop recipe). */
    public static RgbImage cropFace(RgbImage src, double boxX, double boxY, double boxW, double boxH, double scale, Padding pad) {
        return cropSquare(src, boxX + boxW / 2, boxY + boxH / 2, Math.max(boxW, boxH) * scale, pad);
    }

    private static int map(int v, int n, Padding pad) {
        if (v >= 0 && v < n) return v;
        switch (pad) {
            case ZERO: return -1;
            case CLAMP: return v < 0 ? 0 : n - 1;
            default: {
                if (n == 1) return 0;
                int period = 2 * (n - 1);
                int m = Math.floorMod(v, period);
                return m < n ? m : period - m; // reflect_101: ...2 1 | 0 1 2 ... n-1 | n-2 n-3 ...
            }
        }
    }

    /**
     * Packs an image into a planar CHW float tensor: {@code out[c*h*w + y*w + x] = (value * scale - mean[c]) / std[c]}.
     *
     * @param bgr  write channels in B,G,R order (OpenCV-trained models) instead of R,G,B
     */
    public static float[] toChw(RgbImage img, boolean bgr, float scale, float[] mean, float[] std) {
        int w = img.width(), h = img.height(), plane = w * h;
        float[] out = new float[3 * plane];
        byte[] d = img.data();
        for (int i = 0, j = 0; i < plane; i++, j += 3) {
            for (int c = 0; c < 3; c++) {
                int srcC = bgr ? 2 - c : c;
                float m = mean == null ? 0f : mean[c];
                float s = std == null ? 1f : std[c];
                out[c * plane + i] = ((d[j + srcC] & 0xFF) * scale - m) / s;
            }
        }
        return out;
    }

    /** Variance of the 3x3 Laplacian response over the interior; the classic blur measure (higher = sharper). */
    public static double laplacianVariance(FloatImage g) {
        int w = g.width(), h = g.height();
        if (w < 3 || h < 3) return 0;
        float[] d = g.data();
        double sum = 0, sum2 = 0; int n = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                double l = d[i - 1] + d[i + 1] + d[i - w] + d[i + w] - 4.0 * d[i];
                sum += l; sum2 += l * l; n++;
            }
        }
        double mean = sum / n;
        return sum2 / n - mean * mean;
    }

    /** {mean, standard deviation} of a float raster. */
    public static double[] meanStd(FloatImage g) {
        return meanStd(g.data());
    }

    public static double[] meanStd(float[] v) {
        double s = 0, s2 = 0;
        for (float x : v) { s += x; s2 += (double) x * x; }
        double mean = s / v.length;
        return new double[] {mean, Math.sqrt(Math.max(0, s2 / v.length - mean * mean))};
    }

    /** Fraction of values at or beyond the dark / bright clipping points. */
    public static double clippedFraction(FloatImage g, float low, float high) {
        int n = 0;
        for (float x : g.data()) if (x <= low || x >= high) n++;
        return (double) n / g.data().length;
    }

    /**
     * Warps {@code src} with a forward affine map {@code m} (2x3, src -> dst, row-major {a,b,tx,c,d,ty}) into a
     * {@code w x h} destination by inverse mapping and bilinear sampling; outside pixels are black.
     */
    public static RgbImage warpAffine(RgbImage src, double[] m, int w, int h) {
        double a = m[0], b = m[1], tx = m[2], c = m[3], d = m[4], ty = m[5];
        double det = a * d - b * c;
        if (Math.abs(det) < 1e-12) throw new IllegalArgumentException("singular affine transform");
        double ia = d / det, ib = -b / det, ic = -c / det, id = a / det;
        byte[] out = new byte[w * h * 3];
        byte[] s = src.data();
        int sw = src.width(), sh = src.height();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double dx = x - tx, dy = y - ty;
                double sx = ia * dx + ib * dy, sy = ic * dx + id * dy;
                int x0 = (int) Math.floor(sx), y0 = (int) Math.floor(sy);
                if (x0 < 0 || y0 < 0 || x0 + 1 >= sw || y0 + 1 >= sh) continue;
                double wx = sx - x0, wy = sy - y0;
                int i00 = (y0 * sw + x0) * 3, i01 = i00 + 3, i10 = i00 + sw * 3, i11 = i10 + 3;
                int o = (y * w + x) * 3;
                for (int ch = 0; ch < 3; ch++) {
                    double v = (s[i00 + ch] & 0xFF) * (1 - wx) * (1 - wy) + (s[i01 + ch] & 0xFF) * wx * (1 - wy)
                            + (s[i10 + ch] & 0xFF) * (1 - wx) * wy + (s[i11 + ch] & 0xFF) * wx * wy;
                    out[o + ch] = (byte) Math.round(v);
                }
            }
        }
        return new RgbImage(w, h, out);
    }

    /** Mean R, G, B over a rectangle clipped to the image; returns {r, g, b, pixelCount}. */
    public static double[] meanRgb(RgbImage img, int x0, int y0, int w, int h) {
        int xa = Math.max(0, x0), ya = Math.max(0, y0), xb = Math.min(img.width(), x0 + w), yb = Math.min(img.height(), y0 + h);
        double r = 0, g = 0, b = 0; int n = 0;
        byte[] d = img.data();
        for (int y = ya; y < yb; y++) {
            int i = (y * img.width() + xa) * 3;
            for (int x = xa; x < xb; x++, i += 3) {
                r += d[i] & 0xFF; g += d[i + 1] & 0xFF; b += d[i + 2] & 0xFF; n++;
            }
        }
        if (n == 0) return new double[] {0, 0, 0, 0};
        return new double[] {r / n, g / n, b / n, n};
    }

    /** RGB (0..255) to HSV with h in degrees [0,360), s and v in [0,1]. */
    public static void rgbToHsv(int r, int g, int b, float[] hsv) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf)), min = Math.min(rf, Math.min(gf, bf));
        float delta = max - min;
        float h;
        if (delta < 1e-6f) h = 0;
        else if (max == rf) h = 60f * (((gf - bf) / delta) % 6f);
        else if (max == gf) h = 60f * ((bf - rf) / delta + 2f);
        else h = 60f * ((rf - gf) / delta + 4f);
        if (h < 0) h += 360f;
        hsv[0] = h; hsv[1] = max < 1e-6f ? 0 : delta / max; hsv[2] = max;
    }

    public static int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
    public static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
    public static double sigmoid(double z) { return 1.0 / (1.0 + Math.exp(-z)); }
}
