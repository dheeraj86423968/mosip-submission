package io.mosip.vivid.engine.signal;

import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.engine.image.Fft;
import io.mosip.vivid.engine.image.FloatImage;
import io.mosip.vivid.engine.image.ImageOps;

/**
 * S2 - moire / frequency-domain screen-replay detector.
 *
 * <p>A 64x64 luma crop of the face is mean-subtracted, Hann-windowed and transformed with a 2-D FFT. Two features
 * are read from the power spectrum {@code P(u,v)} with radius {@code r = sqrt(u^2 + v^2)}:
 * <ul>
 *   <li>{@code hfRatio} - energy at {@code r >= 16} (a quarter of the sampling rate and above) over all AC energy.
 *       Natural skin follows a roughly 1/f^2 spectrum and scores a few percent; a screen's pixel grid or
 *       half-toned print adds broadband high-frequency energy;</li>
 *   <li>{@code peakScore} - the largest ratio of a bin to the mean of its radius ring, for {@code r >= 6}, log-mapped
 *       between 8 (the expected maximum of ~3000 exponential samples, i.e. no periodicity) and 80. Moire interference
 *       produces isolated spectral peaks far above their ring mean.</li>
 * </ul>
 * Raw logit {@code 6 (peakScore - 0.45) + 4 (hfRatio - 0.45)} then Platt calibration. Periodicity carries the larger
 * weight because sensor noise alone also raises {@code hfRatio} (white noise has a flat spectrum), whereas only a
 * periodic structure produces isolated peaks. Confidence falls for small
 * faces (the crop is up-sampled) and for blurred crops (blur removes moire as well as texture).
 * Attack class {@code SCREEN_REPLAY}. The constants are untrained defaults; a bundle calibration refines them.
 */
public final class MoireSignal implements Signal {
    public static final int SIZE = 64;
    static final int HF_RADIUS = 16;
    static final int PEAK_MIN_RADIUS = 6;

    private final PlattCalibration calibration;
    private double[] lastFeatures = new double[2];

    public MoireSignal(PlattCalibration calibration) {
        this.calibration = calibration == null ? PlattCalibration.identity() : calibration;
    }

    @Override public String id() { return Signals.MOIRE; }

    @Override
    public SignalScore score(FaceContext ctx) {
        FloatImage g = ctx.grayFace(SIZE);
        double[] f = features(g.data(), SIZE);
        lastFeatures = f;
        double p = calibration.apply(rawLogit(f));
        double iodConf = ImageOps.clamp01((ctx.interOcular() - 20) / 30.0);
        double sharpConf = ImageOps.clamp01(ImageOps.laplacianVariance(g) / 40.0);
        double confidence = iodConf * sharpConf;
        return new SignalScore(id(), p, confidence, false, p > 0.5 ? Signals.ATTACK_SCREEN : null);
    }

    /** Raw attack logit from {hfRatio, peakScore}. */
    public static double rawLogit(double[] f) {
        return 6 * (f[1] - 0.45) + 4 * (f[0] - 0.45);
    }

    /** {hfRatio, peakScore} of an n x n luma patch. */
    public static double[] features(float[] patch, int n) {
        float[] w = new float[n * n];
        double mean = 0;
        for (float v : patch) mean += v;
        mean /= patch.length;
        for (int y = 0; y < n; y++) {
            double hy = Fft.hann(y, n);
            for (int x = 0; x < n; x++) w[y * n + x] = (float) ((patch[y * n + x] - mean) * hy * Fft.hann(x, n));
        }
        double[] power = Fft.powerSpectrum2D(w, n);
        int maxR = (int) Math.ceil(Math.sqrt(2) * n / 2) + 1;
        double[] ringSum = new double[maxR + 1];
        int[] ringCount = new int[maxR + 1];
        double total = 0, hf = 0;
        for (int v = 0; v < n; v++) {
            int fv = Fft.signedFrequency(v, n);
            for (int u = 0; u < n; u++) {
                int fu = Fft.signedFrequency(u, n);
                double r = Math.hypot(fu, fv);
                if (r < 1) continue;
                double pw = power[v * n + u];
                total += pw;
                if (r >= HF_RADIUS) hf += pw;
                int ri = (int) Math.round(r);
                ringSum[ri] += pw; ringCount[ri]++;
            }
        }
        if (total < 1e-9) return new double[] {0, 0};
        double hfRatio = hf / total;
        double peakRatio = 0;
        for (int v = 0; v < n; v++) {
            int fv = Fft.signedFrequency(v, n);
            for (int u = 0; u < n; u++) {
                int fu = Fft.signedFrequency(u, n);
                double r = Math.hypot(fu, fv);
                if (r < PEAK_MIN_RADIUS) continue;
                int ri = (int) Math.round(r);
                double ringMean = ringSum[ri] / Math.max(1, ringCount[ri]);
                if (ringMean > 1e-12) peakRatio = Math.max(peakRatio, power[v * n + u] / ringMean);
            }
        }
        double peakScore = ImageOps.clamp01((Math.log(Math.max(peakRatio, 1e-9)) - Math.log(8)) / (Math.log(80) - Math.log(8)));
        return new double[] {hfRatio, peakScore};
    }

    /** Last {hfRatio, peakScore}, for diagnostics. */
    public double[] lastFeatures() { return lastFeatures.clone(); }

    @Override public void reset() { lastFeatures = new double[2]; }
}
