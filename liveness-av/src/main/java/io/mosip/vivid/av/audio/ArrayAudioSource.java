package io.mosip.vivid.av.audio;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Plays a prepared clip as if it were being captured live: {@link #snapshot()} returns the part of the clip that
 * would have been recorded by the current time of the supplied clock. Used for tests, the evaluation harness and
 * file-driven demos; with a fake clock the whole pipeline is deterministic.
 */
public final class ArrayAudioSource implements AudioSource {
    private final short[] pcm;
    private final int sampleRate;
    private final LongSupplier clock;
    private long startNanos = -1;
    private long stoppedAtNanos = -1;
    private boolean closed;

    public ArrayAudioSource(short[] pcm, int sampleRate, LongSupplier clock) {
        this.pcm = Objects.requireNonNull(pcm, "pcm").clone();
        this.sampleRate = sampleRate;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Reads a 16-bit PCM WAV file (resampled to 16 kHz mono if needed). */
    public static ArrayAudioSource fromWav(Path wav, LongSupplier clock) throws IOException {
        AudioClip c = WavReader.read(wav);
        return new ArrayAudioSource(c.samples(), c.sampleRate(), clock);
    }

    @Override public int sampleRate() { return sampleRate; }

    @Override
    public synchronized void start(long nowNanos) {
        if (closed) throw new IllegalStateException("closed");
        if (startNanos < 0) startNanos = nowNanos;
    }

    @Override
    public synchronized AudioClip snapshot() {
        if (startNanos < 0 || closed) return new AudioClip(new short[0], sampleRate, Math.max(0, startNanos));
        long until = stoppedAtNanos >= 0 ? stoppedAtNanos : clock.getAsLong();
        long n = Math.max(0, (until - startNanos)) * sampleRate / 1_000_000_000L;
        int len = (int) Math.min(pcm.length, n);
        short[] out = new short[len];
        System.arraycopy(pcm, 0, out, 0, len);
        return new AudioClip(out, sampleRate, startNanos);
    }

    @Override
    public synchronized void stop() {
        if (startNanos >= 0 && stoppedAtNanos < 0) stoppedAtNanos = clock.getAsLong();
    }

    @Override
    public synchronized void close() {
        stop();
        closed = true;
        java.util.Arrays.fill(pcm, (short) 0);
    }
}
