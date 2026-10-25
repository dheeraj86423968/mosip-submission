package io.mosip.vivid.av.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.util.Arrays;
import java.util.Objects;

/**
 * Desktop microphone through Java Sound: 16 kHz, mono, signed 16-bit little-endian, read on a daemon thread into a
 * bounded in-memory buffer ({@code maxSeconds}). Nothing is written to disk; {@link #close()} zeroes the buffer.
 *
 * <p>Latency: the first sample is stamped with the session-clock time at which {@code line.start()} returned. The
 * device's own input buffering (typically 20-100 ms) is not measured; the lip-sync scorer searches a ±200 ms lag
 * window, which absorbs it. Not usable on Android (no {@code javax.sound}); an Android {@code AudioRecord} source
 * would implement the same SPI.</p>
 */
public final class JavaSoundAudioSource implements AudioSource {
    public static final int RATE = 16_000;
    private static final AudioFormat FORMAT = new AudioFormat(RATE, 16, 1, true, false);

    private final int maxSamples;
    private short[] buffer;
    private int length;
    private long startNanos = -1;
    private TargetDataLine line;
    private Thread reader;
    private volatile boolean running;

    public JavaSoundAudioSource(int maxSeconds) {
        if (maxSeconds <= 0) throw new IllegalArgumentException("maxSeconds must be positive");
        this.maxSamples = maxSeconds * RATE;
        this.buffer = new short[maxSamples];
    }

    /** True when the default mixer offers a capture line in the required format. Does not open it. */
    public static boolean isAvailable() {
        try {
            return AudioSystem.isLineSupported(new DataLine.Info(TargetDataLine.class, FORMAT));
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override public int sampleRate() { return RATE; }

    @Override
    public synchronized void start(long nowNanos) {
        if (running) return;
        Objects.requireNonNull(buffer, "closed");
        try {
            line = AudioSystem.getTargetDataLine(FORMAT);
            line.open(FORMAT, RATE / 10 * 2); // ~100 ms device buffer
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            throw new AudioUnavailableException("no microphone in 16 kHz mono 16-bit: " + e.getMessage(), e);
        }
        line.start();
        startNanos = nowNanos;
        running = true;
        TargetDataLine opened = line;
        reader = new Thread(() -> pump(opened), "vivid-av-mic");
        reader.setDaemon(true);
        reader.start();
    }

    private void pump(TargetDataLine source) {
        byte[] chunk = new byte[RATE / 50 * 2]; // 20 ms
        while (running) {
            int n = source.read(chunk, 0, chunk.length);
            if (n <= 0) {
                if (!source.isOpen()) break;
                continue;
            }
            synchronized (this) {
                if (buffer == null) return;
                for (int i = 0; i + 1 < n && length < maxSamples; i += 2) {
                    buffer[length++] = (short) ((chunk[i] & 0xff) | (chunk[i + 1] << 8));
                }
                if (length >= maxSamples) running = false;
            }
        }
        Arrays.fill(chunk, (byte) 0);
    }

    @Override
    public synchronized AudioClip snapshot() {
        if (buffer == null || startNanos < 0) return new AudioClip(new short[0], RATE, Math.max(0, startNanos));
        return new AudioClip(Arrays.copyOf(buffer, length), RATE, startNanos);
    }

    @Override
    public void stop() {
        running = false;
        TargetDataLine l;
        synchronized (this) { l = line; line = null; }
        if (l != null) {
            l.stop();
            l.close();
        }
        Thread t = reader;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    @Override
    public void close() {
        stop();
        synchronized (this) {
            if (buffer != null) Arrays.fill(buffer, (short) 0);
            buffer = null;
            length = 0;
        }
    }
}
