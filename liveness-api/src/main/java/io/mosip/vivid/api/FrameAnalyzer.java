package io.mosip.vivid.api;

import io.mosip.vivid.device.FaceFrame;

/**
 * Stateful per-session analyzer created by {@link LivenessEngine#newAnalyzer}. Keeps temporal state for
 * windowed signals (rPPG, parallax, smoothing). Not thread-safe; one thread feeds it.
 */
public interface FrameAnalyzer extends AutoCloseable {
    /**
     * Analyze one frame. Throw {@link IllegalArgumentException} when the frame itself is unusable (undecodable,
     * truncated, wrong size): the session fails that attempt with {@code INVALID_FRAME_DATA}. Any other exception is
     * treated as an engine fault ({@code ENGINE_ERROR}).
     */
    FrameAnalysis analyze(FaceFrame frame);

    /** Tells the analyzer which challenge is active so motion-based signals (parallax) know what to expect. May be ignored. */
    default void onChallenge(Challenge challenge) {}

    /** Clear temporal state without releasing models. */
    void reset();

    @Override default void close() {}
}
