package io.mosip.vivid.engine.signal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * Synthetic depth (GRAY16 millimetres) and IR planes registered to {@link SyntheticFaces#face()} in a 320x240 frame.
 * Scale: 1.5 mm per pixel at the face (a 150 mm face over the 100 px detector box), camera focal length 300 px, so
 * a 320x240 depth camera with the face at about 450-500 mm.
 */
final class SyntheticDepth {
    static final int W = SyntheticFaces.W, H = SyntheticFaces.H;
    static final double MM_PER_PX = 1.5, FOCAL_PX = 300;
    static final double HEAD_CX = 160, HEAD_CY = 122;   // pixel centre of the head ellipsoid
    static final double BACKGROUND_MM = 1600;

    private SyntheticDepth() {}

    /**
     * Live head: ellipsoid (semi-axes 80 x 110 x 90 mm, centre 590 mm away) with a nose ridge and tip
     * (up to 24 mm proud), eye sockets (-9 mm) and a chin, rotated by yaw and pitch and splatted with a z-buffer.
     */
    static double[] head(double yawDeg, double pitchDeg) {
        double[] z = filled(BACKGROUND_MM);
        double a = 80, b = 110, c = 90, zc = 590;
        double cy = Math.cos(Math.toRadians(yawDeg)), sy = Math.sin(Math.toRadians(yawDeg));
        double cp = Math.cos(Math.toRadians(pitchDeg)), sp = Math.sin(Math.toRadians(pitchDeg));
        double step = MM_PER_PX / 3;
        for (double y = -b; y <= b; y += step) {
            for (double x = -a; x <= a; x += step) {
                double q = 1 - (x * x) / (a * a) - (y * y) / (b * b);
                if (q <= 0) continue;
                double h = c * Math.sqrt(q) + features(x, y);
                // yaw about the vertical axis (positive: nose to image right), then pitch (positive: look up)
                double x1 = x * cy + h * sy, h1 = -x * sy + h * cy;
                double y2 = y * cp - h1 * sp, h2 = y * sp + h1 * cp;
                double depth = zc - h2;
                int u = (int) Math.round(HEAD_CX + x1 / MM_PER_PX), v = (int) Math.round(HEAD_CY + y2 / MM_PER_PX);
                if (u < 0 || v < 0 || u >= W || v >= H) continue;
                if (depth < z[v * W + u]) z[v * W + u] = depth;
            }
        }
        return z;
    }

    /**
     * Detection for a head rendered by {@link #head}: the {@link SyntheticFaces#face()} box with its five landmarks
     * moved through the same yaw/pitch transform, as a landmark detector following the real head would report them.
     */
    static io.mosip.vivid.engine.detect.FaceDetection detection(double yawDeg, double pitchDeg) {
        double[] lm = SyntheticFaces.face().landmarks();
        double cy = Math.cos(Math.toRadians(yawDeg)), sy = Math.sin(Math.toRadians(yawDeg));
        double cp = Math.cos(Math.toRadians(pitchDeg)), sp = Math.sin(Math.toRadians(pitchDeg));
        double[] out = new double[10];
        for (int i = 0; i < 5; i++) {
            double x = (lm[2 * i] - HEAD_CX) * MM_PER_PX, y = (lm[2 * i + 1] - HEAD_CY) * MM_PER_PX;
            double q = Math.max(0, 1 - x * x / (80.0 * 80) - y * y / (110.0 * 110));
            double h = 90 * Math.sqrt(q) + features(x, y);
            double x1 = x * cy + h * sy, h1 = -x * sy + h * cy;
            double y2 = y * cp - h1 * sp;
            out[2 * i] = HEAD_CX + x1 / MM_PER_PX;
            out[2 * i + 1] = HEAD_CY + y2 / MM_PER_PX;
        }
        io.mosip.vivid.engine.detect.FaceDetection f = SyntheticFaces.face();
        return new io.mosip.vivid.engine.detect.FaceDetection(f.x(), f.y(), f.w(), f.h(), f.score(), out);
    }

    /** Nose, eye sockets and chin in head coordinates (mm; y down, landmarks of SyntheticFaces at 1.5 mm/px). */
    static double features(double x, double y) {
        double noseY = (135 - HEAD_CY) * MM_PER_PX;                 // nose tip landmark
        double eyeY = (110 - HEAD_CY) * MM_PER_PX, eyeX = 20 * MM_PER_PX;
        double ridge = 14 * gauss(x, 0, 7) * gauss(y, noseY - 22, 22);   // nasal bridge
        double tip = 18 * gauss(x, 0, 10) * gauss(y, noseY - 4, 9);       // nose tip
        double sockets = -9 * (gauss(x, -eyeX, 11) + gauss(x, eyeX, 11)) * gauss(y, eyeY, 8);
        double chin = 6 * gauss(x, 0, 18) * gauss(y, 75, 10);
        return Math.max(ridge, tip) + Math.min(ridge, tip) * 0.3 + sockets + chin;
    }

    private static double gauss(double v, double m, double s) { double d = (v - m) / s; return Math.exp(-0.5 * d * d); }

    /**
     * A flat sheet {@code distanceMm} away at the face centre, rotated by {@code yawDeg} about the vertical axis and
     * {@code pitchDeg} about the horizontal, rendered with exact pinhole perspective (so its depth is not linear in
     * pixel coordinates). Covers {@code halfWidthPx} x {@code halfHeightPx} around the face; background elsewhere.
     */
    static double[] sheet(double distanceMm, double yawDeg, double pitchDeg, double halfWidthPx, double halfHeightPx) {
        double[] z = filled(BACKGROUND_MM);
        // plane through (0,0,D) with normal n: n . P = n . (0,0,D); ray P = Z (u/f, v/f, 1)
        double ty = Math.toRadians(yawDeg), tp = Math.toRadians(pitchDeg);
        double nx = Math.sin(ty), ny = Math.sin(tp), nz = Math.cos(ty) * Math.cos(tp);
        for (int v = 0; v < H; v++) for (int u = 0; u < W; u++) {
            if (Math.abs(u - HEAD_CX) > halfWidthPx || Math.abs(v - HEAD_CY) > halfHeightPx) continue;
            double ru = (u - HEAD_CX) / FOCAL_PX, rv = (v - HEAD_CY) / FOCAL_PX;
            z[v * W + u] = nz * distanceMm / (nx * ru + ny * rv + nz);
        }
        return z;
    }

    /** A sheet bent around a vertical axis into a cylinder of {@code radiusMm}, bulging towards the camera. */
    static double[] cylinder(double distanceMm, double radiusMm, double halfWidthPx, double halfHeightPx) {
        double[] z = filled(BACKGROUND_MM);
        for (int v = 0; v < H; v++) for (int u = 0; u < W; u++) {
            if (Math.abs(u - HEAD_CX) > halfWidthPx || Math.abs(v - HEAD_CY) > halfHeightPx) continue;
            double x = (u - HEAD_CX) * MM_PER_PX;
            if (Math.abs(x) >= radiusMm) continue;
            z[v * W + u] = distanceMm + radiusMm - Math.sqrt(radiusMm * radiusMm - x * x);
        }
        return z;
    }

    /** Adds Gaussian noise of {@code sigmaMm} and zeroes {@code holeFraction} of the pixels (plus a 12x12 hole block when > 0). */
    static double[] degrade(double[] z, double sigmaMm, double holeFraction, long seed) {
        Random r = new Random(seed);
        double[] out = z.clone();
        for (int i = 0; i < out.length; i++) {
            out[i] += r.nextGaussian() * sigmaMm;
            if (r.nextDouble() < holeFraction) out[i] = 0;
        }
        if (holeFraction > 0) for (int v = 95; v < 107; v++) for (int u = 170; u < 182; u++) out[v * W + u] = 0;
        return out;
    }

    static ByteBuffer gray16(double[] mm, ByteOrder order) {
        ByteBuffer b = ByteBuffer.allocate(W * H * 2).order(order);
        for (double v : mm) b.putShort((short) Math.max(0, Math.min(65535, Math.round(v))));
        b.flip();
        return b;
    }

    static ByteBuffer gray16(double[] mm) { return gray16(mm, ByteOrder.LITTLE_ENDIAN); }

    /**
     * IR of a live face: Lambertian shading of the given depth map under a co-located emitter with inverse-square
     * fall-off, skin albedo 0.75, dark irises and a saturated corneal glint at each eye landmark.
     */
    static byte[] irLive(double[] depthMm) {
        byte[] ir = new byte[W * H];
        for (int v = 0; v < H; v++) for (int u = 0; u < W; u++) {
            int i = v * W + u;
            double z = depthMm[i];
            double gx = (at(depthMm, u + 1, v) - at(depthMm, u - 1, v)) / (2 * MM_PER_PX);
            double gy = (at(depthMm, u, v + 1) - at(depthMm, u, v - 1)) / (2 * MM_PER_PX);
            double nz = 1 / Math.sqrt(1 + gx * gx + gy * gy);
            boolean face = z < 900;
            double albedo = face ? 0.75 : 0.5;
            double fall = Math.pow(500 / z, 2);
            ir[i] = (byte) Math.min(255, Math.round(255 * 0.85 * albedo * nz * fall));
        }
        for (int[] eye : new int[][] {{140, 110}, {180, 110}}) {
            for (int dv = -3; dv <= 3; dv++) for (int du = -4; du <= 4; du++) ir[(eye[1] + dv) * W + eye[0] + du] = (byte) 60;
            for (int dv = -1; dv <= 0; dv++) for (int du = 0; du <= 1; du++) ir[(eye[1] + dv) * W + eye[0] + du] = (byte) 255;
        }
        return ir;
    }

    /** IR of a print: NIR-transparent inks on bright paper, so a nearly uniform sheet with faint features, no glint. */
    static byte[] irPrint(long seed) {
        Random r = new Random(seed);
        byte[] ir = new byte[W * H];
        for (int v = 0; v < H; v++) for (int u = 0; u < W; u++) {
            double feature = (Math.abs(v - 110) < 4 && (Math.abs(u - 140) < 6 || Math.abs(u - 180) < 6)) ? -12 : 0;
            ir[v * W + u] = (byte) Math.max(0, Math.min(255, Math.round(170 + feature + r.nextGaussian() * 3)));
        }
        return ir;
    }

    /** IR of a screen replay: the panel emits no NIR (face area ~8/255); the bezel and hand around it are lit. */
    static byte[] irScreen(long seed) {
        Random r = new Random(seed);
        byte[] ir = new byte[W * H];
        for (int v = 0; v < H; v++) for (int u = 0; u < W; u++) {
            boolean panel = Math.abs(u - HEAD_CX) < 90 && Math.abs(v - HEAD_CY) < 110;
            double level = panel ? 8 : 70;
            ir[v * W + u] = (byte) Math.max(0, Math.min(255, Math.round(level + r.nextGaussian() * 2)));
        }
        return ir;
    }

    static ByteBuffer ir16(byte[] ir8) {
        ByteBuffer b = ByteBuffer.allocate(ir8.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (byte v : ir8) b.putShort((short) ((v & 0xff) * 257));
        b.flip();
        return b;
    }

    private static double at(double[] z, int u, int v) {
        u = Math.max(0, Math.min(W - 1, u)); v = Math.max(0, Math.min(H - 1, v));
        return z[v * W + u];
    }

    private static double[] filled(double v) { double[] z = new double[W * H]; java.util.Arrays.fill(z, v); return z; }
}
