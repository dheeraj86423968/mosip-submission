package io.mosip.vivid.desktop;

import io.mosip.vivid.api.Challenge;
import io.mosip.vivid.api.ChallengeOutcome;
import io.mosip.vivid.api.FailureReason;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.LivenessResult;
import io.mosip.vivid.api.LivenessSession;
import io.mosip.vivid.api.SessionContext;
import io.mosip.vivid.api.SessionState;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.desktop.spi.LivenessSessionProvider;
import io.mosip.vivid.device.DeviceHealth;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.FaceFrameSource;
import io.mosip.vivid.device.FrameSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The one entry point the Registration Client calls:
 * <pre>
 *   LivenessResult r = gate.verify(Workflow.RESIDENT, frameSource, overlayController);
 *   if (r.accepted()) { ...call rcapture... }
 * </pre>
 * {@code verify} blocks until the session is terminal, so call it from a JavaFX {@code Task} / worker thread,
 * never from the FX application thread. It owns the full life cycle: session context (fresh nonce), policy from
 * config, start/stop of the frame source, timers for timeouts while the device stalls, retries with the
 * configured delay, lock on max retries, audit of every transition, and translation of state into
 * {@link LivenessUiBinding} calls. The UI never decides anything.
 */
public final class LivenessGate {
    private static final Logger LOG = LoggerFactory.getLogger(LivenessGate.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Per-call options; all optional. */
    public static final class Options {
        private boolean online = true;
        private boolean assistedCapture;
        private Locale locale;
        private String auditRefId;
        public static Options defaults() { return new Options(); }
        public Options online(boolean v) { online = v; return this; }
        /** Operator declared the resident cannot perform motor challenges: policy's accessibility pool is used. */
        public Options assistedCapture(boolean v) { assistedCapture = v; return this; }
        public Options locale(Locale v) { locale = v; return this; }
        /** Audit reference (user id or registration id); defaults to the session id. */
        public Options auditRefId(String v) { auditRefId = v; return this; }
    }

    private final LivenessSessionProvider sessions;
    private final DesktopLivenessConfig config;
    private final LivenessAuditAdapter audit;
    private final ScheduledExecutorService timer;
    private final AtomicReference<Run> current = new AtomicReference<>();

    public LivenessGate(LivenessSessionProvider sessions, DesktopLivenessConfig config, LivenessAuditAdapter audit) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.config = Objects.requireNonNull(config, "config");
        this.audit = audit == null ? LivenessAuditAdapter.NOOP : audit;
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "VIVID-LIVENESS-TIMER"); t.setDaemon(true); return t; });
    }

    public DesktopLivenessConfig config() { return config; }

    public boolean isEnabled(Workflow workflow) { return config.enabled(workflow); }

    public LivenessResult verify(Workflow workflow, FaceFrameSource source, LivenessUiBinding ui) {
        return verify(workflow, source, ui, Options.defaults());
    }

    public LivenessResult verify(Workflow workflow, FaceFrameSource source, LivenessUiBinding ui, Options options) {
        Objects.requireNonNull(workflow); Objects.requireNonNull(source);
        LivenessUiBinding binding = ui == null ? LivenessUiBinding.NONE : ui;
        Options opts = options == null ? Options.defaults() : options;
        LivenessPolicy policy = config.policy(workflow);
        String sessionId = UUID.randomUUID().toString();
        String refId = opts.auditRefId != null ? opts.auditRefId : sessionId;

        if (!policy.enabled()) {
            LOG.warn("liveness disabled by policy for {}; capture proceeds WITHOUT liveness (audited)", workflow);
            audit.audit(DesktopAuditEvent.POLICY_DISABLED, refId, Map.of("workflow", workflow.name(), "policyVersion", policy.policyVersion()));
            return bypassResult(sessionId, workflow, policy);
        }

        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        SessionContext ctx = new SessionContext(workflow, sessionId, nonce, opts.locale != null ? opts.locale : config.locale(), opts.online, opts.assistedCapture);
        Run run = new Run(ctx, policy, source, binding, refId);
        Run previous = current.getAndSet(run);
        if (previous != null) previous.cancel("superseded");
        try {
            return run.execute();
        } finally {
            current.compareAndSet(run, null);
        }
    }

    /** Cancel the session in progress (user pressed close / back). Safe from any thread. */
    public void cancel() {
        Run r = current.get();
        if (r != null) r.cancel("user");
    }

    public void shutdown() { timer.shutdownNow(); }

    private LivenessResult bypassResult(String sessionId, Workflow workflow, LivenessPolicy policy) {
        return new LivenessResult(sessionId, workflow, LivenessResult.Decision.LIVE, FailureReason.POLICY_DISABLED, Double.NaN,
                Map.of(), null, null, java.util.List.of(), 0, 0, 0, 0, "none", "none", policy.policyVersion(), "", null, "");
    }

    // ------------------------------------------------------------------------------------------------------------

    private final class Run implements FrameSink, LivenessSession.SessionListener {
        private final SessionContext ctx;
        private final LivenessPolicy policy;
        private final FaceFrameSource source;
        private final LivenessUiBinding ui;
        private final String refId;
        private final Object lock = new Object();
        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicBoolean finished = new AtomicBoolean();
        private final long startedNanos = System.nanoTime();
        private LivenessSession session;
        private ScheduledFuture<?> ticker;
        private volatile Challenge lastChallenge;
        private volatile SessionState.Hint lastHint = SessionState.Hint.NONE;
        private volatile SessionState.Phase lastPhase = SessionState.Phase.IDLE;
        private volatile DeviceHealth deviceFailure;
        private volatile boolean cancelled;
        private volatile boolean passiveAudited;
        private final String nonceHex;

        Run(SessionContext ctx, LivenessPolicy policy, FaceFrameSource source, LivenessUiBinding ui, String refId) {
            this.ctx = ctx; this.policy = policy; this.source = source; this.ui = ui; this.refId = refId;
            this.nonceHex = hex(ctx.nonce());
        }

        LivenessResult execute() {
            audit.audit(DesktopAuditEvent.POLICY_LOADED, refId, Map.of("policyVersion", policy.policyVersion(), "workflow", ctx.workflow().name()));
            try {
                session = sessions.open(ctx, policy);
            } catch (RuntimeException e) {
                LOG.error("could not open liveness session", e);
                audit.audit(DesktopAuditEvent.SESSION_FAILED, refId, Map.of("reason", FailureReason.ENGINE_ERROR.name(), "detail", String.valueOf(e)));
                ui.showFailure(FailureReason.ENGINE_ERROR, LivenessMessages.keyForFailure(FailureReason.ENGINE_ERROR));
                return errorResult(FailureReason.ENGINE_ERROR, null);
            }
            session.addListener(this);
            audit.audit(DesktopAuditEvent.SESSION_STARTED, refId, details("nonce", nonceHex, "workflow", ctx.workflow().name(), "assisted", String.valueOf(ctx.assistedCapture()), "online", String.valueOf(ctx.online())));
            ui.showState(session.state());
            ui.showDeviceHealth(DeviceHealth.connecting());
            ui.showHint(SessionState.Hint.CHECKING_LIVENESS);

            try {
                source.start(this, config.streamOptions());
            } catch (RuntimeException e) {
                LOG.error("frame source failed to start", e);
                deviceFailure = DeviceHealth.failed(String.valueOf(e.getMessage()));
                finish();
            }
            long tick = config.tickMillis();
            ticker = timer.scheduleAtFixedRate(this::tick, tick, tick, TimeUnit.MILLISECONDS);

            long budget = policy.sessionTimeoutMillis() * Math.max(1, policy.maxRetries() + 1) + 15_000L;
            try {
                if (!done.await(budget, TimeUnit.MILLISECONDS)) {
                    LOG.warn("liveness gate watchdog fired after {} ms; cancelling session", budget);
                    synchronized (lock) { session.cancel(); }
                    finish();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancel("interrupted");
            } finally {
                if (ticker != null) ticker.cancel(false);
                try { source.stop(); } catch (RuntimeException e) { LOG.warn("source.stop failed", e); }
            }

            LivenessResult result;
            synchronized (lock) {
                try { result = session.result(); }
                catch (RuntimeException e) { LOG.error("session.result() failed", e); result = errorResult(FailureReason.ENGINE_ERROR, null); }
                try { session.close(); } catch (RuntimeException e) { LOG.warn("session.close failed", e); }
            }
            if (deviceFailure != null && !result.accepted()) {
                FailureReason reason = deviceFailure.state() == DeviceHealth.State.FAILED ? FailureReason.DEVICE_CONNECTION_FAILURE : FailureReason.DEVICE_UNAVAILABLE;
                result = errorResult(reason, result);
                audit.audit(DesktopAuditEvent.DEVICE_FAULT, refId, details("state", deviceFailure.state().name(), "reason", deviceFailure.reason()));
            }
            auditFinal(result);
            return result;
        }

        private void tick() {
            if (finished.get()) return;
            synchronized (lock) {
                if (finished.get()) return;
                try { session.tick(); } catch (RuntimeException e) { LOG.warn("session.tick failed", e); }
            }
        }

        // ---- FrameSink (device thread) ----
        @Override public void onFrame(FaceFrame frame) {
            if (finished.get()) return;
            ui.showFrame(frame);
            synchronized (lock) {
                if (finished.get()) return;
                try { session.feed(frame); }
                catch (RuntimeException e) {
                    LOG.error("session.feed failed", e);
                    ui.showFailure(FailureReason.ENGINE_ERROR, LivenessMessages.keyForFailure(FailureReason.ENGINE_ERROR));
                    session.cancel();
                    finish();
                }
            }
        }

        @Override public void onHealth(DeviceHealth health) {
            ui.showDeviceHealth(health);
            if (health.state() == DeviceHealth.State.RECONNECTING) ui.showHint(SessionState.Hint.DEVICE_RECONNECTING);
            if (health.state() == DeviceHealth.State.DISCONNECTED || health.state() == DeviceHealth.State.FAILED) {
                if (finished.get()) return;
                deviceFailure = health;
                ui.showHint(SessionState.Hint.DEVICE_DISCONNECTED);
                synchronized (lock) { if (!finished.get()) session.cancel(); }
                finish();
            }
        }

        @Override public void onEnd() {
            if (finished.get()) return;
            deviceFailure = DeviceHealth.disconnected("stream ended");
            synchronized (lock) { if (!finished.get()) session.cancel(); }
            finish();
        }

        // ---- SessionListener (called inside feed/tick/retry, under lock) ----
        @Override public void onStateChanged(SessionState previous, SessionState current) {
            ui.showState(current);
            if (current.hint() != lastHint && current.hint() != SessionState.Hint.NONE) {
                lastHint = current.hint();
                ui.showHint(current.hint());
            }
            SessionState.Phase phase = current.phase();
            if (phase == SessionState.Phase.ACTIVE_CHALLENGE && current.challenge().isPresent()) {
                Challenge c = current.challenge().get();
                if (!passiveAudited) {
                    passiveAudited = true;
                    audit.audit(DesktopAuditEvent.PASSIVE_BELOW_THRESHOLD, refId, details("passiveScore", fmt(current.passiveScore())));
                }
                if (lastChallenge == null || lastChallenge.index() != c.index() || lastChallenge.issuedAtNanos() != c.issuedAtNanos()) {
                    lastChallenge = c;
                    ui.showChallenge(c);
                    audit.audit(DesktopAuditEvent.CHALLENGE_ISSUED, refId, details("type", c.type().name(), "index", String.valueOf(c.index()), "total", String.valueOf(c.total()), "nonce", nonceHex));
                }
            }
            if (phase == SessionState.Phase.PASSED && lastPhase != SessionState.Phase.PASSED) {
                if (!passiveAudited) { passiveAudited = true; audit.audit(DesktopAuditEvent.PASSIVE_PASSED, refId, details("passiveScore", fmt(current.passiveScore()))); }
                ui.showHint(SessionState.Hint.CAPTURED_OK);
                ui.showPassed();
                finish();
            } else if (phase == SessionState.Phase.FAILED && lastPhase != SessionState.Phase.FAILED) {
                FailureReason reason = current.failure().orElse(FailureReason.LIVENESS_BELOW_THRESHOLD);
                audit.audit(DesktopAuditEvent.SESSION_FAILED, refId, details("reason", reason.name(), "attempt", String.valueOf(current.attempt()), "passiveScore", fmt(current.passiveScore())));
                if (reason == FailureReason.PAD_DETECTED) audit.audit(DesktopAuditEvent.PAD_DETECTED, refId, details("attempt", String.valueOf(current.attempt())));
                if (reason == FailureReason.IDENTITY_DRIFT) audit.audit(DesktopAuditEvent.IDENTITY_DRIFT, refId, details("attempt", String.valueOf(current.attempt())));
                if (reason == FailureReason.CHALLENGE_TIMEOUT) audit.audit(DesktopAuditEvent.CHALLENGE_TIMEOUT, refId, details("attempt", String.valueOf(current.attempt())));
                ui.showFailure(reason, LivenessMessages.keyForFailure(reason));
                if (reason == FailureReason.CANCELLED || reason == FailureReason.POLICY_DISABLED) {
                    finish();
                } else if (current.canRetry()) {
                    ui.showRetry(current.attempt(), current.maxAttempts());
                    long delay = config.retryDelayMillis(ctx.workflow());
                    timer.schedule(this::retry, delay, TimeUnit.MILLISECONDS);
                } else {
                    lock(current);
                }
            } else if (phase == SessionState.Phase.LOCKED && lastPhase != SessionState.Phase.LOCKED) {
                lock(current);
            } else if (phase == SessionState.Phase.CANCELLED) {
                finish();
            }
            lastPhase = phase;
        }

        private void retry() {
            if (finished.get()) return;
            boolean ok;
            synchronized (lock) {
                if (finished.get()) return;
                try { ok = session.retry(); } catch (RuntimeException e) { LOG.error("session.retry failed", e); ok = false; }
                if (ok) { lastChallenge = null; passiveAudited = false; lastHint = SessionState.Hint.NONE; ui.showHint(SessionState.Hint.CHECKING_LIVENESS); }
            }
            if (!ok) lock(session.state());
        }

        private void lock(SessionState state) {
            if (finished.get()) return;
            audit.audit(DesktopAuditEvent.MAX_RETRIES_REACHED, refId, details("attempts", String.valueOf(state.attempt()), "action", policy.onMaxRetries().name()));
            ui.showHint(SessionState.Hint.MAX_RETRIES_REACHED);
            ui.showLocked();
            finish();
        }

        void cancel(String why) {
            if (finished.get()) return;
            cancelled = true;
            LOG.info("liveness session {} cancelled ({})", ctx.sessionId(), why);
            synchronized (lock) { if (session != null && !finished.get()) { try { session.cancel(); } catch (RuntimeException ignored) { } } }
            finish();
        }

        private void finish() {
            if (finished.compareAndSet(false, true)) done.countDown();
        }

        private void auditFinal(LivenessResult r) {
            for (ChallengeOutcome o : r.challenges()) {
                DesktopAuditEvent ev = o.result() == ChallengeOutcome.Result.PASSED ? DesktopAuditEvent.CHALLENGE_PASSED
                        : o.result() == ChallengeOutcome.Result.TIMEOUT ? DesktopAuditEvent.CHALLENGE_TIMEOUT : null;
                if (ev != null) audit.audit(ev, refId, details("type", o.challenge().type().name(), "index", String.valueOf(o.challenge().index()), "latencyMs", String.valueOf(o.latencyMillis()), "durationMs", String.valueOf(o.durationMillis())));
            }
            Map<String, String> d = details("decision", r.decision().name(), "fused", fmt(r.fusedLivenessScore()), "attempts", String.valueOf(r.attemptsUsed()),
                    "passiveMs", String.valueOf(r.passiveMillis()), "totalMs", String.valueOf(r.totalMillis()), "frames", String.valueOf(r.framesAnalyzed()),
                    "engine", String.valueOf(r.engineId()), "model", String.valueOf(r.modelBundleVersion()), "policy", String.valueOf(r.policyVersion()),
                    "nonce", String.valueOf(r.nonceHex()), "evidenceHash", String.valueOf(r.evidenceHash()));
            r.failure().ifPresent(f -> d.put("reason", f.name()));
            r.vetoSignal().ifPresent(v -> d.put("veto", v));
            r.attackClass().ifPresent(a -> d.put("attackClass", a));
            r.signalScores().forEach((k, v) -> d.put("signal." + k, fmt(v)));
            if (r.accepted()) audit.audit(DesktopAuditEvent.SESSION_PASSED, refId, d);
            else if (cancelled) audit.audit(DesktopAuditEvent.SESSION_FAILED, refId, d);
            if (config.diagnosticsEnabled()) ui.showDiagnostics(d);
        }

        private LivenessResult errorResult(FailureReason reason, LivenessResult base) {
            long total = (System.nanoTime() - startedNanos) / 1_000_000L;
            if (base == null) {
                return new LivenessResult(ctx.sessionId(), ctx.workflow(), LivenessResult.Decision.ERROR, reason, 0, Map.of(), null, null,
                        java.util.List.of(), 0, 0, total, 0, "none", "none", policy.policyVersion(), nonceHex, null, "");
            }
            return new LivenessResult(base.sessionId(), base.workflow(), LivenessResult.Decision.ERROR, reason, base.fusedLivenessScore(), base.signalScores(),
                    base.vetoSignal().orElse(null), base.attackClass().orElse(null), base.challenges(), base.attemptsUsed(), base.passiveMillis(),
                    base.totalMillis() > 0 ? base.totalMillis() : total, base.framesAnalyzed(), base.engineId(), base.modelBundleVersion(), base.policyVersion(),
                    base.nonceHex(), base.anchorEmbedding().orElse(null), base.evidenceHash());
        }
    }

    private static Map<String, String> details(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1] == null ? "" : kv[i + 1]);
        return m;
    }

    static String hex(byte[] b) { StringBuilder sb = new StringBuilder(b.length * 2); for (byte x : b) sb.append(String.format("%02x", x)); return sb.toString(); }

    private static String fmt(double v) { return Double.isNaN(v) ? "nan" : String.format(Locale.ROOT, "%.4f", v); }
}
