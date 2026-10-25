package io.mosip.vivid.testkit.redteam;

import java.awt.image.BufferedImage;

/**
 * Linear float RGB image (interleaved, nominal range [0,1]) used by the red-team renderer. Plain loops only, no
 * Java2D resampling, so the output depends on nothing but the inputs and the arithmetic below (deterministic across
 * JVMs and platforms).
 */
final class FImg {
    final int w, h;
    final float[] p;

    FImg(int w, int h) { this.w = w; this.h = h; this.p = new float[w * h * 3]; }

    static FImg of(BufferedImage img) {
        FImg f = new FImg(img.getWidth(), img.getHeight());
        int[] row = new int[f.w];
        int[] direct = img.getType() == BufferedImage.TYPE_INT_RGB && img.getRaster().getDataBuffer() instanceof java.awt.image.DataBufferInt db
                && img.getRaster().getParent() == null ? db.getData() : null;
        for (int y = 0; y < f.h; y++) {
            if (direct != null) System.arraycopy(direct, y * f.w, row, 0, f.w);
            else img.getRGB(0, y, f.w, 1, row, 0, f.w);
            for (int x = 0, i = y * f.w * 3; x < f.w; x++, i += 3) {
                int v = row[x];
                f.p[i] = ((v >> 16) & 0xff) / 255f;
                f.p[i + 1] = ((v >> 8) & 0xff) / 255f;
                f.p[i + 2] = (v & 0xff) / 255f;
            }
        }
        return f;
    }

    /** Bilinear sample with edge clamping into {@code out[0..2]}. */
    void sample(double x, double y, float[] out) {
        if (x < 0) x = 0; else if (x > w - 1) x = w - 1;
        if (y < 0) y = 0; else if (y > h - 1) y = h - 1;
        int x0 = (int) x, y0 = (int) y;
        int x1 = Math.min(x0 + 1, w - 1), y1 = Math.min(y0 + 1, h - 1);
        float fx = (float) (x - x0), fy = (float) (y - y0);
        int a = (y0 * w + x0) * 3, b = (y0 * w + x1) * 3, c = (y1 * w + x0) * 3, d = (y1 * w + x1) * 3;
        for (int k = 0; k < 3; k++) {
            float top = p[a + k] + (p[b + k] - p[a + k]) * fx;
            float bot = p[c + k] + (p[d + k] - p[c + k]) * fx;
            out[k] = top + (bot - top) * fy;
        }
    }

    /** Separable Gaussian blur (edge clamped); sigma in pixels, no-op below 0.05. */
    FImg gaussian(double sigma) { return gaussian(sigma, sigma); }

    FImg gaussian(double sx, double sy) {
        FImg t = blur1d(this, sx, true);
        return blur1d(t, sy, false);
    }

    private static FImg blur1d(FImg src, double sigma, boolean horizontal) {
        if (sigma < 0.05) return src;
        int r = Math.max(1, (int) Math.ceil(sigma * 3));
        float[] k = new float[2 * r + 1];
        double sum = 0;
        for (int i = -r; i <= r; i++) { k[i + r] = (float) Math.exp(-0.5 * i * i / (sigma * sigma)); sum += k[i + r]; }
        for (int i = 0; i < k.length; i++) k[i] /= (float) sum;
        int w = src.w, h = src.h, rw = w * 3;
        FImg o = new FImg(w, h);
        if (horizontal) {
            float[] pad = new float[(w + 2 * r) * 3];
            for (int y = 0; y < h; y++) {
                int base = y * rw;
                System.arraycopy(src.p, base, pad, r * 3, rw);
                for (int x = 0; x < r; x++) for (int c = 0; c < 3; c++) {
                    pad[x * 3 + c] = src.p[base + c];
                    pad[(w + r + x) * 3 + c] = src.p[base + rw - 3 + c];
                }
                for (int x = 0; x < rw; x++) {
                    float acc = 0;
                    for (int j = 0, q = x; j < k.length; j++, q += 3) acc += pad[q] * k[j];
                    o.p[base + x] = acc;
                }
            }
        } else {
            for (int y = 0; y < h; y++) {
                int ob = y * rw;
                for (int j = -r; j <= r; j++) {
                    int sb = Math.min(h - 1, Math.max(0, y + j)) * rw;
                    float kv = k[j + r];
                    for (int x = 0; x < rw; x++) o.p[ob + x] += src.p[sb + x] * kv;
                }
            }
        }
        return o;
    }

    /** Area-average resize to an arbitrary smaller size (box filter with fractional coverage). */
    FImg areaResize(int nw, int nh) {
        FImg o = new FImg(nw, nh);
        double sx = (double) w / nw, sy = (double) h / nh;
        if (sx < 1 || sy < 1) {               // upscaling: bilinear
            float[] px = new float[3];
            for (int y = 0; y < nh; y++) for (int x = 0; x < nw; x++) {
                sample((x + 0.5) * sx - 0.5, (y + 0.5) * sy - 0.5, px);
                int i = (y * nw + x) * 3;
                o.p[i] = px[0]; o.p[i + 1] = px[1]; o.p[i + 2] = px[2];
            }
            return o;
        }
        for (int y = 0; y < nh; y++) {
            double y0 = y * sy, y1 = y0 + sy;
            for (int x = 0; x < nw; x++) {
                double x0 = x * sx, x1 = x0 + sx;
                double r = 0, g = 0, b = 0, wsum = 0;
                for (int yy = (int) y0; yy < Math.min(h, (int) Math.ceil(y1)); yy++) {
                    double wy = Math.min(y1, yy + 1) - Math.max(y0, yy);
                    for (int xx = (int) x0; xx < Math.min(w, (int) Math.ceil(x1)); xx++) {
                        double wx = Math.min(x1, xx + 1) - Math.max(x0, xx);
                        double ww = wx * wy;
                        int i = (yy * w + xx) * 3;
                        r += p[i] * ww; g += p[i + 1] * ww; b += p[i + 2] * ww; wsum += ww;
                    }
                }
                int i = (y * nw + x) * 3;
                o.p[i] = (float) (r / wsum); o.p[i + 1] = (float) (g / wsum); o.p[i + 2] = (float) (b / wsum);
            }
        }
        return o;
    }

    /** 2x2 box downsample (the camera's pixel aperture). */
    FImg half() {
        FImg o = new FImg(w / 2, h / 2);
        for (int y = 0; y < o.h; y++) for (int x = 0; x < o.w; x++) {
            int a = ((2 * y) * w + 2 * x) * 3, b = a + 3, c = a + w * 3, d = c + 3;
            int i = (y * o.w + x) * 3;
            for (int k = 0; k < 3; k++) o.p[i + k] = (p[a + k] + p[b + k] + p[c + k] + p[d + k]) * 0.25f;
        }
        return o;
    }
}
