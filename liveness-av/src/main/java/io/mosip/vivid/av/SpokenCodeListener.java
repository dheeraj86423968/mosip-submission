package io.mosip.vivid.av;

/**
 * UI hook. The frozen {@code SessionState} cannot carry the code, so the client registers this listener on
 * {@link AvLivenessEngine} and overlays the prompt while the carrier challenge is active. Called on the feeding
 * thread (inside the orchestrator's lock); implementations must return quickly and must not call the session.
 */
public interface SpokenCodeListener {
    default void onPrompt(SpokenCodePrompt prompt) {}
    default void onVerdict(AvVerdict verdict) {}
}
