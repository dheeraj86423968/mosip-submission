package io.mosip.vivid.engine.image;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class FftTest {
    @Test
    void matchesNaiveDft() {
        int n = 64;
        Random r = new Random(7);
        double[] re = new double[n], im = new double[n];
        for (int i = 0; i < n; i++) { re[i] = r.nextGaussian(); im[i] = r.nextGaussian(); }
        double[] fr = re.clone(), fi = im.clone();
        Fft.transform(fr, fi);
        for (int k = 0; k < n; k++) {
            double sr = 0, si = 0;
            for (int t = 0; t < n; t++) {
                double a = -2 * Math.PI * k * t / n;
                sr += re[t] * Math.cos(a) - im[t] * Math.sin(a);
                si += re[t] * Math.sin(a) + im[t] * Math.cos(a);
            }
            assertThat(fr[k]).isCloseTo(sr, offset(1e-9));
            assertThat(fi[k]).isCloseTo(si, offset(1e-9));
        }
    }

    @Test
    void sinusoidPeaksAtItsBin() {
        double[] s = new double[128];
        for (int i = 0; i < s.length; i++) s[i] = Math.sin(2 * Math.PI * 10 * i / 128.0);
        double[] p = Fft.powerSpectrum(s);
        int best = 1;
        for (int k = 1; k < 64; k++) if (p[k] > p[best]) best = k;
        assertThat(best).isEqualTo(10);
        assertThat(p[10]).isCloseTo(64 * 64, offset(1e-6));
    }

    @Test
    void impulseHasFlatSpectrumAndParseval() {
        double[] s = new double[16];
        s[0] = 1;
        for (double v : Fft.powerSpectrum(s)) assertThat(v).isCloseTo(1, offset(1e-12));
        float[] img = new float[16 * 16];
        Random r = new Random(3);
        double energy = 0;
        for (int i = 0; i < img.length; i++) { img[i] = r.nextFloat(); energy += img[i] * img[i]; }
        double sum = 0;
        for (double v : Fft.powerSpectrum2D(img, 16)) sum += v;
        assertThat(sum / img.length).isCloseTo(energy, offset(1e-6));
    }

    @Test
    void twoDimensionalGratingLandsOnTheRightAxis() {
        int n = 32;
        float[] img = new float[n * n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) img[y * n + x] = (float) Math.cos(2 * Math.PI * 4 * x / n);
        double[] p = Fft.powerSpectrum2D(img, n);
        assertThat(p[4]).isGreaterThan(1000);           // (u=4, v=0)
        assertThat(p[4 * n]).isCloseTo(0, offset(1e-6)); // (u=0, v=4)
        assertThat(Fft.signedFrequency(30, 32)).isEqualTo(-2);
    }

    @Test
    void helpers() {
        assertThat(Fft.nextPowerOfTwo(100)).isEqualTo(128);
        assertThat(Fft.nextPowerOfTwo(64)).isEqualTo(64);
        assertThat(Fft.hann(0, 9)).isZero();
        assertThat(Fft.hann(4, 9)).isCloseTo(1, offset(1e-12));
        assertThatThrownBy(() -> Fft.transform(new double[3], new double[3])).isInstanceOf(IllegalArgumentException.class);
    }
}
