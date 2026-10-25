package io.mosip.vivid.av.sync;

import io.mosip.vivid.av.audio.AudioClip;

import java.util.Objects;

/**
 * Tests whether mouth opening moves with the speech energy envelope.
 *
 * <ol>
 *   <li>Window: from the first to the last voiced audio frame, widened by {@code marginMillis} on each side.</li>
 *   <li>Both signals are resampled onto a {@code gridMillis} grid: audio as frame RMS smoothed over 3 grid steps,
 *       mouth by linear interpolation of the per-frame samples (gaps over {@code maxVideoGapMillis} make the result
 *       {@code INSUFFICIENT_VIDEO}). Both are z-normalised.</li>
 *   <li>Peak: the largest Pearson correlation over lags in {@code [-maxLag, +maxLag]}.</li>
 *   <li>Baselines: the same peak search after circularly shifting the mouth series by {@code baselineCount} offsets
 *       spread over {@code [minShift, window - minShift]}. These are chance-level correlations for this very
 *       utterance and face, including the rhythm of the digits, so the test asks "is the aligned correlation
 *       special?", not just "is it high?".</li>
 *   <li>Pass: mouth sd ≥ {@code minMouthStd}, peak ≥ {@code minPeak}, z ≥ {@code minZ}, and peak beats the largest
 *       baseline by {@code minMarginOverBaselineMax}.</li>
 * </ol>
 */
public final class LipSyncScorer {
    private final SyncConfig cfg;

    public LipSyncScorer(SyncConfig cfg) { this.cfg = Objects.requireNonNull(cfg, "cfg"); }
    public LipSyncScorer() { this(SyncConfig.defaults()); }

    public SyncConfig config() { return cfg; }

    public SyncResult score(AudioClip audio, MouthTrack mouth) {
        return score(SpeechActivity.of(audio), mouth);
    }

    public SyncResult score(SpeechActivity act, MouthTrack mouth) {
        if (!act.hasSpeech()) return SyncResult.of(SyncResult.Status.NO_SPEECH);
        int perGrid = Math.max(1, cfg.gridMillis / SpeechActivity.FRAME_MS);
        int f0 = Math.max(0, act.firstVoiced() - cfg.marginMillis / SpeechActivity.FRAME_MS);
        int f1 = Math.min(act.frames() - 1, act.lastVoiced() + cfg.marginMillis / SpeechActivity.FRAME_MS);
        int n = (f1 - f0 + 1) / perGrid;
        int windowMillis = n * cfg.gridMillis;
        if (windowMillis < cfg.minWindowMillis) {
            // Too short to tell rhythm from chance; widen symmetrically if the clip allows, otherwise give up.
            return SyncResult.of(SyncResult.Status.INSUFFICIENT_VIDEO);
        }
        double[] env = new double[n];
        double[] mo = new double[n];
        long maxGap = cfg.maxVideoGapMillis * 1_000_000L;
        int missing = 0;
        double rawSum = 0, rawSq = 0;
        for (int g = 0; g < n; g++) {
            double acc = 0;
            for (int k = 0; k < perGrid; k++) acc += act.rms(f0 + g * perGrid + k);
            env[g] = acc / perGrid;
            long centre = act.frameNanos(f0 + g * perGrid) + cfg.gridMillis * 500_000L;
            double m = mouth.at(centre, maxGap);
            if (Double.isNaN(m)) missing++;
            mo[g] = m;
        }
        if (missing > n / 10) return SyncResult.of(SyncResult.Status.INSUFFICIENT_VIDEO);
        fillGaps(mo);
        for (double m : mo) { rawSum += m; rawSq += m * m; }
        double mouthStd = Math.sqrt(Math.max(0, rawSq / n - (rawSum / n) * (rawSum / n)));
        env = smooth3(env);
        zNorm(env);
        zNorm(mo);
        int maxLag = cfg.maxLagMillis / cfg.gridMillis;
        double[] best = peak(env, mo, maxLag, 0);
        int minShift = Math.max(maxLag + 1, cfg.minShiftMillis / cfg.gridMillis);
        int span = n - 2 * minShift;
        double sum = 0, sq = 0, max = Double.NEGATIVE_INFINITY;
        int count = 0;
        if (span > 0) {
            for (int k = 0; k < cfg.baselineCount; k++) {
                int shift = minShift + (int) Math.round((double) k * span / Math.max(1, cfg.baselineCount - 1));
                double r = peak(env, mo, maxLag, shift)[0];
                sum += r; sq += r * r; max = Math.max(max, r); count++;
            }
        }
        double mean = count == 0 ? Double.NaN : sum / count;
        double sd = count == 0 ? Double.NaN : Math.sqrt(Math.max(1e-6, sq / count - mean * mean));
        int lagMs = (int) best[1] * cfg.gridMillis;
        if (mouthStd < cfg.minMouthStd) {
            return new SyncResult(SyncResult.Status.STATIC_MOUTH, best[0], lagMs, mean, sd, max, mouthStd, windowMillis);
        }
        if (count == 0) return new SyncResult(SyncResult.Status.INSUFFICIENT_VIDEO, best[0], lagMs, mean, sd, max, mouthStd, windowMillis);
        boolean ok = best[0] >= cfg.minPeak && (best[0] - mean) / sd >= cfg.minZ && best[0] - max >= cfg.minMarginOverBaselineMax;
        return new SyncResult(ok ? SyncResult.Status.SYNCED : SyncResult.Status.OUT_OF_SYNC, best[0], lagMs, mean, sd, max, mouthStd, windowMillis);
    }

    /** {peak r, lag in grid steps}; mouth index is (i + lag + shift) mod n, so negative lag means the mouth leads. */
    static double[] peak(double[] a, double[] b, int maxLag, int shift) {
        int n = a.length;
        double best = Double.NEGATIVE_INFINITY;
        int bestLag = 0;
        for (int lag = -maxLag; lag <= maxLag; lag++) {
            double sab = 0, sa = 0, sb = 0, saa = 0, sbb = 0;
            int c = 0;
            for (int i = 0; i < n; i++) {
                int j = i + lag;
                if (shift == 0 && (j < 0 || j >= n)) continue;
                double x = a[i], y = b[Math.floorMod(j + shift, n)];
                sab += x * y; sa += x; sb += y; saa += x * x; sbb += y * y; c++;
            }
            if (c < 3) continue;
            double cov = sab / c - (sa / c) * (sb / c);
            double va = saa / c - (sa / c) * (sa / c), vb = sbb / c - (sb / c) * (sb / c);
            double r = va <= 1e-12 || vb <= 1e-12 ? 0 : cov / Math.sqrt(va * vb);
            if (r > best) { best = r; bestLag = lag; }
        }
        return new double[] {best == Double.NEGATIVE_INFINITY ? 0 : best, bestLag};
    }

    private static void fillGaps(double[] x) {
        double last = Double.NaN;
        for (int i = 0; i < x.length; i++) if (!Double.isNaN(x[i])) { last = x[i]; break; }
        for (int i = 0; i < x.length; i++) {
            if (Double.isNaN(x[i])) x[i] = Double.isNaN(last) ? 0 : last; else last = x[i];
        }
    }

    private static double[] smooth3(double[] x) {
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            double s = x[i]; int c = 1;
            if (i > 0) { s += x[i - 1]; c++; }
            if (i + 1 < x.length) { s += x[i + 1]; c++; }
            y[i] = s / c;
        }
        return y;
    }

    private static void zNorm(double[] x) {
        double s = 0, q = 0;
        for (double v : x) { s += v; q += v * v; }
        double m = s / x.length, sd = Math.sqrt(Math.max(0, q / x.length - m * m));
        for (int i = 0; i < x.length; i++) x[i] = sd < 1e-12 ? 0 : (x[i] - m) / sd;
    }
}
