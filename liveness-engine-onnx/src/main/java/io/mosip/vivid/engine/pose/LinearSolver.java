package io.mosip.vivid.engine.pose;

/** Gaussian elimination with partial pivoting and a normal-equations least-squares solver. Tiny systems only. */
public final class LinearSolver {
    private LinearSolver() {}

    /** Solves {@code A x = b} for square A (modified in place). */
    public static double[] solve(double[][] a, double[] b) {
        int n = b.length;
        double[][] m = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            if (a[i].length != n) throw new IllegalArgumentException("matrix must be square");
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n] = b[i];
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) if (Math.abs(m[r][col]) > Math.abs(m[pivot][col])) pivot = r;
            if (Math.abs(m[pivot][col]) < 1e-12) throw new ArithmeticException("singular system");
            double[] t = m[col]; m[col] = m[pivot]; m[pivot] = t;
            for (int r = col + 1; r < n; r++) {
                double f = m[r][col] / m[col][col];
                if (f == 0) continue;
                for (int c = col; c <= n; c++) m[r][c] -= f * m[col][c];
            }
        }
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double s = m[i][n];
            for (int c = i + 1; c < n; c++) s -= m[i][c] * x[c];
            x[i] = s / m[i][i];
        }
        return x;
    }

    /** Least-squares solution of the over-determined {@code A x = b} (rows &gt;= columns) via normal equations. */
    public static double[] leastSquares(double[][] a, double[] b) {
        int rows = a.length, cols = a[0].length;
        if (rows < cols) throw new IllegalArgumentException("need at least as many equations as unknowns");
        double[][] ata = new double[cols][cols];
        double[] atb = new double[cols];
        for (int r = 0; r < rows; r++) {
            for (int i = 0; i < cols; i++) {
                atb[i] += a[r][i] * b[r];
                for (int j = 0; j < cols; j++) ata[i][j] += a[r][i] * a[r][j];
            }
        }
        return solve(ata, atb);
    }
}
