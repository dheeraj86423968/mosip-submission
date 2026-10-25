package io.mosip.vivid.engine.image;

/**
 * Iterative radix-2 complex FFT (Cooley-Tukey) for power-of-two lengths, plus a 2-D power spectrum helper.
 * Sizes in this engine are tiny (64x64 image patches, 256-sample rPPG windows) so no further optimisation is needed.
 */
public final class Fft {
    private Fft() {}

    /** In-place forward FFT of (re, im); length must be a power of two. */
    public static void transform(double[] re, double[] im) {
        int n = re.length;
        if (n != im.length) throw new IllegalArgumentException("re/im length mismatch");
        if (Integer.bitCount(n) != 1) throw new IllegalArgumentException("length must be a power of two: " + n);
        // bit-reversal permutation
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double tr = re[b] * cr - im[b] * ci;
                    double ti = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - tr; im[b] = im[a] - ti;
                    re[a] += tr; im[a] += ti;
                    double ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
    }

    /** Power spectrum |X[k]|^2 of a real signal (zero-padded to a power of two). */
    public static double[] powerSpectrum(double[] signal) {
        int n = nextPowerOfTwo(signal.length);
        double[] re = new double[n], im = new double[n];
        System.arraycopy(signal, 0, re, 0, signal.length);
        transform(re, im);
        double[] p = new double[n];
        for (int i = 0; i < n; i++) p[i] = re[i] * re[i] + im[i] * im[i];
        return p;
    }

    /**
     * 2-D power spectrum of an {@code n x n} raster (n a power of two), computed as row FFTs followed by column FFTs.
     * Output is row-major with the DC term at index 0; frequency index u in [0,n) maps to u for u &lt; n/2 and u-n otherwise.
     */
    public static double[] powerSpectrum2D(float[] img, int n) {
        if (img.length != n * n) throw new IllegalArgumentException("expected " + (n * n) + " values");
        double[] re = new double[n * n], im = new double[n * n];
        for (int i = 0; i < re.length; i++) re[i] = img[i];
        double[] rr = new double[n], ri = new double[n];
        for (int y = 0; y < n; y++) {
            System.arraycopy(re, y * n, rr, 0, n);
            System.arraycopy(im, y * n, ri, 0, n);
            transform(rr, ri);
            System.arraycopy(rr, 0, re, y * n, n);
            System.arraycopy(ri, 0, im, y * n, n);
        }
        for (int x = 0; x < n; x++) {
            for (int y = 0; y < n; y++) { rr[y] = re[y * n + x]; ri[y] = im[y * n + x]; }
            transform(rr, ri);
            for (int y = 0; y < n; y++) { re[y * n + x] = rr[y]; im[y * n + x] = ri[y]; }
        }
        double[] p = new double[n * n];
        for (int i = 0; i < p.length; i++) p[i] = re[i] * re[i] + im[i] * im[i];
        return p;
    }

    /** Signed frequency index for a bin of an n-point transform. */
    public static int signedFrequency(int bin, int n) { return bin < n / 2 ? bin : bin - n; }

    public static int nextPowerOfTwo(int v) {
        int n = 1;
        while (n < v) n <<= 1;
        return n;
    }

    /** Hann window value for sample i of n. */
    public static double hann(int i, int n) {
        return n <= 1 ? 1.0 : 0.5 * (1 - Math.cos(2 * Math.PI * i / (n - 1)));
    }
}
