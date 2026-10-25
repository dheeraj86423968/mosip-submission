package io.mosip.vivid.core.session;

import io.mosip.vivid.api.Challenge;
import io.mosip.vivid.api.ChallengeOutcome;
import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.EngineInfo;
import io.mosip.vivid.api.FailureReason;
import io.mosip.vivid.api.FrameAnalysis;
import io.mosip.vivid.api.FrameAnalyzer;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.LivenessResult;
import io.mosip.vivid.api.LivenessSession;
import io.mosip.vivid.api.SessionContext;
import io.mosip.vivid.api.SessionState;
import io.mosip.vivid.api.SessionState.Hint;
import io.mosip.vivid.api.SessionState.Phase;
import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.core.action.ActionDetector;
import io.mosip.vivid.core.action.ActionDetectors;
import io.mosip.vivid.core.action.ActionStatus;
import io.mosip.vivid.core.action.ActionThresholds;
import io.mosip.vivid.core.action.UnpromptedActionMonitor;
import io.mosip.vivid.core.challenge.ChallengeSelector;
import io.mosip.vivid.core.challenge.ChallengeTiming;
import io.mosip.vivid.core.fusion.FusionResult;
import io.mosip.vivid.core.fusion.ScoreFusion;
import io.mosip.vivid.core.identity.IdentityContinuity;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.telemetry.audit.AuditRecord;
import io.mosip.vivid.telemetry.audit.AuditSink;
import io.mosip.vivid.telemetry.audit.LivenessAuditEvent;
import io.mosip.vivid.telemetry.diagnostics.DiagnosticSnapshot;
import io.mosip.vivid.telemetry.diagnostics.DiagnosticSource;
import io.mosip.vivid.telemetry.diagnostics.FpsMeter;
import io.mosip.vivid.telemetry.metrics.LivenessMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;
import java.util.Random;

/**
 * The liveness session state machine (PLAN.md section 2.3). The only component that knows about policy,
 * retries, timeouts and transitions; engines score frames, the UI renders {@link SessionState}.
 *
 * <pre>
 * IDLE ─start─▶ DEVICE_CONNECTING ─deviceReady─▶ FACE_ACQUIRING ─good frame─▶ PASSIVE_EVALUATING
 *   PASSIVE_EVALUATING ─score ≥ threshold, no veto─▶ PASSED           (or ACTIVE_CHALLENGE when mode = ALWAYS)
 *   PASSIVE_EVALUATING ─hard veto / fused collapse─▶ FAILED(PAD_DETECTED)
 *   PASSIVE_EVALUATING ─score &lt; threshold─▶ ACTIVE_CHALLENGE[0..n-1]  (or FAILED(LIVENESS_BELOW_THRESHOLD) when mode = NEVER)
 *   ACTIVE_CHALLENGE ─action verified─▶ next challenge | PASSED
 *   ACTIVE_CHALLENGE ─timeout─▶ FAILED(CHALLENGE_TIMEOUT); ─identity drift─▶ FAILED(IDENTITY_DRIFT); ─PAD─▶ FAILED(PAD_DETECTED)
 *   FAILED ─retry, attempt &lt; max─▶ FACE_ACQUIRING;  FAILED ─attempt == max─▶ LOCKED;  any ─cancel─▶ CANCELLED
 * </pre>
 *
 * <p>Passive fusion, identity continuity and PAD vetoes keep running during active challenges. All timing uses
 * the injected {@link TimeSource}; device frame timestamps are never trusted for timing. Every transition emits
 * the matching {@link LivenessAuditEvent}. Thread-safe through a single lock; listeners are invoked
 * synchronously on the feeding thread while the lock is held and must return quickly without calling back.</p>
 *
 * <p>When the policy disables liveness for the workflow, {@link #start()} moves straight to {@link Phase#PASSED}
 * with a result whose decision is {@code LIVE} but whose {@code failure} is {@link FailureReason#POLICY_DISABLED},
 * so the capture flow proceeds while audit can tell a verified-live from a policy bypass.</p>
 */
public final class LivenessOrchestrator implements LivenessSession, DiagnosticSource {

    private static final Logger LOG = LoggerFactory.getLogger(LivenessOrchestrator.class);
    private static final Set<FailureReason> ATTACK_REASONS = EnumSet.of(FailureReason.PAD_DETECTED, FailureReason.IDENTITY_DRIFT);
    private static final Set<FailureReason> DEVICE_REASONS = EnumSet.of(FailureReason.DEVICE_UNAVAILABLE,
            FailureReason.DEVICE_CONNECTION_FAILURE, FailureReason.CAMERA_UNAVAILABLE);
    private static final Set<FailureReason> ERROR_REASONS = EnumSet.of(FailureReason.DEVICE_UNAVAILABLE,
            FailureReason.DEVICE_CONNECTION_FAILURE, FailureReason.CAMERA_UNAVAILABLE, FailureReason.INVALID_FRAME_DATA,
            FailureReason.ENGINE_ERROR);
    /** Veto signal id reported when the fused score itself collapses rather than a single signal. */
    public static final String FUSION_VETO = "fusion";

    private final FrameAnalyzer analyzer;
    private final LivenessPolicy policy;
    private final SessionContext context;
    private final TimeSource time;
    private final AuditSink audit;
    private final LivenessMetrics metrics;
    private final EngineInfo engineInfo;
    private final ActionThresholds thresholds;
    private final DetectorFactory detectors;
    private final ChallengeSelector selector;
    private final ScoreFusion fusion;
    private final IdentityContinuity identity;
    private final QualityGate gate;
    private final FpsMeter fps = new FpsMeter(2000);
    private final String nonceHex;
    private final int maxAttempts;
    private final Object lock = new Object();
    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();
    private final List<ChallengeOutcome> outcomes = new ArrayList<>();

    private Phase phase = Phase.IDLE;
    private Hint hint = Hint.NONE;
    private Challenge challenge;
    private FailureReason failure;
    private int attempt;
    private SessionState published;
    private long sessionStartNanos;
    private long attemptStartNanos;
    private long passiveStartNanos = -1;
    /** Watches for other distinctive actions during the current challenge; null when disabled or idle. */
    private UnpromptedActionMonitor unprompted;
    private long lastPassiveMillis;
    private int framesAnalyzed;
    private int framesThisPassive;
    private List<ChallengeType> sequence = List.of();
    private int challengeIndex;
    private ActionDetector detector;
    private FailureReason lastGateFailure;
    private String vetoSignal;
    private String attackClass;
    private double lastScore = 0.5;
    private String deviceHealth = "UNKNOWN";
    private LivenessResult result;
    private boolean closed;

    /**
     * Constructor matching the module contract: wall-clock from a {@link Clock}, randomness from any
     * {@link Random} (production: {@link SecureRandom}).
     */
    public LivenessOrchestrator(FrameAnalyzer analyzer, LivenessPolicy policy, SessionContext context, Clock clock,
                                Random random, AuditSink audit, LivenessMetrics metrics) {
        this(builder(analyzer, policy, context).clock(clock).random(random).auditSink(audit).metrics(metrics));
    }

    /** Constructor variant taking a monotonic nanosecond supplier instead of a clock. */
    public LivenessOrchestrator(FrameAnalyzer analyzer, LivenessPolicy policy, SessionContext context, LongSupplier nanos,
                                Random random, AuditSink audit, LivenessMetrics metrics) {
        this(builder(analyzer, policy, context).timeSource(TimeSource.of(nanos)).random(random).auditSink(audit).metrics(metrics));
    }

    private LivenessOrchestrator(Builder b) {
        this.analyzer = b.analyzer;
        this.policy = b.policy;
        this.context = b.context;
        this.time = b.time;
        this.audit = b.audit;
        this.metrics = b.metrics;
        this.engineInfo = b.engineInfo;
        this.thresholds = b.thresholds;
        this.detectors = b.detectors;
        this.selector = new ChallengeSelector(b.random);
        this.fusion = new ScoreFusion(policy, b.fusionAlpha);
        this.identity = new IdentityContinuity(policy.identityDriftThreshold(), b.anchorFrames);
        this.gate = new QualityGate(policy.minFaceQuality());
        this.nonceHex = EvidenceHash.toHex(context.nonce());
        this.maxAttempts = Math.max(1, policy.maxRetries());
        this.published = snapshot();
    }

    /** Starts a builder; {@code analyzer}, {@code policy} and {@code context} are mandatory. */
    public static Builder builder(FrameAnalyzer analyzer, LivenessPolicy policy, SessionContext context) {
        return new Builder(analyzer, policy, context);
    }

    // ---- lifecycle entry points beyond LivenessSession -------------------------------------------------------

    /** IDLE → DEVICE_CONNECTING (or PASSED when the policy disables liveness). Idempotent. */
    public SessionState start() {
        synchronized (lock) {
            if (phase != Phase.IDLE) {
                return published;
            }
            long now = time.nanoTime();
            sessionStartNanos = now;
            attemptStartNanos = now;
            attempt = 1;
            metrics.sessionStarted();
            audit(LivenessAuditEvent.SESSION_STARTED).attribute("attempt", attempt).attribute("retry", false)
                    .attribute("policyVersion", policy.policyVersion()).attribute("assistedCapture", context.assistedCapture())
                    .attribute("engine", engineInfo == null ? "unknown" : engineInfo.engineId()).build();
            if (!policy.enabled()) {
                failure = FailureReason.POLICY_DISABLED;
                phase = Phase.PASSED;
                hint = Hint.CAPTURED_OK;
                result = buildResult(LivenessResult.Decision.LIVE);
                metrics.sessionPassed();
                audit(LivenessAuditEvent.SESSION_PASSED).attribute("policyDisabled", true).evidenceHash(result.evidenceHash()).build();
                return publish();
            }
            phase = Phase.DEVICE_CONNECTING;
            hint = Hint.NONE;
            return publish();
        }
    }

    /** DEVICE_CONNECTING → FACE_ACQUIRING. Also clears a reconnecting hint. */
    public SessionState deviceReady() {
        synchronized (lock) {
            deviceHealth = "CONNECTED";
            if (phase == Phase.DEVICE_CONNECTING) {
                phase = Phase.FACE_ACQUIRING;
                hint = Hint.CENTER_FACE;
            } else if (hint == Hint.DEVICE_RECONNECTING) {
                hint = Hint.NONE;
            }
            return publish();
        }
    }

    /** The device is degraded or reconnecting: show guidance, keep the attempt alive, audit the fault. */
    public SessionState deviceReconnecting(String detail) {
        synchronized (lock) {
            deviceHealth = "RECONNECTING";
            if (isLive()) {
                hint = Hint.DEVICE_RECONNECTING;
                metrics.deviceFault("RECONNECTING");
                audit(LivenessAuditEvent.DEVICE_FAULT).attribute("kind", "RECONNECTING").attribute("detail", detail).build();
            }
            return publish();
        }
    }

    /** The device failed: the attempt fails with the given DEVICE_* / CAMERA_* reason. */
    public SessionState deviceFault(FailureReason reason, String detail) {
        Objects.requireNonNull(reason, "reason");
        synchronized (lock) {
            deviceHealth = "FAILED";
            if (published.isTerminal() || phase == Phase.IDLE) {
                return published;
            }
            metrics.deviceFault(reason.name());
            audit(LivenessAuditEvent.DEVICE_FAULT).attribute("kind", reason.name()).attribute("detail", detail).build();
            if (phase == Phase.FAILED) {
                hint = Hint.DEVICE_DISCONNECTED;
                return publish();
            }
            return fail(reason, Hint.DEVICE_DISCONNECTED);
        }
    }

    /**
     * The frame source delivered a frame whose device signature is missing or failed verification although the device
     * advertised signed frames ({@code DeviceCapabilities.signsFrames()}). That is an injection on the preview path, so
     * the attempt fails as {@code PAD_DETECTED} with attack class {@code INJECTION}, exactly like a hard veto.
     */
    public SessionState frameSignatureInvalid(String detail) {
        synchronized (lock) {
            if (published.isTerminal() || phase == Phase.IDLE || phase == Phase.FAILED) {
                return published;
            }
            audit(LivenessAuditEvent.DEVICE_FAULT).attribute("kind", "FRAME_SIGNATURE_INVALID").attribute("detail", detail).build();
            failPad("device_signature", Signals.ATTACK_INJECTION, 0.0);
            return published;
        }
    }

    // ---- LivenessSession ------------------------------------------------------------------------------------

    @Override
    public SessionContext context() {
        return context;
    }

    @Override
    public SessionState feed(FaceFrame frame) {
        Objects.requireNonNull(frame, "frame");
        synchronized (lock) {
            if (phase == Phase.IDLE) {
                start();
            }
            if (phase == Phase.DEVICE_CONNECTING) {
                deviceReady();
            }
            if (!isLive()) {
                return published;
            }
            long now = time.nanoTime();
            if (checkTimeouts(now)) {
                return published;
            }
            FrameAnalysis analysis;
            try {
                analysis = analyzer.analyze(frame);
            } catch (RuntimeException e) {
                LOG.error("engine failed on frame {}: {}", frame.sequence(), e.toString());
                return fail(FailureReason.ENGINE_ERROR, Hint.TRY_AGAIN);
            }
            if (analysis == null) {
                return fail(FailureReason.INVALID_FRAME_DATA, Hint.TRY_AGAIN);
            }
            framesAnalyzed++;
            fps.tick(now);
            handle(analysis, now);
            return publish();
        }
    }

    @Override
    public SessionState tick() {
        synchronized (lock) {
            if (!isLive() && phase != Phase.DEVICE_CONNECTING) {
                return published;
            }
            checkTimeouts(time.nanoTime());
            return publish();
        }
    }

    @Override
    public SessionState state() {
        synchronized (lock) {
            return published;
        }
    }

    @Override
    public boolean retry() {
        synchronized (lock) {
            if (phase != Phase.FAILED || attempt >= maxAttempts) {
                return false;
            }
            // After a device failure the new attempt must re-establish the device (formal model action Retry):
            // going straight to FACE_ACQUIRING let a still-unplugged camera time out as FACE_NOT_DETECTED.
            boolean deviceLost = failure != null && DEVICE_REASONS.contains(failure);
            attempt++;
            failure = null;
            vetoSignal = null;
            attackClass = null;
            challenge = null;
            detector = null;
            unprompted = null;
            sequence = List.of();
            challengeIndex = 0;
            lastGateFailure = null;
            passiveStartNanos = -1;
            framesThisPassive = 0;
            fusion.reset();
            identity.resetStreak();
            try {
                analyzer.reset();
            } catch (RuntimeException e) {
                LOG.warn("analyzer reset failed: {}", e.toString());
            }
            attemptStartNanos = time.nanoTime();
            phase = deviceLost ? Phase.DEVICE_CONNECTING : Phase.FACE_ACQUIRING;
            hint = Hint.TRY_AGAIN;
            metrics.retry();
            audit(LivenessAuditEvent.SESSION_STARTED).attribute("attempt", attempt).attribute("retry", true).build();
            publish();
            return true;
        }
    }

    @Override
    public void cancel() {
        synchronized (lock) {
            if (published.isTerminal()) {
                return;
            }
            if (phase == Phase.IDLE) {
                sessionStartNanos = attemptStartNanos = time.nanoTime();
            }
            abortChallenge(ChallengeOutcome.Result.ABORTED);
            failure = FailureReason.CANCELLED;
            phase = Phase.CANCELLED;
            hint = Hint.NONE;
            result = buildResult(LivenessResult.Decision.CANCELLED);
            metrics.sessionFailed(FailureReason.CANCELLED);
            audit(LivenessAuditEvent.SESSION_FAILED).attribute("reason", FailureReason.CANCELLED).attribute("attempt", attempt)
                    .evidenceHash(result.evidenceHash()).build();
            publish();
        }
    }

    @Override
    public LivenessResult result() {
        synchronized (lock) {
            if (result == null) {
                throw new IllegalStateException("result not available in phase " + phase);
            }
            return result;
        }
    }

    @Override
    public void addListener(SessionListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            cancel();
            try {
                analyzer.close();
            } catch (Exception e) {
                LOG.warn("analyzer close failed: {}", e.toString());
            }
        }
    }

    // ---- accessors ----------------------------------------------------------------------------------------

    /** The challenge currently shown to the user, if any. */
    public Optional<Challenge> currentChallenge() {
        synchronized (lock) {
            return Optional.ofNullable(challenge);
        }
    }

    /** The challenge sequence drawn for the current attempt (empty before the active phase). */
    public List<ChallengeType> challengeSequence() {
        synchronized (lock) {
            return sequence;
        }
    }

    /** Policy in force for this session. */
    public LivenessPolicy policy() {
        return policy;
    }

    /** Progress of the current action detector in [0,1], 0 when no challenge is active. */
    public double actionProgress() {
        synchronized (lock) {
            return detector == null ? 0 : detector.progress();
        }
    }

    @Override
    public DiagnosticSnapshot captureDiagnostics() {
        synchronized (lock) {
            DiagnosticSnapshot.Builder b = DiagnosticSnapshot.builder().capturedAt(time.now()).sessionId(context.sessionId())
                    .phase(phase.name()).fusedLivenessScore(lastScore).signalScores(fusion.lastAttackProbabilities())
                    .framesPerSecond(fps.fps(time.nanoTime())).framesAnalyzed(framesAnalyzed).policyVersion(policy.policyVersion())
                    .deviceHealth(deviceHealth).extra("attempt", attempt + "/" + maxAttempts).extra("hint", hint.name());
            if (engineInfo != null) {
                b.engineId(engineInfo.engineId()).engineVersion(engineInfo.engineVersion())
                        .modelBundleId(engineInfo.modelBundleId()).modelBundleVersion(engineInfo.modelBundleVersion());
            }
            if (challenge != null) {
                b.extra("challenge", challenge.toString()).extra("actionProgress", String.valueOf(detector == null ? 0 : detector.progress()));
            }
            if (identity.anchored()) {
                b.extra("identityDistance", String.valueOf(identity.lastDistance()));
            }
            return b.build();
        }
    }

    // ---- frame handling -------------------------------------------------------------------------------------

    private void handle(FrameAnalysis a, long now) {
        boolean headMotionChallenge = phase == Phase.ACTIVE_CHALLENGE && challenge != null && challenge.type().requiresMotorAbility();
        QualityGate.Verdict verdict = gate.evaluate(a, headMotionChallenge);
        if (!verdict.passed()) {
            lastGateFailure = verdict.reason();
            hint = verdict.hint();
            // Keep the identity streak honest: a poor frame neither builds the anchor nor counts as drift.
            return;
        }
        if (phase == Phase.FACE_ACQUIRING) {
            phase = Phase.PASSIVE_EVALUATING;
            passiveStartNanos = now;
            framesThisPassive = 0;
        }
        hint = phase == Phase.ACTIVE_CHALLENGE ? Hint.PLEASE_CONTINUE : Hint.CHECKING_LIVENESS;

        List<SignalScore> signals = new ArrayList<>(a.signals());
        Optional<SignalScore> idScore = identity.observe(a.embedding().orElse(null), true);
        if (policy.requireIdentityContinuity() && idScore.isPresent()) {
            SignalScore id = idScore.get();
            if (id.hardVeto()) {
                audit(LivenessAuditEvent.IDENTITY_DRIFT).attribute("distance", fmt(identity.lastDistance()))
                        .attribute("threshold", fmt(identity.driftThreshold())).build();
                abortChallenge(ChallengeOutcome.Result.FAILED);
                vetoSignal = Signals.IDENTITY;
                fail(FailureReason.IDENTITY_DRIFT, Hint.TRY_AGAIN);
                return;
            }
            // Veto-only: identity agreement is not liveness evidence, so it is reported but never fused.
            fusion.recordAuxiliary(id);
        }

        FusionResult f = fusion.update(signals);
        lastScore = f.livenessScore();
        if (phase == Phase.PASSIVE_EVALUATING) {
            framesThisPassive++;
        }
        if (f.vetoed()) {
            failPad(f.vetoSignal(), f.attackClass(), f.livenessScore());
            return;
        }
        if (f.framesFused() >= policy.passiveMinFrames() && f.attackProbability() >= policy.padHardThreshold()) {
            failPad(FUSION_VETO, Signals.ATTACK_UNKNOWN, f.livenessScore());
            return;
        }
        if (phase == Phase.PASSIVE_EVALUATING) {
            if (framesThisPassive >= policy.passiveMinFrames() && millis(now - passiveStartNanos) >= policy.passiveWindowMillis()) {
                decidePassive(now, f);
            }
        } else if (phase == Phase.ACTIVE_CHALLENGE) {
            driveChallenge(a, now);
        }
    }

    private void decidePassive(long now, FusionResult f) {
        lastPassiveMillis = millis(now - passiveStartNanos);
        metrics.passiveDuration(lastPassiveMillis);
        boolean passed = f.livenessScore() >= policy.passiveThreshold();
        if (passed) {
            audit(LivenessAuditEvent.PASSIVE_PASSED).attribute("fusedScore", fmt(f.livenessScore()))
                    .attribute("frames", f.framesFused()).attribute("passiveMs", lastPassiveMillis).build();
            if (policy.activeMode() == LivenessPolicy.ActiveMode.ALWAYS) {
                beginChallenges(now, true);
            } else {
                pass();
            }
            return;
        }
        audit(LivenessAuditEvent.PASSIVE_BELOW_THRESHOLD).attribute("fusedScore", fmt(f.livenessScore()))
                .attribute("threshold", fmt(policy.passiveThreshold())).attribute("frames", f.framesFused()).build();
        if (policy.activeMode() == LivenessPolicy.ActiveMode.NEVER) {
            fail(FailureReason.LIVENESS_BELOW_THRESHOLD, Hint.TRY_AGAIN);
            return;
        }
        beginChallenges(now, false);
    }

    private void beginChallenges(long now, boolean passivePassed) {
        Set<ChallengeType> supported = engineInfo == null ? EnumSet.allOf(ChallengeType.class) : engineInfo.supportedChallenges();
        sequence = selector.select(policy, context, supported);
        challengeIndex = 0;
        if (sequence.isEmpty()) {
            if (passivePassed) {
                pass();
            } else {
                fail(FailureReason.LIVENESS_BELOW_THRESHOLD, Hint.TRY_AGAIN);
            }
            return;
        }
        phase = Phase.ACTIVE_CHALLENGE;
        issueChallenge(now);
    }

    private void issueChallenge(long now) {
        ChallengeType type = sequence.get(challengeIndex);
        challenge = new Challenge(type, challengeIndex, sequence.size(), now, policy.challengeTimeoutMillis());
        detector = detectors.create(type, thresholds, now);
        unprompted = thresholds.rejectUnpromptedActions()
                ? new UnpromptedActionMonitor(type, policy.challengePool(), thresholds, now) : null;
        hint = Hint.PLEASE_CONTINUE;
        try {
            analyzer.onChallenge(challenge);
        } catch (RuntimeException e) {
            LOG.warn("analyzer rejected challenge notification: {}", e.toString());
        }
        audit(LivenessAuditEvent.CHALLENGE_ISSUED).attribute("type", type).attribute("index", challengeIndex)
                .attribute("total", sequence.size()).attribute("nonce", nonceHex).attribute("timeoutMs", challenge.timeoutMillis()).build();
    }

    private void driveChallenge(FrameAnalysis a, long now) {
        if (detector == null || a.face().isEmpty()) {
            return;
        }
        ActionStatus status = detector.update(a.face().get(), now);
        if (status == ActionStatus.FAILED) {
            recordOutcome(ChallengeOutcome.Result.FAILED, now);
            fail(FailureReason.ACTIVE_CHALLENGE_FAILED, Hint.TRY_AGAIN);
            return;
        }
        if (status != ActionStatus.COMPLETED) {
            Optional<ChallengeType> other = unprompted == null ? Optional.empty() : unprompted.update(a.face().get(), now);
            if (other.isPresent()) {
                audit(LivenessAuditEvent.UNPROMPTED_ACTION).attribute("type", challenge.type()).attribute("index", challenge.index())
                        .attribute("unprompted", other.get()).build();
                recordOutcome(ChallengeOutcome.Result.FAILED, now);
                fail(FailureReason.ACTIVE_CHALLENGE_FAILED, Hint.TRY_AGAIN);
            }
            return;
        }
        long onset = detector.onsetNanos().orElse(now);
        long latencyMs = ChallengeTiming.latencyMillis(challenge, onset);
        long durationMs = millis(now - challenge.issuedAtNanos());
        if (detector.timingApplies()) {
            SignalScore timing = ChallengeTiming.evaluate(challenge, onset, policy);
            if (timing.hardVeto() && policy.signalWeight(Signals.CHALLENGE_TIMING) > 0) {
                outcomes.add(new ChallengeOutcome(challenge, ChallengeOutcome.Result.FAILED, latencyMs, durationMs));
                challenge = null;
                detector = null;
                unprompted = null;
                failPad(Signals.CHALLENGE_TIMING, timing.attackClass(), lastScore);
                return;
            }
            fusion.setSessionSignal(timing);
        }
        outcomes.add(new ChallengeOutcome(challenge, ChallengeOutcome.Result.PASSED, latencyMs, durationMs));
        metrics.challengeCompletion(durationMs);
        audit(LivenessAuditEvent.CHALLENGE_PASSED).attribute("type", challenge.type()).attribute("index", challenge.index())
                .attribute("latencyMs", latencyMs).attribute("durationMs", durationMs).build();
        hint = Hint.ACTION_DETECTED;
        challengeIndex++;
        if (challengeIndex < sequence.size()) {
            issueChallenge(now);
        } else {
            challenge = null;
            detector = null;
            unprompted = null;
            // Re-evaluation during active verification (design 04 section 5, formal property PassedImpliesPolicy):
            // completing the challenges is not a substitute for liveness evidence. The fused score of this frame
            // must still clear the threshold, otherwise the attempt fails.
            if (lastScore >= policy.passiveThreshold()) {
                pass();
            } else {
                audit(LivenessAuditEvent.PASSIVE_BELOW_THRESHOLD).attribute("fusedScore", fmt(lastScore))
                        .attribute("threshold", fmt(policy.passiveThreshold())).attribute("afterChallenges", sequence.size()).build();
                fail(FailureReason.LIVENESS_BELOW_THRESHOLD, Hint.TRY_AGAIN);
            }
        }
    }

    // ---- timeouts -------------------------------------------------------------------------------------------

    /** Applies expired timeouts; returns true if the attempt ended. */
    private boolean checkTimeouts(long now) {
        long attemptMs = millis(now - attemptStartNanos);
        if (phase == Phase.DEVICE_CONNECTING) {
            if (attemptMs >= policy.sessionTimeoutMillis()) {
                deviceFault(FailureReason.DEVICE_CONNECTION_FAILURE, "no device within " + policy.sessionTimeoutMillis() + " ms");
                return true;
            }
            return false;
        }
        if (phase == Phase.ACTIVE_CHALLENGE && challenge != null && millis(now - challenge.issuedAtNanos()) >= challenge.timeoutMillis()) {
            audit(LivenessAuditEvent.CHALLENGE_TIMEOUT).attribute("type", challenge.type()).attribute("index", challenge.index())
                    .attribute("timeoutMs", challenge.timeoutMillis()).build();
            recordOutcome(ChallengeOutcome.Result.TIMEOUT, now);
            fail(FailureReason.CHALLENGE_TIMEOUT, Hint.TRY_AGAIN);
            return true;
        }
        if (attemptMs >= policy.sessionTimeoutMillis()) {
            FailureReason reason = phase == Phase.FACE_ACQUIRING
                    ? Objects.requireNonNullElse(lastGateFailure, FailureReason.FACE_NOT_DETECTED)
                    : FailureReason.SESSION_TIMEOUT;
            abortChallenge(ChallengeOutcome.Result.TIMEOUT);
            fail(reason, Hint.TRY_AGAIN);
            return true;
        }
        return false;
    }

    // ---- terminal transitions -------------------------------------------------------------------------------

    private void pass() {
        challenge = null;
        detector = null;
        unprompted = null;
        phase = Phase.PASSED;
        hint = Hint.CAPTURED_OK;
        failure = null;
        result = buildResult(LivenessResult.Decision.LIVE);
        metrics.sessionPassed();
        AuditEntry b = audit(LivenessAuditEvent.SESSION_PASSED).attribute("fusedScore", fmt(result.fusedLivenessScore()))
                .attribute("attempt", attempt).attribute("frames", framesAnalyzed).attribute("challenges", outcomes.size())
                .attribute("passiveMs", lastPassiveMillis).evidenceHash(result.evidenceHash());
        for (Map.Entry<String, Double> e : result.signalScores().entrySet()) {
            b.attribute("signal." + e.getKey(), fmt(e.getValue()));
        }
        b.build();
    }

    private void failPad(String signal, String attack, double score) {
        vetoSignal = signal;
        attackClass = attack == null ? Signals.ATTACK_UNKNOWN : attack;
        abortChallenge(ChallengeOutcome.Result.FAILED);
        metrics.padDetected(attackClass);
        audit(LivenessAuditEvent.PAD_DETECTED).attribute("attackClass", attackClass).attribute("fusedScore", fmt(score))
                .attribute("vetoSignal", signal).build();
        fail(FailureReason.PAD_DETECTED, Hint.TRY_AGAIN);
    }

    private SessionState fail(FailureReason reason, Hint failHint) {
        abortChallenge(ChallengeOutcome.Result.ABORTED);
        failure = reason;
        phase = Phase.FAILED;
        hint = attempt < maxAttempts ? (failHint == Hint.TRY_AGAIN ? Hint.RETRY_AVAILABLE : failHint) : failHint;
        result = buildResult(decisionFor(reason));
        metrics.sessionFailed(reason);
        audit(LivenessAuditEvent.SESSION_FAILED).attribute("reason", reason).attribute("attempt", attempt)
                .attribute("decision", result.decision()).attribute("fusedScore", fmt(result.fusedLivenessScore()))
                .evidenceHash(result.evidenceHash()).build();
        publish();
        if (attempt >= maxAttempts) {
            phase = Phase.LOCKED;
            hint = Hint.MAX_RETRIES_REACHED;
            audit(LivenessAuditEvent.MAX_RETRIES_REACHED).attribute("action", policy.onMaxRetries()).attribute("attempts", attempt)
                    .attribute("lastReason", reason).build();
            publish();
        }
        return published;
    }

    private void abortChallenge(ChallengeOutcome.Result how) {
        if (challenge != null) {
            recordOutcome(how, time.nanoTime());
        }
    }

    private void recordOutcome(ChallengeOutcome.Result how, long now) {
        if (challenge == null) {
            return;
        }
        long onset = detector == null ? -1 : detector.onsetNanos().orElse(-1);
        long latency = onset < 0 ? -1 : ChallengeTiming.latencyMillis(challenge, onset);
        outcomes.add(new ChallengeOutcome(challenge, how, latency, millis(now - challenge.issuedAtNanos())));
        challenge = null;
        detector = null;
        unprompted = null;
    }

    private LivenessResult.Decision decisionFor(FailureReason reason) {
        if (ATTACK_REASONS.contains(reason)) {
            return LivenessResult.Decision.ATTACK;
        }
        if (ERROR_REASONS.contains(reason)) {
            return LivenessResult.Decision.ERROR;
        }
        return LivenessResult.Decision.NOT_LIVE;
    }

    private LivenessResult buildResult(LivenessResult.Decision decision) {
        Map<String, Double> scores = fusion.lastAttackProbabilities();
        List<ChallengeOutcome> log = List.copyOf(outcomes);
        String engineId = engineInfo == null ? "unknown" : engineInfo.engineId();
        String bundle = engineInfo == null ? "none" : engineInfo.modelBundleVersion();
        String hash = EvidenceHash.compute(context.sessionId(), context.workflow(), decision, scores, log, nonceHex,
                policy.policyVersion(), bundle);
        long total = millis(time.nanoTime() - sessionStartNanos);
        return new LivenessResult(context.sessionId(), context.workflow(), decision, failure, fusion.livenessScore(), scores,
                vetoSignal, attackClass, log, attempt, lastPassiveMillis, total, framesAnalyzed, engineId, bundle,
                policy.policyVersion(), nonceHex, identity.anchor().orElse(null), hash);
    }

    // ---- state publishing -----------------------------------------------------------------------------------

    private SessionState publish() {
        SessionState previous = published;
        SessionState next = snapshot();
        published = next;
        if (previous == null || changed(previous, next)) {
            for (SessionListener l : listeners) {
                try {
                    l.onStateChanged(previous, next);
                } catch (RuntimeException e) {
                    LOG.warn("session listener failed: {}", e.toString());
                }
            }
        }
        return next;
    }

    private static boolean changed(SessionState a, SessionState b) {
        return a.phase() != b.phase() || a.hint() != b.hint() || !a.failure().equals(b.failure())
                || !a.challenge().equals(b.challenge()) || a.attempt() != b.attempt();
    }

    private SessionState snapshot() {
        long remaining = 0;
        if (phase == Phase.ACTIVE_CHALLENGE && challenge != null) {
            remaining = Math.max(0, challenge.timeoutMillis() - millis(time.nanoTime() - challenge.issuedAtNanos()));
        }
        return new SessionState(phase, hint, challenge, failure, attempt, maxAttempts, progress(), lastScore, remaining);
    }

    private double progress() {
        switch (phase) {
            case IDLE:
            case DEVICE_CONNECTING:
                return 0.0;
            case FACE_ACQUIRING:
                return 0.1;
            case PASSIVE_EVALUATING: {
                double byTime = passiveStartNanos < 0 ? 0 : Math.min(1.0, (double) millis(time.nanoTime() - passiveStartNanos) / Math.max(1, policy.passiveWindowMillis()));
                double byFrames = Math.min(1.0, (double) framesThisPassive / Math.max(1, policy.passiveMinFrames()));
                return 0.1 + 0.4 * Math.min(byTime, byFrames);
            }
            case ACTIVE_CHALLENGE: {
                double done = sequence.isEmpty() ? 0 : (challengeIndex + (detector == null ? 0 : detector.progress())) / sequence.size();
                return 0.5 + 0.5 * Math.min(1.0, done);
            }
            case PASSED:
                return 1.0;
            default:
                return published == null ? 0.0 : published.progress();
        }
    }

    private boolean isLive() {
        return phase == Phase.FACE_ACQUIRING || phase == Phase.PASSIVE_EVALUATING || phase == Phase.ACTIVE_CHALLENGE;
    }

    private AuditEntry audit(LivenessAuditEvent event) {
        return new AuditEntry(event);
    }

    /** Audit record under construction; every record carries session id, workflow and timestamp and is delivered on build(). */
    private final class AuditEntry {
        private final AuditRecord.Builder builder;

        AuditEntry(LivenessAuditEvent event) {
            builder = AuditRecord.builder(event).sessionId(context.sessionId()).workflow(context.workflow()).at(time.now());
        }

        AuditEntry attribute(String key, Object value) {
            builder.attribute(key, value);
            return this;
        }

        AuditEntry evidenceHash(String hash) {
            builder.evidenceHash(hash);
            return this;
        }

        void build() {
            AuditRecord record = builder.build();
            try {
                audit.accept(record);
            } catch (RuntimeException e) {
                LOG.error("audit sink failed for {}: {}", record.event(), e.toString());
            }
        }
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000L;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    /** Creates the {@link ActionDetector} for an issued challenge; production uses {@link ActionDetectors#create}. */
    @FunctionalInterface
    public interface DetectorFactory {
        ActionDetector create(ChallengeType type, ActionThresholds thresholds, long issuedAtNanos);
    }

    /** Frames scored in the current passive window; package-private probe for the conformance test. */
    int passiveFramesForConformance() {
        synchronized (lock) {
            return framesThisPassive;
        }
    }

    /** Builder for an orchestrator with optional collaborators. */
    public static final class Builder {
        private final FrameAnalyzer analyzer;
        private final LivenessPolicy policy;
        private final SessionContext context;
        private TimeSource time = TimeSource.system();
        private Random random = new SecureRandom();
        private AuditSink audit = AuditSink.discarding();
        private LivenessMetrics metrics = new LivenessMetrics();
        private EngineInfo engineInfo;
        private ActionThresholds thresholds = ActionThresholds.defaults();
        private DetectorFactory detectors = ActionDetectors::create;
        private double fusionAlpha = ScoreFusion.DEFAULT_ALPHA;
        private int anchorFrames = IdentityContinuity.DEFAULT_ANCHOR_FRAMES;

        private Builder(FrameAnalyzer analyzer, LivenessPolicy policy, SessionContext context) {
            this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
            this.policy = Objects.requireNonNull(policy, "policy");
            this.context = Objects.requireNonNull(context, "context");
            if (policy.workflow() != context.workflow()) {
                throw new IllegalArgumentException("policy is for " + policy.workflow() + " but session is " + context.workflow());
            }
        }

        public Builder timeSource(TimeSource v) { time = Objects.requireNonNull(v); return this; }
        public Builder clock(Clock v) { time = TimeSource.of(v); return this; }
        public Builder random(Random v) { random = Objects.requireNonNull(v); return this; }
        public Builder auditSink(AuditSink v) { audit = Objects.requireNonNull(v); return this; }
        public Builder metrics(LivenessMetrics v) { metrics = Objects.requireNonNull(v); return this; }
        /** Engine description used for challenge support, result and diagnostics; optional. */
        public Builder engineInfo(EngineInfo v) { engineInfo = v; return this; }
        public Builder actionThresholds(ActionThresholds v) { thresholds = Objects.requireNonNull(v); return this; }
        /**
         * Creates the detector for each issued challenge; defaults to {@link ActionDetectors#create}. A test seam: the
         * model-based conformance test (formal/README.md) scripts detector outcomes the way the TLA+ environment
         * chooses them.
         */
        public Builder actionDetectorFactory(DetectorFactory v) { detectors = Objects.requireNonNull(v); return this; }
        /** EMA factor of passive fusion in (0,1]. */
        public Builder fusionAlpha(double v) { fusionAlpha = v; return this; }
        /** Number of embeddings averaged into the identity anchor. */
        public Builder anchorFrames(int v) { anchorFrames = v; return this; }

        public LivenessOrchestrator build() {
            return new LivenessOrchestrator(this);
        }
    }
}
