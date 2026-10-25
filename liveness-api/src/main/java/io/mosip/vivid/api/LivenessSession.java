package io.mosip.vivid.api;

import io.mosip.vivid.device.FaceFrame;

/**
 * A running liveness session. The Registration Client feeds frames (or wires a FaceFrameSource to feed them),
 * observes state for the UI, and reads the result when terminal. Implemented by liveness-core's orchestrator.
 */
public interface LivenessSession extends AutoCloseable {
    SessionContext context();

    /** Feed one frame. Returns the new state. Safe to call from the device delivery thread. */
    SessionState feed(FaceFrame frame);

    /** Advance timers without a frame (used when the device stalls so timeouts still fire). */
    SessionState tick();

    SessionState state();

    /** Begin another attempt after FAILED, subject to policy. Returns false if not allowed. */
    boolean retry();

    void cancel();

    /** Available once the session is terminal or FAILED. */
    LivenessResult result();

    /** Register a listener for state changes; invoked synchronously on the feeding thread. */
    void addListener(SessionListener listener);

    @Override void close();

    interface SessionListener {
        void onStateChanged(SessionState previous, SessionState current);
    }
}
