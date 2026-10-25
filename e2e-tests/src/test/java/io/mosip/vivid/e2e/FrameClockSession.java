package io.mosip.vivid.e2e;

import io.mosip.vivid.api.*;
import io.mosip.vivid.device.FaceFrame;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Delegating session that advances a shared clock to each frame's device timestamp before feeding it.
 * This lets unthrottled scenario replays exercise real timing logic (passive windows, challenge timeouts)
 * deterministically, without waiting in wall-clock time.
 */
final class FrameClockSession implements LivenessSession {
    private final LivenessSession delegate;
    private final AtomicLong clock;

    FrameClockSession(LivenessSession delegate, AtomicLong clock) { this.delegate = delegate; this.clock = clock; }

    @Override public SessionContext context() { return delegate.context(); }
    @Override public SessionState feed(FaceFrame frame) {
        clock.accumulateAndGet(frame.timestampNanos(), Math::max);
        return delegate.feed(frame);
    }
    @Override public SessionState tick() { return delegate.tick(); }
    @Override public SessionState state() { return delegate.state(); }
    @Override public boolean retry() { return delegate.retry(); }
    @Override public void cancel() { delegate.cancel(); }
    @Override public LivenessResult result() { return delegate.result(); }
    @Override public void addListener(SessionListener l) { delegate.addListener(l); }
    @Override public void close() { delegate.close(); }
}
