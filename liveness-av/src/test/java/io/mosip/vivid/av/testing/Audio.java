package io.mosip.vivid.av.testing;

import io.mosip.vivid.av.audio.AudioClip;
import io.mosip.vivid.av.audio.WavReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;

/** Test audio: a pure-Java speech-like synthesiser, mixing, padding and noise. 16 kHz mono. */
public final class Audio {
    public static final int RATE = 16_000;
    private Audio() {}

    /**
     * Speech-like signal for a digit string: every digit is 1-3 voiced "syllables" (harmonic tone with an attack /
     * decay envelope) whose count and durations depend on the digit, separated by 150-300 ms pauses. Not speech;
     * only for testing energy and synchrony without a TTS.
     */
    public static short[] syntheticSpeech(String digits, Random rnd) {
        int[] syllables = {2, 1, 1, 1, 1, 1, 2, 2, 1, 1};
        java.util.List<short[]> parts = new java.util.ArrayList<>();
        double f0 = 110 + rnd.nextDouble() * 90;
        for (char c : digits.toCharArray()) {
            int d = c - '0';
            for (int s = 0; s < syllables[d]; s++) {
                int ms = 120 + (d * 37 + s * 53) % 160 + rnd.nextInt(40);
                parts.add(syllable(ms, f0 * (0.9 + 0.2 * rnd.nextDouble()), 0.25 + 0.15 * rnd.nextDouble(), rnd));
                if (s + 1 < syllables[d]) parts.add(new short[RATE * (40 + rnd.nextInt(40)) / 1000]);
            }
            parts.add(new short[RATE * (150 + rnd.nextInt(150)) / 1000]);
        }
        return concat(parts.toArray(new short[0][]));
    }

    private static short[] syllable(int ms, double f0, double amp, Random rnd) {
        int n = RATE * ms / 1000;
        short[] out = new short[n];
        double phase = rnd.nextDouble();
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double env = Math.sin(Math.PI * i / n);
            env = Math.pow(env, 0.6);
            double s = 0;
            for (int h = 1; h <= 6; h++) s += Math.sin(2 * Math.PI * (f0 * h * t + phase)) / h;
            s += 0.05 * rnd.nextGaussian();
            out[i] = (short) Math.max(-32767, Math.min(32767, amp * env * s * 12000));
        }
        return out;
    }

    public static short[] silence(int ms) { return new short[RATE * ms / 1000]; }

    public static short[] concat(short[]... parts) {
        int n = 0;
        for (short[] p : parts) n += p.length;
        short[] out = new short[n];
        int o = 0;
        for (short[] p : parts) { System.arraycopy(p, 0, out, o, p.length); o += p.length; }
        return out;
    }

    /** Leading and trailing silence. */
    public static short[] pad(short[] x, int leadMs, int tailMs) { return concat(silence(leadMs), x, silence(tailMs)); }

    /** Adds white noise at the given RMS (fraction of full scale). */
    public static short[] withNoise(short[] x, double rms, Random rnd) {
        short[] out = new short[x.length];
        for (int i = 0; i < x.length; i++) out[i] = clip(x[i] + rnd.nextGaussian() * rms * 32768);
        return out;
    }

    /** a + gain * b starting at offsetMs, length max of both. */
    public static short[] mix(short[] a, short[] b, double gain, int offsetMs) {
        int off = RATE * offsetMs / 1000;
        short[] out = new short[Math.max(a.length, off + b.length)];
        for (int i = 0; i < out.length; i++) {
            double v = (i < a.length ? a[i] : 0) + (i >= off && i - off < b.length ? gain * b[i - off] : 0);
            out[i] = clip(v);
        }
        return out;
    }

    public static short[] scale(short[] x, double g) {
        short[] out = new short[x.length];
        for (int i = 0; i < x.length; i++) out[i] = clip(x[i] * g);
        return out;
    }

    private static short clip(double v) { return (short) Math.max(-32768, Math.min(32767, Math.round(v))); }

    public static short[] read(Path wav) throws IOException { return WavReader.read(wav).samples(); }

    public static AudioClip clip(short[] pcm, long startNanos) { return new AudioClip(pcm, RATE, startNanos); }
}
