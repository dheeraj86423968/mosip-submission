package io.mosip.vivid.engine.signal;

import io.mosip.vivid.api.Challenge;
import io.mosip.vivid.api.SignalScore;

import java.util.Optional;

/**
 * One anti-spoofing scorer. Instances are per analyzer (per session) and may keep temporal state;
 * expensive resources (ONNX sessions) are shared through the constructor. Not thread-safe.
 */
public interface Signal {
    /** Canonical id from {@code io.mosip.vivid.api.Signals}. */
    String id();

    /**
     * Scores the primary face of the current frame. Must always return a score; when the signal has no evidence
     * yet it returns {@code attackProbability = 0.5, confidence = 0} (see {@link #neutral(String)}).
     */
    SignalScore score(FaceContext ctx);

    /** Score that does not need a face (camera-level checks). Default: nothing. */
    default Optional<SignalScore> scoreWithoutFace(long timestampNanos) { return Optional.empty(); }

    /** Called when the orchestrator issues a challenge. */
    default void onChallenge(Challenge challenge) {}

    /** Called for frames where no single face was found, so windowed signals can note the gap. */
    default void onFaceLost(long timestampNanos) {}

    /** Clears temporal state. */
    void reset();

    /** "No evidence" score: ignored by a confidence-weighted fusion. */
    static SignalScore neutral(String id) { return SignalScore.of(id, 0.5, 0.0); }
}
