package io.mosip.vivid.testkit.scenario;

import io.mosip.vivid.api.FailureReason;
import io.mosip.vivid.api.LivenessResult;
import io.mosip.vivid.api.LivenessSession;
import io.mosip.vivid.api.SessionContext;
import io.mosip.vivid.api.SessionState;
import io.mosip.vivid.device.FaceFrame;

import java.util.ArrayList;
import java.util.List;

/** A stand-in for liveness-core: passes after N frames, or fails with a given reason after N frames. */
final class FakeSession implements LivenessSession {
    private final SessionContext ctx;
    private final int framesToDecide;
    private final FailureReason failWith;   // null = pass
    private final List<SessionListener> listeners = new ArrayList<>();
    private SessionState state = new SessionState(SessionState.Phase.FACE_ACQUIRING, SessionState.Hint.CENTER_FACE, null, null, 1, 3, 0, 0, 0);
    private int frames;
    private int ticks;

    FakeSession(SessionContext ctx, int framesToDecide, FailureReason failWith) { this.ctx = ctx; this.framesToDecide = framesToDecide; this.failWith = failWith; }

    @Override public SessionContext context() { return ctx; }
    @Override public SessionState feed(FaceFrame frame) {
        frames++;
        if (frames == 1) set(new SessionState(SessionState.Phase.PASSIVE_EVALUATING, SessionState.Hint.CHECKING_LIVENESS, null, null, 1, 3, 0.2, 0.5, 0));
        if (frames >= framesToDecide && state.phase() == SessionState.Phase.PASSIVE_EVALUATING) {
            if (failWith == null) set(new SessionState(SessionState.Phase.PASSED, SessionState.Hint.CAPTURED_OK, null, null, 1, 3, 1, 0.95, 0));
            else set(new SessionState(SessionState.Phase.FAILED, SessionState.Hint.TRY_AGAIN, null, failWith, 1, 3, 1, 0.1, 0));
        }
        return state;
    }
    @Override public SessionState tick() { ticks++; return state; }
    @Override public SessionState state() { return state; }
    @Override public boolean retry() { return false; }
    @Override public void cancel() { }
    @Override public LivenessResult result() {
        if (state.phase() != SessionState.Phase.PASSED && state.phase() != SessionState.Phase.FAILED) throw new IllegalStateException("not terminal");
        boolean live = failWith == null;
        return new LivenessResult(ctx.sessionId(), ctx.workflow(), live ? LivenessResult.Decision.LIVE : (failWith == FailureReason.PAD_DETECTED ? LivenessResult.Decision.ATTACK : LivenessResult.Decision.NOT_LIVE),
                failWith, live ? 0.95 : 0.1, java.util.Map.of("fake", live ? 0.05 : 0.9), null, failWith == FailureReason.PAD_DETECTED ? "PRINT" : null,
                List.of(), 1, 100, 200, frames, "fake", "none", "test", "00", null, "deadbeef");
    }
    @Override public void addListener(SessionListener l) { listeners.add(l); }
    @Override public void close() { }
    int ticks() { return ticks; }
    private void set(SessionState s) { SessionState p = state; state = s; for (SessionListener l : listeners) l.onStateChanged(p, s); }
}
