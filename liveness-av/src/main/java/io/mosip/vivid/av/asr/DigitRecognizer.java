package io.mosip.vivid.av.asr;

import io.mosip.vivid.av.audio.AudioClip;

/**
 * Recognises spoken digits in one language. Implementations must run offline and must not retain the audio.
 * Thread-safety is implementation-defined; {@link VoskDigitRecognizer} is safe for concurrent calls.
 */
public interface DigitRecognizer extends AutoCloseable {

    /** BCP-47 language tag this recogniser was built for, e.g. {@code en}, {@code hi}, {@code te}, {@code gu}. */
    String language();

    /** Recognises the digits spoken in the clip, in order. Never null; empty digits when nothing was recognised. */
    Recognition recognize(AudioClip clip);

    @Override
    default void close() {}
}
