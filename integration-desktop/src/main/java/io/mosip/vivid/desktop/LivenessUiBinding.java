package io.mosip.vivid.desktop;

import io.mosip.vivid.api.Challenge;
import io.mosip.vivid.api.FailureReason;
import io.mosip.vivid.api.SessionState;
import io.mosip.vivid.device.DeviceHealth;
import io.mosip.vivid.device.FaceFrame;

import java.util.Map;

/**
 * What the Registration Client's JavaFX controller implements so that {@link LivenessGate} can drive the screen.
 * <p>
 * All methods are invoked from the device delivery thread or the gate's timer thread, never from the JavaFX
 * application thread. Implementations must marshal to the UI thread themselves (see
 * {@code io.mosip.vivid.desktop.ui.LivenessOverlayController}). Every method must return quickly.
 * <p>
 * Text is never passed as strings: the binding receives enum constants (or i18n keys from {@link LivenessMessages})
 * and looks the localised text up itself, so the same gate works for every bundle language.
 */
public interface LivenessUiBinding {

    /** Called on every state transition with the full snapshot. Use it for the face guide colour and progress. */
    void showState(SessionState state);

    /** A new active challenge was issued; render the prompt card for {@code challenge.type()}. */
    void showChallenge(Challenge challenge);

    /** Positioning / feedback hint chosen by the orchestrator ("Please continue", "Hold still", ...). */
    void showHint(SessionState.Hint hint);

    /**
     * The attempt failed. {@code messageKey} is already the i18n key to show; for security-sensitive reasons
     * (PAD, identity drift) it is the generic key, never the specific one. Do not derive text from {@code reason}.
     */
    void showFailure(FailureReason reason, String messageKey);

    /** Retry counter, 1-based attempt of max. */
    void showRetry(int attempt, int maxAttempts);

    /** Max retries reached and policy says lock: show recovery guidance, disable capture controls. */
    void showLocked();

    /** Session passed. Default no-op because {@link #showState} already carries PASSED. */
    default void showPassed() {}

    /** Preview frame. The gate forwards every frame it feeds to the engine so the overlay can render it. */
    default void showFrame(FaceFrame frame) {}

    /** Device connection state for the status chip. */
    default void showDeviceHealth(DeviceHealth health) {}

    /** Per-signal scores and timings; only ever shown when diagnostics mode is enabled by policy. */
    default void showDiagnostics(Map<String, String> values) {}

    /** No-op binding for headless use and tests. */
    LivenessUiBinding NONE = new LivenessUiBinding() {
        @Override public void showState(SessionState state) {}
        @Override public void showChallenge(Challenge challenge) {}
        @Override public void showHint(SessionState.Hint hint) {}
        @Override public void showFailure(FailureReason reason, String messageKey) {}
        @Override public void showRetry(int attempt, int maxAttempts) {}
        @Override public void showLocked() {}
    };
}
