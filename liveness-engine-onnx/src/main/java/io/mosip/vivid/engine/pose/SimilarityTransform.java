package io.mosip.vivid.engine.pose;

/**
 * Least-squares 2-D similarity (rotation + uniform scale + translation, no reflection) mapping src to dst:
 * {@code u = a x - b y + tx, v = b x + a y + ty}. Equivalent to Umeyama's closed form for the 2-D case and to
 * OpenCV's {@code estimateAffinePartial2D} without RANSAC. Used for face alignment before embedding.
 */
public final class SimilarityTransform {
    private SimilarityTransform() {}

    /** Returns the 2x3 matrix {a, -b, tx, b, a, ty} fitted from at least two point pairs. */
    public static double[] fit(double[] src, double[] dst) {
        if (src.length != dst.length || src.length < 4 || src.length % 2 != 0) throw new IllegalArgumentException("need >= 2 point pairs");
        int n = src.length / 2;
        double[][] a = new double[2 * n][4];
        double[] b = new double[2 * n];
        for (int i = 0; i < n; i++) {
            double x = src[2 * i], y = src[2 * i + 1];
            a[2 * i] = new double[] {x, -y, 1, 0}; b[2 * i] = dst[2 * i];
            a[2 * i + 1] = new double[] {y, x, 0, 1}; b[2 * i + 1] = dst[2 * i + 1];
        }
        double[] p = LinearSolver.leastSquares(a, b);
        return new double[] {p[0], -p[1], p[2], p[1], p[0], p[3]};
    }

    /** Uniform scale factor of a 2x3 similarity matrix. */
    public static double scale(double[] m) { return Math.hypot(m[0], m[3]); }

    /** Rotation angle in degrees of a 2x3 similarity matrix. */
    public static double rotationDegrees(double[] m) { return Math.toDegrees(Math.atan2(m[3], m[0])); }
}
