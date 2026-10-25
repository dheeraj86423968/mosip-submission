package io.mosip.vivid.av.testing;

import io.mosip.vivid.av.sync.MouthTrack;

import java.util.Random;
import java.util.function.LongToDoubleFunction;

/**
 * Synthetic mouth-opening tracks. A "live" track is driven by the speech envelope: frame RMS smoothed over 80 ms
 * (lips move more slowly than the waveform), normalised, compressed and mapped to mouthAspect 0.06..0.36, sampled
 * {@code leadMs} ahead of the audio, plus Gaussian noise. This is an idealisation of real articulation, where lip
 * aperture correlates with the envelope only partly (r ≈ 0.3-0.6 in the audio-visual speech literature).
 */
public final class LipTracks {
    public static final double REST = 0.06;
    public static final double RANGE = 0.30;

    private LipTracks() {}

    /** Mouth opening as a function of session time, from audio that starts at {@code audioStartNanos}. */
    public static LongToDoubleFunction fromAudio(short[] pcm, long audioStartNanos, int leadMs, double noiseSd, Random rnd) {
        int hop = Audio.RATE / 100;
        int n = pcm.length / hop;
        double[] rms = new double[Math.max(1, n)];
        for (int f = 0; f < n; f++) {
            double a = 0;
            for (int i = f * hop; i < (f + 1) * hop; i++) a += (pcm[i] / 32768.0) * (pcm[i] / 32768.0);
            rms[f] = Math.sqrt(a / hop);
        }
        double[] sm = new double[rms.length];
        double max = 1e-9;
        for (int f = 0; f < rms.length; f++) {
            double s = 0; int c = 0;
            for (int k = -4; k <= 4; k++) if (f + k >= 0 && f + k < rms.length) { s += rms[f + k]; c++; }
            sm[f] = s / c;
            max = Math.max(max, sm[f]);
        }
        final double mx = max;
        return t -> {
            int f = (int) Math.floorDiv(t - audioStartNanos + leadMs * 1_000_000L, 10_000_000L);
            double e = f < 0 || f >= sm.length ? 0 : Math.pow(sm[f] / mx, 0.7);
            return Math.max(0, REST + RANGE * e + noiseSd * rnd.nextGaussian());
        };
    }

    /**
     * The "realistic" live model used by the scenarios: {@link #fromAudio} without its own noise, with the opening
     * scaled by a random articulation gain (0.4-1.6) that changes every 200 ms (different sounds open the mouth by
     * different amounts), plus 0.02 Gaussian noise. Still envelope-driven, so still easier than real lips.
     */
    public static LongToDoubleFunction articulated(short[] pcm, long audioStartNanos, int leadMs, Random rnd) {
        LongToDoubleFunction base = fromAudio(pcm, audioStartNanos, leadMs, 0.0, rnd);
        double[] gain = new double[256];
        for (int i = 0; i < gain.length; i++) gain[i] = 0.4 + 1.2 * rnd.nextDouble();
        return t -> {
            int seg = (int) Math.max(0, Math.min(gain.length - 1, (t - audioStartNanos) / 200_000_000L));
            return Math.max(0, REST + (base.applyAsDouble(t) - REST) * gain[seg] + 0.02 * rnd.nextGaussian());
        };
    }

    /** A still face (photo, mask, paused video): rest opening plus sensor noise. */
    public static LongToDoubleFunction still(double noiseSd, Random rnd) {
        return t -> REST + 0.02 + noiseSd * rnd.nextGaussian();
    }

    /** Samples a function into a track at {@code fps} with ±3 ms timestamp jitter, from t0 to t1 (session nanos). */
    public static MouthTrack sample(LongToDoubleFunction f, long t0, long t1, double fps, Random rnd) {
        MouthTrack m = new MouthTrack();
        long step = (long) (1e9 / fps);
        for (long t = t0; t <= t1; t += step) {
            long tt = t + (long) ((rnd.nextDouble() - 0.5) * 6_000_000L);
            m.add(tt, f.applyAsDouble(tt));
        }
        return m;
    }
}
