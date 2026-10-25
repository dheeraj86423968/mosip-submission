package io.mosip.vivid.av.audio;

/**
 * SPI for a microphone (or a test stand-in). One instance serves one spoken-code round.
 *
 * <p>Contract: mono, signed 16-bit samples at {@link #sampleRate()}. {@link #start(long)} begins capture and stamps
 * the first sample with the given session-clock time. {@link #snapshot()} returns everything captured so far
 * without stopping. {@link #close()} stops capture and must discard every buffered sample: audio is never stored.</p>
 */
public interface AudioSource extends AutoCloseable {

    /** Samples per second, 16000 for every implementation in this module. */
    int sampleRate();

    /**
     * Starts capturing.
     *
     * @param nowNanos session-clock time to stamp on the first captured sample
     * @throws AudioUnavailableException when no capture device can be opened
     */
    void start(long nowNanos);

    /** Audio captured since {@link #start(long)}; empty before start. */
    AudioClip snapshot();

    /** Stops capturing; the buffer stays readable until {@link #close()}. Idempotent. */
    void stop();

    /** Stops and zeroes the buffer. Idempotent. */
    @Override
    void close();

    /** Thrown when the microphone cannot be opened (missing, busy or denied). */
    final class AudioUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public AudioUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
