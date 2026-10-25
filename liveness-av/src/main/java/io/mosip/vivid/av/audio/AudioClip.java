package io.mosip.vivid.av.audio;

import java.util.Arrays;
import java.util.Objects;

/**
 * Mono 16-bit PCM audio held in memory only. {@code startNanos} is the session-clock time of the first sample, the
 * same clock the orchestrator and the AV analyzer use, so audio and lip motion can be aligned.
 *
 * <p>Privacy: clips are never written anywhere by this module. {@link #wipe()} zeroes the samples once a verdict
 * exists; the analyzer calls it before dropping the reference.</p>
 */
public final class AudioClip {
    private final short[] samples;
    private final int sampleRate;
    private final long startNanos;

    public AudioClip(short[] samples, int sampleRate, long startNanos) {
        this.samples = Objects.requireNonNull(samples, "samples");
        if (sampleRate <= 0) throw new IllegalArgumentException("sampleRate must be positive");
        this.sampleRate = sampleRate;
        this.startNanos = startNanos;
    }

    /** Copy of the samples. */
    public short[] samples() { return samples.clone(); }
    public short sample(int i) { return samples[i]; }
    public int length() { return samples.length; }
    public int sampleRate() { return sampleRate; }
    public long startNanos() { return startNanos; }
    public long durationMillis() { return samples.length * 1000L / sampleRate; }
    /** Session-clock time of sample {@code i}. */
    public long nanosAt(int i) { return startNanos + i * 1_000_000_000L / sampleRate; }

    /** The first {@code n} samples (n clamped to the length) as a new clip with the same start time. */
    public AudioClip head(int n) {
        return new AudioClip(Arrays.copyOf(samples, Math.max(0, Math.min(n, samples.length))), sampleRate, startNanos);
    }

    /** Overwrites the samples with zeros. */
    public void wipe() { Arrays.fill(samples, (short) 0); }
}
