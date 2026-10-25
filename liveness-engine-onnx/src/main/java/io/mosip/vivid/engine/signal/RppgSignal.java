package io.mosip.vivid.engine.signal;

import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.engine.detect.FaceDetection;
import io.mosip.vivid.engine.image.Fft;
import io.mosip.vivid.engine.image.ImageOps;
import io.mosip.vivid.engine.image.RgbImage;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * S4 - remote photoplethysmography. Skin colour pulses with blood volume at the heart rate; a photo, a screen replay
 * or a mask shows no such periodicity (or an unphysiological one).
 *
 * <p>Per frame the mean R, G, B over three skin ROIs (forehead and both cheeks, placed from the eye landmarks) is
 * appended to a time-stamped ring buffer of {@code windowSeconds}. A gap longer than {@code maxGapSeconds} (face lost)
 * clears the buffer. To evaluate, samples are linearly resampled onto a uniform {@code fs} Hz grid (so irregular camera
 * timing is tolerated), each channel is normalised by its mean, and two pulse signals are formed: plain green and the
 * CHROM projection (de Haan and Jeanne 2013) {@code X = 3R - 2G, Y = 1.5R + G - 1.5B, S = X - (sigma_X / sigma_Y) Y},
 * which cancels specular and motion components. Each is detrended by a 1-second moving average, Hann-windowed and
 * transformed; in the 0.7-3.0 Hz band (42-180 bpm) the SNR is the power within +/-0.15 Hz of the strongest bin
 * divided by the remaining band power. Raw attack logit {@code 2 (1.5 - SNR)} with the better of the two signals, then
 * Platt calibration. Confidence ramps linearly from 0 at {@code minSeconds} of data to 1 when the window is full, and is
 * halved when the face moves more than 3% of the inter-ocular distance per frame.
 * Attack class {@code PRINT} when the signal is essentially constant, otherwise {@code VIDEO_REPLAY}.
 */
public final class RppgSignal implements Signal {
    public static final double DEFAULT_WINDOW_SECONDS = 6.0;
    public static final double DEFAULT_MIN_SECONDS = 3.0;
    public static final double DEFAULT_SAMPLE_RATE = 20.0;
    public static final double MAX_GAP_SECONDS = 0.5;
    public static final double BAND_LOW_HZ = 0.7, BAND_HIGH_HZ = 3.0;
    /**
     * Ceiling on the reported attack probability, below the default {@code padHardThreshold} of 0.90, so S4 is fused
     * but never vetoes on its own. On compressed real-face video it scored 32 of 39 bona fide clips at 0.5 or more and
     * vetoed 3 of them (docs/testing/proxy-eval/README.md); it stays fusion-only until measured on webcam capture.
     */
    public static final double MAX_ATTACK_PROBABILITY = 0.85;

    /** Result of a window evaluation. */
    public static final class Evaluation {
        public final double seconds, snr, bpm, confidence, attackProbability;
        public final boolean constant;
        Evaluation(double seconds, double snr, double bpm, double confidence, double attackProbability, boolean constant) {
            this.seconds = seconds; this.snr = snr; this.bpm = bpm; this.confidence = confidence; this.attackProbability = attackProbability; this.constant = constant;
        }
        @Override public String toString() { return String.format("rppg %.1fs snr=%.2f bpm=%.0f conf=%.2f p=%.2f", seconds, snr, bpm, confidence, attackProbability); }
    }

    private final double windowSeconds, minSeconds, fs;
    private final PlattCalibration calibration;
    private final Deque<double[]> samples = new ArrayDeque<>(); // {t, r, g, b, motion}
    private double lastEyeX = Double.NaN, lastEyeY = Double.NaN, lastIod = Double.NaN;
    private Evaluation last;

    public RppgSignal(PlattCalibration calibration) {
        this(DEFAULT_WINDOW_SECONDS, DEFAULT_MIN_SECONDS, DEFAULT_SAMPLE_RATE, calibration);
    }

    public RppgSignal(double windowSeconds, double minSeconds, double sampleRate, PlattCalibration calibration) {
        this.windowSeconds = windowSeconds; this.minSeconds = minSeconds; this.fs = sampleRate;
        this.calibration = calibration == null ? PlattCalibration.identity() : calibration;
    }

    @Override public String id() { return Signals.RPPG; }

    @Override
    public SignalScore score(FaceContext ctx) {
        FaceDetection f = ctx.face();
        double iod = f.interOcular();
        double ex = f.eyeMidX(), ey = f.eyeMidY();
        double motion = 0;
        if (!Double.isNaN(lastEyeX)) motion = Math.hypot(ex - lastEyeX, ey - lastEyeY) / Math.max(1, lastIod);
        lastEyeX = ex; lastEyeY = ey; lastIod = iod;
        double[] mean = roiMean(ctx.frame(), f);
        addSample(ctx.timestampSeconds(), mean[0], mean[1], mean[2], motion);
        Evaluation e = evaluate();
        last = e;
        String cls = e.attackProbability > 0.5 && e.confidence > 0 ? (e.constant ? Signals.ATTACK_PRINT : Signals.ATTACK_VIDEO) : null;
        return new SignalScore(id(), Math.min(MAX_ATTACK_PROBABILITY, e.attackProbability), e.confidence, false, cls);
    }

    /** Mean RGB over forehead and both cheeks; ROIs are derived from the eye landmarks (roll ignored). */
    static double[] roiMean(RgbImage img, FaceDetection f) {
        double iod = f.interOcular();
        double ex = f.eyeMidX(), ey = f.eyeMidY();
        double[][] rois = {
                {ex - 0.45 * iod, ey - 0.78 * iod, 0.9 * iod, 0.35 * iod},                 // forehead
                {f.landmarkX(0) - 0.2 * iod, f.landmarkY(0) + 0.4 * iod, 0.35 * iod, 0.3 * iod},  // subject-right cheek
                {f.landmarkX(1) - 0.15 * iod, f.landmarkY(1) + 0.4 * iod, 0.35 * iod, 0.3 * iod}, // subject-left cheek
        };
        double r = 0, g = 0, b = 0, n = 0;
        for (double[] roi : rois) {
            double[] m = ImageOps.meanRgb(img, (int) Math.round(roi[0]), (int) Math.round(roi[1]), Math.max(1, (int) Math.round(roi[2])), Math.max(1, (int) Math.round(roi[3])));
            r += m[0] * m[3]; g += m[1] * m[3]; b += m[2] * m[3]; n += m[3];
        }
        return n == 0 ? new double[] {0, 0, 0} : new double[] {r / n, g / n, b / n};
    }

    /** Appends one ROI-mean sample; public so tests and diagnostics can drive the estimator without images. */
    public void addSample(double tSeconds, double r, double g, double b, double motion) {
        double[] lastSample = samples.peekLast();
        if (lastSample != null && (tSeconds - lastSample[0] > MAX_GAP_SECONDS || tSeconds <= lastSample[0])) {
            samples.clear();
        }
        samples.addLast(new double[] {tSeconds, r, g, b, motion});
        while (!samples.isEmpty() && samples.peekFirst()[0] < tSeconds - windowSeconds) samples.pollFirst();
    }

    @Override public void onFaceLost(long timestampNanos) {
        lastEyeX = Double.NaN;
    }

    /** Evaluates the current buffer. */
    public Evaluation evaluate() {
        int n = samples.size();
        if (n < 8) return new Evaluation(0, 0, 0, 0, 0.5, false);
        double t0 = samples.peekFirst()[0], t1 = samples.peekLast()[0];
        double span = t1 - t0;
        if (span < minSeconds) return new Evaluation(span, 0, 0, 0, 0.5, false);
        int m = (int) Math.floor(span * fs);
        if (m < 16) return new Evaluation(span, 0, 0, 0, 0.5, false);
        double[][] raw = samples.toArray(new double[0][]);
        double[] r = new double[m], g = new double[m], b = new double[m];
        double motionSum = 0;
        for (double[] s : raw) motionSum += s[4];
        resample(raw, t0, m, r, g, b);
        double[] mr = ImageOps.meanStd(toFloat(r)), mg = ImageOps.meanStd(toFloat(g)), mb = ImageOps.meanStd(toFloat(b));
        boolean constant = mg[1] / Math.max(1e-9, mg[0]) < 1e-4;
        double[] rn = normalise(r, mr[0]), gn = normalise(g, mg[0]), bn = normalise(b, mb[0]);
        double[] x = new double[m], y = new double[m];
        for (int i = 0; i < m; i++) { x[i] = 3 * rn[i] - 2 * gn[i]; y[i] = 1.5 * rn[i] + gn[i] - 1.5 * bn[i]; }
        double sx = ImageOps.meanStd(toFloat(x))[1], sy = ImageOps.meanStd(toFloat(y))[1];
        double alpha = sy > 1e-12 ? sx / sy : 0;
        double[] chrom = new double[m];
        for (int i = 0; i < m; i++) chrom[i] = x[i] - alpha * y[i];
        double[] snrChrom = bandSnr(chrom), snrGreen = bandSnr(gn);
        double[] best = snrChrom[0] >= snrGreen[0] ? snrChrom : snrGreen;
        double p = calibration.apply(2.0 * (1.5 - best[0]));
        double confidence = ImageOps.clamp01(span / windowSeconds);
        if (motionSum / n > 0.03) confidence *= 0.5;
        return new Evaluation(span, best[0], best[1] * 60, confidence, p, constant);
    }

    /** {snr, peakFrequencyHz} of a signal: detrend, window, FFT, band analysis. */
    double[] bandSnr(double[] s) {
        int m = s.length;
        int win = Math.max(2, (int) Math.round(fs));
        double[] d = new double[m];
        for (int i = 0; i < m; i++) {
            int a = Math.max(0, i - win / 2), bnd = Math.min(m, i + win / 2 + 1);
            double sum = 0;
            for (int k = a; k < bnd; k++) sum += s[k];
            d[i] = (s[i] - sum / (bnd - a)) * Fft.hann(i, m);
        }
        int nfft = Math.max(256, Fft.nextPowerOfTwo(m));
        double[] padded = new double[nfft];
        System.arraycopy(d, 0, padded, 0, m);
        double[] power = Fft.powerSpectrum(padded);
        double res = fs / nfft;
        int lo = (int) Math.ceil(BAND_LOW_HZ / res), hi = (int) Math.floor(BAND_HIGH_HZ / res);
        int peak = lo; double band = 0;
        for (int k = lo; k <= hi && k < nfft / 2; k++) {
            band += power[k];
            if (power[k] > power[peak]) peak = k;
        }
        int halfWidth = Math.max(1, (int) Math.round(0.15 / res));
        double peakPower = 0;
        for (int k = Math.max(lo, peak - halfWidth); k <= Math.min(hi, peak + halfWidth); k++) peakPower += power[k];
        double rest = band - peakPower;
        double snr = rest > 1e-12 ? peakPower / rest : (peakPower > 0 ? 100 : 0);
        return new double[] {snr, peak * res};
    }

    /** Linear interpolation of the irregular samples onto a uniform grid t0 + i / fs. */
    private void resample(double[][] raw, double t0, int m, double[] r, double[] g, double[] b) {
        int j = 0;
        for (int i = 0; i < m; i++) {
            double t = t0 + i / fs;
            while (j + 1 < raw.length - 1 && raw[j + 1][0] <= t) j++;
            double[] a = raw[j], c = raw[Math.min(j + 1, raw.length - 1)];
            double w = c[0] > a[0] ? Math.max(0, Math.min(1, (t - a[0]) / (c[0] - a[0]))) : 0;
            r[i] = a[1] + (c[1] - a[1]) * w; g[i] = a[2] + (c[2] - a[2]) * w; b[i] = a[3] + (c[3] - a[3]) * w;
        }
    }

    private static double[] normalise(double[] v, double mean) {
        double[] o = new double[v.length];
        for (int i = 0; i < v.length; i++) o[i] = mean > 1e-9 ? v[i] / mean - 1 : 0;
        return o;
    }

    private static float[] toFloat(double[] v) {
        float[] f = new float[v.length];
        for (int i = 0; i < v.length; i++) f[i] = (float) v[i];
        return f;
    }

    public Evaluation lastEvaluation() { return last; }
    public int bufferedSamples() { return samples.size(); }

    @Override public void reset() {
        samples.clear();
        lastEyeX = lastEyeY = lastIod = Double.NaN;
        last = null;
    }
}
