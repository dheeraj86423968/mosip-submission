package io.mosip.vivid.av.sync;

import io.mosip.vivid.av.audio.AudioClip;

import java.util.Arrays;

/**
 * Short-time energy and a simple energy voice-activity decision on 10 ms frames.
 *
 * <p>The noise floor is the 10th percentile of frame RMS; a frame is voiced when its RMS exceeds both
 * {@link #ABSOLUTE_MIN_RMS} (about -44 dBFS) and {@link #FLOOR_FACTOR} times the floor (12 dB). Gaps shorter than
 * {@value #BRIDGE_MS} ms between voiced runs are bridged and runs shorter than {@value #MIN_RUN_MS} ms are dropped,
 * so clicks do not count as speech and the pauses between digits do not end the utterance.</p>
 */
public final class SpeechActivity {
    public static final int FRAME_MS = 10;
    public static final double ABSOLUTE_MIN_RMS = 0.006;
    public static final double FLOOR_FACTOR = 4.0;
    public static final int BRIDGE_MS = 200;
    public static final int MIN_RUN_MS = 60;

    private final double[] rms;
    private final boolean[] voiced;
    private final double noiseFloor;
    private final double threshold;
    private final long startNanos;

    private SpeechActivity(double[] rms, boolean[] voiced, double noiseFloor, double threshold, long startNanos) {
        this.rms = rms; this.voiced = voiced; this.noiseFloor = noiseFloor; this.threshold = threshold; this.startNanos = startNanos;
    }

    public static SpeechActivity of(AudioClip clip) {
        int hop = clip.sampleRate() * FRAME_MS / 1000;
        int n = clip.length() / hop;
        double[] rms = new double[n];
        for (int f = 0; f < n; f++) {
            double acc = 0;
            for (int i = f * hop; i < (f + 1) * hop; i++) {
                double s = clip.sample(i) / 32768.0;
                acc += s * s;
            }
            rms[f] = Math.sqrt(acc / hop);
        }
        double floor = n == 0 ? 0 : percentile(rms, 0.10);
        double threshold = Math.max(ABSOLUTE_MIN_RMS, Math.max(floor, 1e-5) * FLOOR_FACTOR);
        boolean[] v = new boolean[n];
        for (int f = 0; f < n; f++) v[f] = rms[f] > threshold;
        bridge(v, BRIDGE_MS / FRAME_MS);
        dropShort(v, MIN_RUN_MS / FRAME_MS);
        return new SpeechActivity(rms, v, floor, threshold, clip.startNanos());
    }

    static double percentile(double[] x, double q) {
        double[] s = x.clone();
        Arrays.sort(s);
        return s[(int) Math.min(s.length - 1, Math.floor(q * (s.length - 1)))];
    }

    private static void bridge(boolean[] v, int maxGap) {
        int last = -1;
        for (int i = 0; i < v.length; i++) {
            if (!v[i]) continue;
            if (last >= 0 && i - last - 1 <= maxGap) Arrays.fill(v, last + 1, i, true);
            last = i;
        }
    }

    private static void dropShort(boolean[] v, int minRun) {
        int i = 0;
        while (i < v.length) {
            if (!v[i]) { i++; continue; }
            int j = i;
            while (j < v.length && v[j]) j++;
            if (j - i < minRun) Arrays.fill(v, i, j, false);
            i = j;
        }
    }

    public int frames() { return rms.length; }
    public double rms(int frame) { return rms[frame]; }
    public boolean voiced(int frame) { return voiced[frame]; }
    public double noiseFloor() { return noiseFloor; }
    public double threshold() { return threshold; }
    public long frameNanos(int frame) { return startNanos + frame * FRAME_MS * 1_000_000L; }

    public int firstVoiced() {
        for (int i = 0; i < voiced.length; i++) if (voiced[i]) return i;
        return -1;
    }

    public int lastVoiced() {
        for (int i = voiced.length - 1; i >= 0; i--) if (voiced[i]) return i;
        return -1;
    }

    public boolean hasSpeech() { return firstVoiced() >= 0; }

    public int voicedFrames() {
        int c = 0;
        for (boolean b : voiced) if (b) c++;
        return c;
    }

    public long speechMillis() { return (long) voicedFrames() * FRAME_MS; }

    /** Silence after the last voiced frame, in ms; the whole clip when there is no speech. */
    public long trailingSilenceMillis() {
        int last = lastVoiced();
        return (long) (rms.length - 1 - last) * FRAME_MS;
    }
}
