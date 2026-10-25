package io.mosip.vivid.engine.pose;

/**
 * 2-D projective transform fitted with the normalised DLT. The scale ambiguity is fixed by h33 = 1, which turns
 * the usual SVD null-space problem into an ordinary least-squares system (8 unknowns, 2 equations per point);
 * this is exact for the near-identity motions seen between video frames and keeps the solver dependency-free.
 * Points are flat arrays {@code x0,y0,x1,y1,...}.
 *
 * <p>Also provides a 6-parameter affine fit. With only five landmarks a full homography leaves two residual
 * degrees of freedom and can absorb much of the non-planar motion of a nose tip; the affine model leaves four and is
 * the one the parallax signal uses for five-point input.
 */
public final class Homography {
    private final double[] h; // 9 values row-major, h[8] == 1

    private Homography(double[] h) { this.h = h; }

    public static Homography of(double[] rowMajor3x3) {
        if (rowMajor3x3.length != 9) throw new IllegalArgumentException("need 9 values");
        double[] n = rowMajor3x3.clone();
        if (Math.abs(n[8]) < 1e-12) throw new IllegalArgumentException("h33 must be non-zero");
        for (int i = 0; i < 9; i++) n[i] /= rowMajor3x3[8];
        return new Homography(n);
    }

    /** Fits src -> dst from at least four correspondences. */
    public static Homography fit(double[] src, double[] dst) {
        int n = checkPairs(src, dst);
        if (n < 4) throw new IllegalArgumentException("need at least 4 points");
        Normalisation ns = Normalisation.of(src), nd = Normalisation.of(dst);
        double[][] a = new double[2 * n][8];
        double[] b = new double[2 * n];
        for (int i = 0; i < n; i++) {
            double x = ns.x(src[2 * i]), y = ns.y(src[2 * i + 1]);
            double u = nd.x(dst[2 * i]), v = nd.y(dst[2 * i + 1]);
            a[2 * i] = new double[] {x, y, 1, 0, 0, 0, -u * x, -u * y}; b[2 * i] = u;
            a[2 * i + 1] = new double[] {0, 0, 0, x, y, 1, -v * x, -v * y}; b[2 * i + 1] = v;
        }
        double[] p = LinearSolver.leastSquares(a, b);
        double[] hn = {p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], 1};
        // H = Td^-1 * Hn * Ts
        double[] h = mul(mul(nd.inverse(), hn), ns.matrix());
        return of(h);
    }

    /** Applies the transform to a point. */
    public double[] apply(double x, double y) {
        double w = h[6] * x + h[7] * y + h[8];
        return new double[] {(h[0] * x + h[1] * y + h[2]) / w, (h[3] * x + h[4] * y + h[5]) / w};
    }

    /** RMS distance between transformed src points and dst points. */
    public double residualRms(double[] src, double[] dst) {
        int n = checkPairs(src, dst);
        double s = 0;
        for (int i = 0; i < n; i++) {
            double[] p = apply(src[2 * i], src[2 * i + 1]);
            double dx = p[0] - dst[2 * i], dy = p[1] - dst[2 * i + 1];
            s += dx * dx + dy * dy;
        }
        return Math.sqrt(s / n);
    }

    public double[] matrix() { return h.clone(); }

    /** Fits a 2x3 affine transform {a,b,tx,c,d,ty} (src -> dst) from at least three correspondences. */
    public static double[] fitAffine(double[] src, double[] dst) {
        int n = checkPairs(src, dst);
        if (n < 3) throw new IllegalArgumentException("need at least 3 points");
        double[][] a = new double[2 * n][6];
        double[] b = new double[2 * n];
        for (int i = 0; i < n; i++) {
            double x = src[2 * i], y = src[2 * i + 1];
            a[2 * i] = new double[] {x, y, 1, 0, 0, 0}; b[2 * i] = dst[2 * i];
            a[2 * i + 1] = new double[] {0, 0, 0, x, y, 1}; b[2 * i + 1] = dst[2 * i + 1];
        }
        return LinearSolver.leastSquares(a, b);
    }

    /** RMS residual of an affine fit. */
    public static double affineResidualRms(double[] m, double[] src, double[] dst) {
        int n = checkPairs(src, dst);
        double s = 0;
        for (int i = 0; i < n; i++) {
            double x = src[2 * i], y = src[2 * i + 1];
            double u = m[0] * x + m[1] * y + m[2], v = m[3] * x + m[4] * y + m[5];
            double dx = u - dst[2 * i], dy = v - dst[2 * i + 1];
            s += dx * dx + dy * dy;
        }
        return Math.sqrt(s / n);
    }

    private static int checkPairs(double[] src, double[] dst) {
        if (src.length != dst.length || src.length % 2 != 0) throw new IllegalArgumentException("point arrays must have equal even length");
        return src.length / 2;
    }

    private static double[] mul(double[] a, double[] b) {
        double[] c = new double[9];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
            double s = 0;
            for (int k = 0; k < 3; k++) s += a[i * 3 + k] * b[k * 3 + j];
            c[i * 3 + j] = s;
        }
        return c;
    }

    /** Hartley normalisation: translate to centroid, scale so mean distance is sqrt(2). */
    private static final class Normalisation {
        final double cx, cy, s;
        Normalisation(double cx, double cy, double s) { this.cx = cx; this.cy = cy; this.s = s; }
        static Normalisation of(double[] pts) {
            int n = pts.length / 2;
            double cx = 0, cy = 0;
            for (int i = 0; i < n; i++) { cx += pts[2 * i]; cy += pts[2 * i + 1]; }
            cx /= n; cy /= n;
            double d = 0;
            for (int i = 0; i < n; i++) d += Math.hypot(pts[2 * i] - cx, pts[2 * i + 1] - cy);
            d /= n;
            return new Normalisation(cx, cy, d < 1e-12 ? 1 : Math.sqrt(2) / d);
        }
        double x(double v) { return (v - cx) * s; }
        double y(double v) { return (v - cy) * s; }
        double[] matrix() { return new double[] {s, 0, -s * cx, 0, s, -s * cy, 0, 0, 1}; }
        double[] inverse() { return new double[] {1 / s, 0, cx, 0, 1 / s, cy, 0, 0, 1}; }
    }
}
