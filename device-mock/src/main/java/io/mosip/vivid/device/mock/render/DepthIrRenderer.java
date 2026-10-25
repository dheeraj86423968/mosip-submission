package io.mosip.vivid.device.mock.render;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Random;

/**
 * Synthetic depth (GRAY16 little-endian millimetres, 0 = no measurement) and active-IR (GRAY8) planes registered
 * pixel-for-pixel to the frames of {@link SyntheticFaceRenderer}. Kept separate from the RGB renderer: it re-derives
 * the head and feature positions from the same published proportions (head height 0.6 x frame height x scale, width
 * 0.78 x height, feature offsets from yaw and pitch) so the depth bumps sit under the drawn eyes and nose.
 *
 * <p>Physical scale: the head is 220 mm tall, so millimetres per pixel is {@code 220 / headHeightPx}; the head centre
 * is {@code 590 / scale} mm from the camera (face surface about 500 mm), the background wall 1600 mm. A live head is
 * an ellipsoid (90 mm deep) plus a nasal ridge and tip (up to about 24 mm proud), eye sockets (-9 mm) and a chin, with
 * shoulders and neck behind it. Prints and screens are sheets rendered with exact pinhole perspective (focal length =
 * frame width in pixels), optionally tilted, or bent into a cylinder. IR is Lambertian shading under a co-located
 * emitter with inverse-square fall-off; skin albedo follows the RGB luminance, the corneal glint is a saturated spot on
 * each open pupil; print IR is bright, nearly uniform paper (NIR-transparent inks); a screen panel is IR-dark.
 *
 * <p>Stateless and thread-safe. Synthetic only: no real depth camera's noise structure (multipath, edge flying pixels,
 * structured-light shadowing) is modelled beyond Gaussian noise and random drop-outs.
 */
public final class DepthIrRenderer {
    /** How a sheet presents (prints and screens). */
    public enum Sheet { FLAT, CYLINDER, SCREEN }

    /** One head placement, as passed to {@link SyntheticFaceRenderer#drawFace}. */
    public record Head(FaceState state, double cx, double cy, double scale) {}

    /** Rendered planes; either may be null when not requested. */
    public record Planes(byte[] depth, byte[] ir) {
        public ByteBuffer depthBuffer() { return depth == null ? null : ByteBuffer.wrap(depth).order(ByteOrder.LITTLE_ENDIAN); }
        public ByteBuffer irBuffer() { return ir == null ? null : ByteBuffer.wrap(ir); }
    }

    public static final double HEAD_HEIGHT_MM = 220;
    public static final double WALL_MM = 1600;
    private static final double SHEET_MM = 450, SCREEN_MM = 400;

    private final int width, height;

    public DepthIrRenderer(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("size must be positive");
        this.width = width; this.height = height;
    }

    /** Live heads (any number, nearest wins). {@code rgb} supplies the IR albedo; may be null. */
    public Planes heads(List<Head> heads, BufferedImage rgb, boolean wantDepth, boolean wantIr, double noiseMm, double holeFraction, long seed) {
        double[] z = wall();
        boolean[] skin = new boolean[width * height];
        for (Head h : heads) drawHead(z, skin, h);
        double mmpp = heads.isEmpty() ? 1.0 : HEAD_HEIGHT_MM / headH(heads.get(0));
        byte[] ir = null;
        if (wantIr) {
            ir = new byte[width * height];
            for (int i = 0; i < z.length; i++) {
                int u = i % width, v = i / width;
                double albedo = skin[i] ? 0.35 + 0.5 * luma(rgb, u, v) : 0.5;
                double val = 255 * 0.85 * albedo * normalZ(z, u, v, mmpp) * Math.pow(500 / z[i], 2);
                ir[i] = (byte) clamp255(val);
            }
            for (Head h : heads) glints(ir, h);
        }
        return new Planes(wantDepth ? encode(degrade(z, noiseMm, holeFraction, null, 0, seed)) : null, ir);
    }

    /**
     * A sheet (print or screen) carrying the face at {@code at}: tilted by {@code tiltDeg} about the vertical axis and
     * {@code pitchDeg} about the horizontal, or (CYLINDER) bent with {@code bendRadiusMm}.
     */
    public Planes sheet(Sheet kind, Head at, BufferedImage rgb, boolean wantDepth, boolean wantIr, double tiltDeg, double pitchDeg,
                        double bendRadiusMm, double noiseMm, double holeFraction, long seed) {
        double[] z = wall();
        boolean[] onSheet = new boolean[width * height];
        double x0 = at.cx() * width, y0 = at.cy() * height, hh = headH(at), hw = hh * 0.78;
        double mmpp = HEAD_HEIGHT_MM / hh;
        double halfW = kind == Sheet.SCREEN ? 0.75 * hh : 1.1 * hw, halfH = 0.8 * hh;
        double dist = kind == Sheet.SCREEN ? SCREEN_MM : SHEET_MM, f = width;
        double ty = Math.toRadians(tiltDeg), tp = Math.toRadians(pitchDeg);
        double nx = Math.sin(ty), ny = Math.sin(tp), nz = Math.cos(ty) * Math.cos(tp);
        for (int v = 0; v < height; v++) for (int u = 0; u < width; u++) {
            if (Math.abs(u - x0) > halfW || Math.abs(v - y0) > halfH) continue;
            int i = v * width + u;
            if (kind == Sheet.CYLINDER) {
                double x = (u - x0) * mmpp;
                if (Math.abs(x) >= bendRadiusMm) continue;
                z[i] = dist + bendRadiusMm - Math.sqrt(bendRadiusMm * bendRadiusMm - x * x);
            } else {
                z[i] = nz * dist / (nx * (u - x0) / f + ny * (v - y0) / f + nz);
            }
            onSheet[i] = true;
        }
        Random r = new Random(seed * 7919 + 1);
        byte[] ir = null;
        if (wantIr) {
            ir = new byte[width * height];
            double panelHalfW = halfW - 0.08 * hh, panelHalfH = halfH - 0.08 * hh;
            for (int i = 0; i < z.length; i++) {
                int u = i % width, v = i / width;
                double val;
                if (!onSheet[i]) {
                    val = 255 * 0.85 * 0.5 * Math.pow(500 / z[i], 2);
                } else if (kind == Sheet.SCREEN) {
                    boolean panel = Math.abs(u - x0) <= panelHalfW && Math.abs(v - y0) <= panelHalfH;
                    val = panel ? 8 : 70;
                } else {
                    double paper = 0.62 + 0.08 * (luma(rgb, u, v) - 0.5);
                    val = 255 * paper * Math.pow(SHEET_MM / z[i], 2);
                }
                ir[i] = (byte) clamp255(val + r.nextGaussian() * 2.5);
            }
        }
        double extraHoles = kind == Sheet.SCREEN ? 0.10 : 0;
        return new Planes(wantDepth ? encode(degrade(z, noiseMm, holeFraction, onSheet, extraHoles, seed)) : null, ir);
    }

    // ------------------------------------------------------------------ geometry

    private double headH(Head h) { return height * 0.60 * h.scale(); }

    private void drawHead(double[] z, boolean[] skin, Head h) {
        FaceState s = h.state();
        double headH = headH(h), headW = headH * 0.78, mmpp = HEAD_HEIGHT_MM / headH;
        double yawR = Math.toRadians(s.yawDeg()), pitchR = Math.toRadians(s.pitchDeg());
        double headWVis = headW * (0.80 + 0.20 * Math.cos(yawR));
        double x0 = h.cx() * width, y0 = h.cy() * height;
        double dx = Math.sin(yawR) * headW * 0.30, dy = -Math.sin(pitchR) * headH * 0.25;
        double zc = 590 / h.scale();
        double eyeY = y0 - headH * 0.10 + dy, eyeDX = headW * 0.22 * (0.85 + 0.15 * Math.cos(yawR));
        double noseX = x0 + dx * 1.3, noseY = y0 + headH * 0.08 + dy;
        double chinX = x0 + dx, chinY = y0 + headH * 0.40 + dy * 0.8;
        // shoulders and neck behind the head
        fillEllipse(z, skin, x0, y0 + headH * 0.90, headW * 1.3, headH * 0.45, zc + 80, false);
        for (int v = (int) (y0 + headH * 0.35); v < Math.min(height, (int) (y0 + headH * 0.60)); v++) {
            for (int u = (int) (x0 - headW * 0.18); u < (int) (x0 + headW * 0.18); u++) {
                if (u < 0 || v < 0 || u >= width) continue;
                int i = v * width + u;
                if (zc + 30 < z[i]) { z[i] = zc + 30; skin[i] = true; }
            }
        }
        int u0 = Math.max(0, (int) (x0 - headWVis / 2) - 1), u1 = Math.min(width - 1, (int) (x0 + headWVis / 2) + 1);
        int v0 = Math.max(0, (int) (y0 - headH / 2) - 1), v1 = Math.min(height - 1, (int) (y0 + headH / 2) + 1);
        for (int v = v0; v <= v1; v++) for (int u = u0; u <= u1; u++) {
            double a = (u - x0) / (headWVis / 2), b = (v - y0) / (headH / 2);
            double q = 1 - a * a - b * b;
            if (q <= 0) continue;
            double relief = 90 * Math.sqrt(q);
            double ridge = 14 * g((u - noseX) * mmpp, 7) * g((v - (noseY - headH * 0.07)) * mmpp, 22);
            double tip = 18 * g((u - noseX) * mmpp, 10) * g((v - noseY) * mmpp + 4, 9);
            double sockets = -9 * (g((u - (x0 + dx - eyeDX)) * mmpp, 11) + g((u - (x0 + dx + eyeDX)) * mmpp, 11)) * g((v - eyeY) * mmpp, 8);
            double chin = 6 * g((u - chinX) * mmpp, 18) * g((v - chinY) * mmpp, 10);
            double depth = zc - (relief + Math.max(ridge, tip) + 0.3 * Math.min(ridge, tip) + sockets + chin);
            int i = v * width + u;
            if (depth < z[i]) { z[i] = depth; skin[i] = true; }
        }
    }

    private void glints(byte[] ir, Head h) {
        FaceState s = h.state();
        if (s.eyeOpen() <= 0.15) return;
        double headH = headH(h), headW = headH * 0.78;
        double yawR = Math.toRadians(s.yawDeg()), pitchR = Math.toRadians(s.pitchDeg());
        double x0 = h.cx() * width, y0 = h.cy() * height;
        double dx = Math.sin(yawR) * headW * 0.30, dy = -Math.sin(pitchR) * headH * 0.25;
        double eyeY = y0 - headH * 0.10 + dy, eyeDX = headW * 0.22 * (0.85 + 0.15 * Math.cos(yawR)), eyeW = headW * 0.18;
        int size = Math.max(1, (int) Math.round(headH / 150));
        for (int side = -1; side <= 1; side += 2) {
            double ex = x0 + dx + side * eyeDX, px = ex + Math.sin(yawR) * eyeW * 0.15;
            // dark iris around a saturated glint
            int ir0 = Math.max(2, (int) Math.round(eyeW * 0.2));
            for (int v = (int) eyeY - ir0; v <= (int) eyeY + ir0; v++) for (int u = (int) px - ir0; u <= (int) px + ir0; u++) set(ir, u, v, 55);
            for (int v = (int) eyeY - size; v < (int) eyeY + size; v++) for (int u = (int) px - size + 1; u <= (int) px + size; u++) set(ir, u, v, 255);
        }
    }

    private void set(byte[] ir, int u, int v, int val) {
        if (u >= 0 && v >= 0 && u < width && v < height) ir[v * width + u] = (byte) val;
    }

    private void fillEllipse(double[] z, boolean[] skin, double cx, double cy, double rx, double ry, double depth, boolean isSkin) {
        for (int v = Math.max(0, (int) (cy - ry)); v < Math.min(height, (int) (cy + ry) + 1); v++) {
            for (int u = Math.max(0, (int) (cx - rx)); u < Math.min(width, (int) (cx + rx) + 1); u++) {
                double a = (u - cx) / rx, b = (v - cy) / ry;
                if (a * a + b * b > 1) continue;
                int i = v * width + u;
                if (depth < z[i]) { z[i] = depth; skin[i] = isSkin; }
            }
        }
    }

    private double normalZ(double[] z, int u, int v, double mmpp) {
        double gx = (z[v * width + Math.min(width - 1, u + 1)] - z[v * width + Math.max(0, u - 1)]) / (2 * mmpp);
        double gy = (z[Math.min(height - 1, v + 1) * width + u] - z[Math.max(0, v - 1) * width + u]) / (2 * mmpp);
        if (Math.abs(gx) > 5 || Math.abs(gy) > 5) return 0.2; // silhouette edge
        return 1 / Math.sqrt(1 + gx * gx + gy * gy);
    }

    private static double luma(BufferedImage rgb, int u, int v) {
        if (rgb == null || u >= rgb.getWidth() || v >= rgb.getHeight()) return 0.6;
        int p = rgb.getRGB(u, v);
        return (0.299 * ((p >> 16) & 0xff) + 0.587 * ((p >> 8) & 0xff) + 0.114 * (p & 0xff)) / 255.0;
    }

    private double[] wall() { double[] z = new double[width * height]; java.util.Arrays.fill(z, WALL_MM); return z; }

    private double[] degrade(double[] z, double noiseMm, double holeFraction, boolean[] region, double extraHoles, long seed) {
        Random r = new Random(seed * 1_000_003L + 17);
        double[] out = z.clone();
        for (int i = 0; i < out.length; i++) {
            if (noiseMm > 0) out[i] += r.nextGaussian() * noiseMm;
            double holes = holeFraction + (region != null && region[i] ? extraHoles : 0);
            if (holes > 0 && r.nextDouble() < holes) out[i] = 0;
        }
        return out;
    }

    private static byte[] encode(double[] mm) {
        ByteBuffer b = ByteBuffer.allocate(mm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : mm) b.putShort((short) Math.max(0, Math.min(65535, Math.round(v))));
        return b.array();
    }

    private static double g(double d, double s) { double t = d / s; return Math.exp(-0.5 * t * t); }

    private static double clamp255(double v) { return Math.max(0, Math.min(255, Math.round(v))); }
}
