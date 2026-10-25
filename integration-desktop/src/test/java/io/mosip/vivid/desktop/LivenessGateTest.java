package io.mosip.vivid.desktop;

import io.mosip.vivid.api.Challenge;
import io.mosip.vivid.api.ChallengeOutcome;
import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.FailureReason;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.LivenessResult;
import io.mosip.vivid.api.LivenessSession;
import io.mosip.vivid.api.SessionContext;
import io.mosip.vivid.api.SessionState;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.desktop.spi.LivenessSessionProvider;
import io.mosip.vivid.device.DeviceCapabilities;
import io.mosip.vivid.device.DeviceHealth;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.FaceFrameSource;
import io.mosip.vivid.device.FrameSink;
import io.mosip.vivid.device.PixelFormat;
import io.mosip.vivid.device.StreamOptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class LivenessGateTest {

    /** A session whose state is a pure function of (attempt, frames fed in this attempt). */
    static final class ScriptedSession implements LivenessSession {
        final SessionContext ctx; final LivenessPolicy policy; final Function<int[], SessionState> script;
        final List<SessionListener> listeners = new ArrayList<>();
        int attempt = 1, fed = 0; SessionState state; boolean cancelled;
        ScriptedSession(SessionContext ctx, LivenessPolicy policy, Function<int[], SessionState> script) {
            this.ctx = ctx; this.policy = policy; this.script = script;
            this.state = new SessionState(SessionState.Phase.FACE_ACQUIRING, SessionState.Hint.CENTER_FACE, null, null, 1, policy.maxRetries(), 0, 0, 0);
        }
        private SessionState move(SessionState next) { SessionState prev = state; state = next; if (!prev.toString().equals(next.toString())) listeners.forEach(l -> l.onStateChanged(prev, next)); return next; }
        @Override public SessionContext context() { return ctx; }
        @Override public SessionState feed(FaceFrame frame) { if (state.isTerminal() || state.phase() == SessionState.Phase.FAILED) return state; fed++; return move(script.apply(new int[] { attempt, fed })); }
        @Override public SessionState tick() { return state; }
        @Override public SessionState state() { return state; }
        @Override public boolean retry() {
            if (state.phase() != SessionState.Phase.FAILED || attempt >= policy.maxRetries()) {
                move(new SessionState(SessionState.Phase.LOCKED, SessionState.Hint.MAX_RETRIES_REACHED, null, state.failure().orElse(null), attempt, policy.maxRetries(), 1, 0, 0));
                return false;
            }
            attempt++; fed = 0;
            move(new SessionState(SessionState.Phase.FACE_ACQUIRING, SessionState.Hint.CENTER_FACE, null, null, attempt, policy.maxRetries(), 0, 0, 0));
            return true;
        }
        @Override public void cancel() { cancelled = true; move(new SessionState(SessionState.Phase.CANCELLED, SessionState.Hint.NONE, null, FailureReason.CANCELLED, attempt, policy.maxRetries(), 0, 0, 0)); }
        @Override public LivenessResult result() {
            boolean live = state.phase() == SessionState.Phase.PASSED;
            List<ChallengeOutcome> outcomes = state.challenge().map(c -> List.of(new ChallengeOutcome(c, ChallengeOutcome.Result.PASSED, 300, 900))).orElse(List.of());
            return new LivenessResult(ctx.sessionId(), ctx.workflow(), live ? LivenessResult.Decision.LIVE : cancelled ? LivenessResult.Decision.CANCELLED : LivenessResult.Decision.NOT_LIVE,
                    live ? null : state.failure().orElse(FailureReason.LIVENESS_BELOW_THRESHOLD), live ? 0.95 : 0.3, Map.of("cnn_texture", 0.04), null, null, outcomes,
                    attempt, 2500, 5000, fed, "scripted", "1", policy.policyVersion(), "ab", live ? new float[] { 1, 0 } : null, "h");
        }
        @Override public void addListener(SessionListener listener) { listeners.add(listener); }
        @Override public void close() {}
    }

    /** Pushes N synthetic frames from its own thread, then ends. */
    static final class FakeSource implements FaceFrameSource {
        final int frames; final boolean endAfter; volatile DeviceHealth health = DeviceHealth.connecting(); final AtomicBoolean stopped = new AtomicBoolean();
        FakeSource(int frames, boolean endAfter) { this.frames = frames; this.endAfter = endAfter; }
        @Override public String adapterId() { return "fake"; }
        @Override public DeviceCapabilities capabilities() { return DeviceCapabilities.builder().build(); }
        @Override public void start(FrameSink sink, StreamOptions options) {
            Thread t = new Thread(() -> {
                health = DeviceHealth.connected(); sink.onHealth(health);
                for (int i = 1; i <= frames && !stopped.get(); i++) {
                    sink.onFrame(FaceFrame.builder().sequence(i).timestampNanos(System.nanoTime()).width(4).height(4).format(PixelFormat.RGB888).pixels(new byte[48]).build());
                    try { Thread.sleep(5); } catch (InterruptedException e) { return; }
                }
                if (endAfter && !stopped.get()) { health = DeviceHealth.disconnected("unplugged"); sink.onHealth(health); sink.onEnd(); }
            }, "fake-source");
            t.setDaemon(true); t.start();
        }
        @Override public void stop() { stopped.set(true); }
        @Override public DeviceHealth health() { return health; }
    }

    static final class RecordingUi implements LivenessUiBinding {
        final List<String> events = new CopyOnWriteArrayList<>();
        final AtomicInteger frames = new AtomicInteger();
        @Override public void showState(SessionState s) { events.add("state:" + s.phase()); }
        @Override public void showChallenge(Challenge c) { events.add("challenge:" + c.type()); }
        @Override public void showHint(SessionState.Hint h) { events.add("hint:" + h); }
        @Override public void showFailure(FailureReason r, String key) { events.add("failure:" + r + ":" + key); }
        @Override public void showRetry(int a, int m) { events.add("retry:" + a + "/" + m); }
        @Override public void showLocked() { events.add("locked"); }
        @Override public void showPassed() { events.add("passed"); }
        @Override public void showFrame(FaceFrame f) { frames.incrementAndGet(); }
        @Override public void showDeviceHealth(DeviceHealth h) { events.add("device:" + h.state()); }
    }

    static final class RecordingAudit implements LivenessAuditAdapter {
        final List<DesktopAuditEvent> events = new CopyOnWriteArrayList<>();
        final List<Map<String, String>> details = new CopyOnWriteArrayList<>();
        @Override public void audit(DesktopAuditEvent e, String refId, Map<String, String> d) { events.add(e); details.add(d); }
    }

    static DesktopLivenessConfig config(String... kv) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put(DesktopLivenessConfig.Keys.ROOT + "retry.delay_ms", "20");
        m.put(DesktopLivenessConfig.Keys.ROOT + "tick_ms", "50");
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return DesktopLivenessConfig.fromApplicationMap(m);
    }

    static SessionState st(SessionState.Phase p, SessionState.Hint h, Challenge c, FailureReason f, int attempt, int max) {
        return new SessionState(p, h, c, f, attempt, max, 0.5, 0.8, 5000);
    }

    @Test
    void passiveThenChallengeThenPassedDrivesUiAndAudit() {
        Challenge blink = new Challenge(ChallengeType.BLINK, 0, 1, 1, 8000);
        LivenessSessionProvider provider = (ctx, policy) -> new ScriptedSession(ctx, policy, s -> {
            int fed = s[1];
            if (fed < 3) return st(SessionState.Phase.PASSIVE_EVALUATING, SessionState.Hint.CHECKING_LIVENESS, null, null, 1, 3);
            if (fed < 6) return st(SessionState.Phase.ACTIVE_CHALLENGE, SessionState.Hint.NONE, blink, null, 1, 3);
            if (fed == 6) return st(SessionState.Phase.ACTIVE_CHALLENGE, SessionState.Hint.ACTION_DETECTED, blink, null, 1, 3);
            return st(SessionState.Phase.PASSED, SessionState.Hint.CAPTURED_OK, blink, null, 1, 3);
        });
        RecordingUi ui = new RecordingUi(); RecordingAudit audit = new RecordingAudit();
        LivenessGate gate = new LivenessGate(provider, config(), audit);
        LivenessResult r = gate.verify(Workflow.RESIDENT, new FakeSource(50, false), ui, LivenessGate.Options.defaults().auditRefId("user-1"));

        assertThat(r.accepted()).isTrue();
        assertThat(r.anchorEmbedding()).isPresent();
        assertThat(ui.frames.get()).isGreaterThanOrEqualTo(7);
        assertThat(ui.events).contains("device:CONNECTED", "hint:CHECKING_LIVENESS", "state:PASSIVE_EVALUATING", "challenge:BLINK", "hint:ACTION_DETECTED", "state:PASSED", "hint:CAPTURED_OK", "passed");
        assertThat(ui.events).doesNotContain("locked");
        assertThat(ui.events.stream().filter(e -> e.startsWith("challenge:")).count()).isEqualTo(1);
        assertThat(audit.events).containsSubsequence(DesktopAuditEvent.POLICY_LOADED, DesktopAuditEvent.SESSION_STARTED, DesktopAuditEvent.PASSIVE_BELOW_THRESHOLD, DesktopAuditEvent.CHALLENGE_ISSUED, DesktopAuditEvent.CHALLENGE_PASSED, DesktopAuditEvent.SESSION_PASSED);
        Map<String, String> started = audit.details.get(audit.events.indexOf(DesktopAuditEvent.SESSION_STARTED));
        assertThat(started.get("nonce")).hasSize(32);
        gate.shutdown();
    }

    @Test
    void failedAttemptsRetryUntilLockedWithGenericMessageForPad() {
        LivenessSessionProvider provider = (ctx, policy) -> new ScriptedSession(ctx, policy, s -> {
            int attempt = s[0], fed = s[1];
            if (fed < 2) return st(SessionState.Phase.PASSIVE_EVALUATING, SessionState.Hint.CHECKING_LIVENESS, null, null, attempt, 2);
            return st(SessionState.Phase.FAILED, SessionState.Hint.TRY_AGAIN, null, FailureReason.PAD_DETECTED, attempt, 2);
        });
        RecordingUi ui = new RecordingUi(); RecordingAudit audit = new RecordingAudit();
        LivenessGate gate = new LivenessGate(provider, config(DesktopLivenessConfig.Keys.ROOT + "retries", "2"), audit);
        LivenessResult r = gate.verify(Workflow.OPERATOR, new FakeSource(200, false), ui);

        assertThat(r.accepted()).isFalse();
        assertThat(r.failure()).contains(FailureReason.PAD_DETECTED);
        assertThat(ui.events).contains("failure:PAD_DETECTED:" + LivenessMessages.GENERIC_FAILURE, "retry:1/2", "locked", "hint:MAX_RETRIES_REACHED");
        assertThat(ui.events.stream().filter(e -> e.startsWith("failure:")).count()).isEqualTo(2);
        assertThat(audit.events).contains(DesktopAuditEvent.PAD_DETECTED, DesktopAuditEvent.MAX_RETRIES_REACHED);
        assertThat(audit.events.stream().filter(e -> e == DesktopAuditEvent.SESSION_FAILED).count()).isGreaterThanOrEqualTo(2);
        gate.shutdown();
    }

    @Test
    void deviceDisconnectBecomesADeviceFailureResult() {
        LivenessSessionProvider provider = (ctx, policy) -> new ScriptedSession(ctx, policy, s -> st(SessionState.Phase.PASSIVE_EVALUATING, SessionState.Hint.CHECKING_LIVENESS, null, null, 1, 3));
        RecordingUi ui = new RecordingUi(); RecordingAudit audit = new RecordingAudit();
        LivenessGate gate = new LivenessGate(provider, config(), audit);
        LivenessResult r = gate.verify(Workflow.RESIDENT, new FakeSource(3, true), ui);
        assertThat(r.accepted()).isFalse();
        assertThat(r.decision()).isEqualTo(LivenessResult.Decision.ERROR);
        assertThat(r.failure()).contains(FailureReason.DEVICE_UNAVAILABLE);
        assertThat(ui.events).contains("device:DISCONNECTED", "hint:DEVICE_DISCONNECTED");
        assertThat(audit.events).contains(DesktopAuditEvent.DEVICE_FAULT);
        gate.shutdown();
    }

    @Test
    void disabledPolicyBypassesWithAnAuditedResult() {
        AtomicBoolean opened = new AtomicBoolean();
        LivenessSessionProvider provider = (ctx, policy) -> { opened.set(true); throw new AssertionError("must not open"); };
        RecordingAudit audit = new RecordingAudit();
        LivenessGate gate = new LivenessGate(provider, config(DesktopLivenessConfig.Keys.ROOT + "resident.enabled", "N"), audit);
        LivenessResult r = gate.verify(Workflow.RESIDENT, new FakeSource(1, false), new RecordingUi());
        assertThat(opened).isFalse();
        assertThat(r.accepted()).isTrue();
        assertThat(r.failure()).contains(FailureReason.POLICY_DISABLED);
        assertThat(audit.events).containsExactly(DesktopAuditEvent.POLICY_DISABLED);
        assertThat(gate.isEnabled(Workflow.OPERATOR)).isTrue();
        gate.shutdown();
    }

    @Test
    void cancelFromAnotherThreadEndsTheSession() throws Exception {
        LivenessSessionProvider provider = (ctx, policy) -> new ScriptedSession(ctx, policy, s -> st(SessionState.Phase.FACE_ACQUIRING, SessionState.Hint.CENTER_FACE, null, null, 1, 3));
        LivenessGate gate = new LivenessGate(provider, config(), LivenessAuditAdapter.logging());
        RecordingUi ui = new RecordingUi();
        Thread canceller = new Thread(() -> { try { Thread.sleep(150); } catch (InterruptedException ignored) { } gate.cancel(); });
        canceller.start();
        LivenessResult r = gate.verify(Workflow.SUPERVISOR, new FakeSource(10_000, false), ui);
        assertThat(r.accepted()).isFalse();
        assertThat(r.decision()).isEqualTo(LivenessResult.Decision.CANCELLED);
        assertThat(ui.events).contains("state:CANCELLED");
        canceller.join();
        gate.shutdown();
    }

    @Test
    void sessionOpenFailureIsReportedAsEngineError() {
        LivenessSessionProvider provider = (ctx, policy) -> { throw new IllegalStateException("model bundle missing"); };
        RecordingUi ui = new RecordingUi();
        LivenessGate gate = new LivenessGate(provider, config(), LivenessAuditAdapter.NOOP);
        LivenessResult r = gate.verify(Workflow.RESIDENT, new FakeSource(1, false), ui);
        assertThat(r.decision()).isEqualTo(LivenessResult.Decision.ERROR);
        assertThat(r.failure()).contains(FailureReason.ENGINE_ERROR);
        assertThat(ui.events).contains("failure:ENGINE_ERROR:liveness.failure.engine_error");
        gate.shutdown();
    }
}
