package io.mosip.vivid.av.testing;

import io.mosip.vivid.av.audio.AudioClip;
import io.mosip.vivid.av.audio.AudioSource;

/**
 * Microphone stand-in whose content is decided after capture starts (when the prompt reveals the code), then
 * released in step with the fake clock.
 */
public final class ScriptedAudioSource implements AudioSource {
    private final FakeClock clock;
    private volatile short[] content = new short[0];
    private long start = -1;
    private boolean closed;
    public boolean wiped;

    public ScriptedAudioSource(FakeClock clock) { this.clock = clock; }

    public void play(short[] pcm) { content = pcm.clone(); }

    @Override public int sampleRate() { return Audio.RATE; }
    @Override public synchronized void start(long nowNanos) { start = nowNanos; }

    @Override
    public synchronized AudioClip snapshot() {
        if (start < 0 || closed) return new AudioClip(new short[0], Audio.RATE, Math.max(0, start));
        long n = (clock.nanoTime() - start) * Audio.RATE / 1_000_000_000L;
        short[] out = new short[(int) Math.min(n, Integer.MAX_VALUE / 2)];
        System.arraycopy(content, 0, out, 0, Math.min(out.length, content.length));
        return new AudioClip(out, Audio.RATE, start);
    }

    @Override public void stop() {}

    @Override
    public synchronized void close() {
        closed = true;
        java.util.Arrays.fill(content, (short) 0);
        wiped = true;
    }
}
