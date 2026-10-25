package io.mosip.vivid.engine.signal;

import io.mosip.vivid.engine.detect.FaceDetection;

/**
 * Face region of a sensor plane registered to the RGB frame: the detector box shrunk to the inner face (12 % off each
 * side, 8 % off the top, 5 % off the bottom) and masked by the inscribed ellipse, so hair, ears and background at the
 * box corners stay out of the fit. Coordinates are in sensor-plane pixels; {@code sx} rescales detector coordinates
 * when the plane and the decoded RGB frame differ in size. Package-private helper for S8.
 */
final class PlaneRegion {
    final int x0, y0, x1, y1;       // inclusive-exclusive pixel bounds, clipped to the plane
    final double cx, cy, halfW, halfH;
    final int stride;
    final double noseX, noseY, eyeRX, eyeRY, eyeLX, eyeLY, iod;

    PlaneRegion(FaceDetection face, int planeW, int planeH, double sx, double sy, int targetSamples) {
        double bx = face.x() * sx, by = face.y() * sy, bw = face.w() * sx, bh = face.h() * sy;
        double left = bx + 0.12 * bw, right = bx + 0.88 * bw, top = by + 0.08 * bh, bottom = by + 0.95 * bh;
        cx = (left + right) / 2; cy = (top + bottom) / 2;
        halfW = Math.max(1, (right - left) / 2); halfH = Math.max(1, (bottom - top) / 2);
        x0 = clamp((int) Math.floor(left), planeW); x1 = clamp((int) Math.ceil(right), planeW);
        y0 = clamp((int) Math.floor(top), planeH); y1 = clamp((int) Math.ceil(bottom), planeH);
        double area = Math.PI * halfW * halfH;
        stride = Math.max(1, (int) Math.round(Math.sqrt(area / Math.max(1, targetSamples))));
        noseX = face.landmarkX(2) * sx; noseY = face.landmarkY(2) * sy;
        eyeRX = face.landmarkX(0) * sx; eyeRY = face.landmarkY(0) * sy;
        eyeLX = face.landmarkX(1) * sx; eyeLY = face.landmarkY(1) * sy;
        iod = Math.max(1, Math.hypot(eyeLX - eyeRX, eyeLY - eyeRY));
    }

    /** Normalised horizontal coordinate in [-1, 1] across the region. */
    double u(int x) { return (x - cx) / halfW; }
    /** Normalised vertical coordinate in [-1, 1] across the region. */
    double v(int y) { return (y - cy) / halfH; }

    boolean inside(int x, int y) {
        double u = u(x), v = v(y);
        return u * u + v * v <= 1.0;
    }

    boolean isEmpty() { return x1 - x0 < 3 || y1 - y0 < 3; }

    private static int clamp(int v, int n) { return Math.max(0, Math.min(n, v)); }

    /** Percentile (0..1) of the first {@code n} entries; sorts a copy. */
    static double percentile(double[] values, int n, double q) {
        if (n <= 0) return Double.NaN;
        double[] c = java.util.Arrays.copyOf(values, n);
        java.util.Arrays.sort(c);
        double pos = q * (n - 1);
        int lo = (int) Math.floor(pos), hi = Math.min(n - 1, lo + 1);
        return c[lo] + (c[hi] - c[lo]) * (pos - lo);
    }
}
