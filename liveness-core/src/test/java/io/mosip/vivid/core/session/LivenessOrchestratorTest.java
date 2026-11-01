package io.mosip.vivid.core.session;

import io.mosip.vivid.api.ChallengeOutcome;
import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.EngineInfo;
import io.mosip.vivid.api.FaceQuality;
import io.mosip.vivid.api.FailureReason;
import io.mosip.vivid.api.FrameAnalysis;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.LivenessResult;
import io.mosip.vivid.api.SessionContext;
import io.mosip.vivid.api.SessionState;
import io.mosip.vivid.api.SessionState.Hint;
import io.mosip.vivid.api.SessionState.Phase;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.core.identity.IdentityContinuity;
import io.mosip.vivid.core.testing.FakeFrameAnalyzer;
import io.mosip.vivid.core.testing.FakeTime;
import io.mosip.vivid.core.testing.Frames;
import io.mosip.vivid.core.testing.Scenarios;
import io.mosip.vivid.telemetry.audit.AuditRecord;
import io.mosip.vivid.telemetry.audit.InMemoryAuditSink;
import io.mosip.vivid.telemetry.audit.LivenessAuditEvent;
import io.mosip.vivid.telemetry.diagnostics.DiagnosticSnapshot;
import io.mosip.vivid.telemetry.metrics.LivenessMetrics;
import io.mosip.vivid.telemetry.metrics.MetricsSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.Random;

import static io.mosip.vivid.telemetry.audit.LivenessAuditEvent.CHALLENGE_ISSUED;
import static io.mosip.vivid.telemetry.audit.LivenessAuditEvent.CHALLENGE_PASSED;
import static io.mosip.vivid.telemetry.audit.LivenessAuditEvent.CHALLENGE_TIMEOUT;
import static io.mosip.vivid.telemetry.audit.LivenessAuditEvent.MAX_RETRIES_REACHED;
import static io.mosip.vivid.telemetry.audit.LivenessAuditEvent.PAD_DETECTED;
import static io.mosip.vivid.telemetry.audit.LivenessAuditEvent.PASSIVE_BELOW_THRESHOLD;
import static io.mosip.vivid.telemetry.audit.LivenessAuditEvent.PASSIVE_PASSED;
import static io.mosip.vivid.telemetry.audit.LivenessAuditEvent.SESSION_FAILED;
import static io.mosip.vivid.telemetry.audit.LivenessAuditEvent.SESSION_PASSED;
import static io.mosip.vivid.telemetry.audit.LivenessAuditEvent.SESSION_STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LivenessOrchestratorTest {

    private static final EngineInfo ENGINE = new EngineInfo("fake-engine", "1.0", "bundle-a", "2026.1",
            EnumSet.allOf(ChallengeType.class), Set.of(Signals.CNN_TEXTURE, Signals.MOIRE), true);

    private final FakeTime time = new FakeTime();
    private final FakeFrameAnalyzer analyzer = new FakeFrameAnalyzer();
    private final InMemoryAuditSink audit = new InMemoryAuditSink();
    private final LivenessMetrics metrics = new LivenessMetrics();
    private final List<SessionState> transitions = new ArrayList<>();
    private LivenessOrchestrator session;
    private Scenarios.Driver driver;

    private LivenessOrchestrator open(LivenessPolicy policy, SessionContext ctx, EngineInfo engine) {
        session = LivenessOrchestrator.builder(analyzer, policy, ctx).timeSource(time).random(new Random(11))
                .auditSink(audit).metrics(metrics).engineInfo(engine).build();
        session.addListener((prev, cur) -> transitions.add(cur));
        driver = new Scenarios.Driver(session, analyzer, time);
        return session;
    }

    private LivenessOrchestrator open(LivenessPolicy policy) {
        return open(policy, Scenarios.context(policy.workflow(), false), ENGINE);
    }

    private List<Phase> phases() {
        List<Phase> out = new ArrayList<>();
        for (SessionState s : transitions) {
            if (out.isEmpty() || out.get(out.size() - 1) != s.phase()) {
                out.add(s.phase());
            }
        }
        return out;
    }

    // ---- scenarios ----------------------------------------------------------------------------------------

    @Test
    void liveSubjectPassesPassively() {
        open(Scenarios.policy(Workflow.RESIDENT).build());
        assertThat(session.state().phase()).isEqualTo(Phase.IDLE);
        SessionState s = driver.feed(Scenarios.live());
        assertThat(s.phase()).isEqualTo(Phase.PASSIVE_EVALUATING);
        assertThat(s.hint()).isEqualTo(Hint.CHECKING_LIVENESS);
        s = driver.feedUntilLeaves(Scenarios.live(), Phase.PASSIVE_EVALUATING, 20);
        assertThat(s.phase()).isEqualTo(Phase.PASSED);
        assertThat(s.isTerminal()).isTrue();
        assertThat(s.progress()).isEqualTo(1.0);
        assertThat(s.hint()).isEqualTo(Hint.CAPTURED_OK);
        assertThat(phases()).containsExactly(Phase.DEVICE_CONNECTING, Phase.FACE_ACQUIRING, Phase.PASSIVE_EVALUATING, Phase.PASSED);

        LivenessResult r = session.result();
        assertThat(r.accepted()).isTrue();
        assertThat(r.decision()).isEqualTo(LivenessResult.Decision.LIVE);
        assertThat(r.failure()).isEmpty();
        assertThat(r.fusedLivenessScore()).isGreaterThan(0.9);
        assertThat(r.signalScores()).containsKeys(Signals.CNN_TEXTURE, Signals.MOIRE, Signals.IDENTITY).doesNotContainKey(Signals.QUALITY + "x");
        assertThat(r.challenges()).isEmpty();
        assertThat(r.attemptsUsed()).isEqualTo(1);
        assertThat(r.framesAnalyzed()).isEqualTo(6);
        assertThat(r.passiveMillis()).isEqualTo(500);
        assertThat(r.totalMillis()).isEqualTo(500); // measured from start, which the first frame triggers
        assertThat(r.engineId()).isEqualTo("fake-engine");
        assertThat(r.modelBundleVersion()).isEqualTo("2026.1");
        assertThat(r.policyVersion()).isEqualTo("test-1");
        assertThat(r.nonceHex()).isEqualTo("00112233445566778899aabbccddeeff");
        assertThat(r.anchorEmbedding()).isPresent();
        assertThat(IdentityContinuity.compare(r.anchorEmbedding().get(), Scenarios.embedding(0))).isLessThan(1e-6);
        assertThat(r.evidenceHash()).hasSize(64);
        assertThat(EvidenceHash.verify(r)).isTrue();

        assertThat(audit.events()).containsExactly(SESSION_STARTED, PASSIVE_PASSED, SESSION_PASSED);
        AuditRecord passed = audit.last(SESSION_PASSED).orElseThrow();
        assertThat(passed.evidenceHash()).contains(r.evidenceHash());
        assertThat(passed.sessionId()).isEqualTo("session-resident");
        assertThat(passed.workflow()).contains(Workflow.RESIDENT);
        assertThat(passed.attributes()).containsKey("signal.cnn_texture").doesNotContainKey("anchorEmbedding");
        MetricsSnapshot m = metrics.snapshot();
        assertThat(m.sessionsStarted()).isEqualTo(1);
        assertThat(m.sessionsPassed()).isEqualTo(1);
        assertThat(m.averagePassiveMillis()).isEqualTo(500.0);

        // Terminal: further input is ignored, retry refused, cancel is a no-op.
        assertThat(driver.feed(Scenarios.live()).phase()).isEqualTo(Phase.PASSED);
        assertThat(session.tick().phase()).isEqualTo(Phase.PASSED);
        assertThat(session.retry()).isFalse();
        session.cancel();
        assertThat(session.state().phase()).isEqualTo(Phase.PASSED);
    }

    @Test
    void lowConfidenceSubjectPassesViaChallenges() {
        open(Scenarios.policy(Workflow.RESIDENT).minChallenges(2).maxChallenges(2).build());
        SessionState s = driver.feedUntilLeaves(Scenarios.lowConfidence(), Phase.IDLE, 1);
        s = driver.feedUntilLeaves(Scenarios.lowConfidence(), Phase.PASSIVE_EVALUATING, 20);
        assertThat(s.phase()).isEqualTo(Phase.ACTIVE_CHALLENGE);
        assertThat(s.challenge()).isPresent();
        assertThat(s.remainingChallengeMillis()).isEqualTo(4000);
        assertThat(session.challengeSequence()).hasSize(2).anyMatch(ChallengeType::revealsDepth);
        assertThat(session.currentChallenge()).isPresent();
        assertThat(analyzer.challenges()).hasSize(1);
        double before = s.progress();
        // The subject's passive evidence recovers while they answer (design 04 section 8 timeline): the fused score
        // re-evaluated on the last challenge frame clears the threshold.
        s = driver.performAllChallenges(Scenarios.liveSignals());
        assertThat(s.phase()).isEqualTo(Phase.PASSED);
        assertThat(before).isBetween(0.5, 0.6);

        LivenessResult r = session.result();
        assertThat(r.accepted()).isTrue();
        assertThat(r.challenges()).hasSize(2).allMatch(c -> c.result() == ChallengeOutcome.Result.PASSED);
        assertThat(r.challenges()).allMatch(c -> c.latencyMillis() >= 150 && c.latencyMillis() <= 3000);
        assertThat(r.challenges()).extracting(c -> c.challenge().type()).containsExactlyElementsOf(session.challengeSequence());
        assertThat(r.signalScores()).containsKey(Signals.CHALLENGE_TIMING);
        assertThat(EvidenceHash.verify(r)).isTrue();
        assertThat(audit.events()).containsExactly(SESSION_STARTED, PASSIVE_BELOW_THRESHOLD, CHALLENGE_ISSUED, CHALLENGE_PASSED,
                CHALLENGE_ISSUED, CHALLENGE_PASSED, SESSION_PASSED);
        assertThat(audit.find(CHALLENGE_ISSUED).get(0).attributes()).containsEntry("nonce", r.nonceHex()).containsEntry("index", "0").containsEntry("total", "2");
        assertThat(audit.find(CHALLENGE_PASSED).get(0).attributes()).containsKeys("latencyMs", "durationMs");
        assertThat(metrics.snapshot().challengeSamples()).isEqualTo(2);
        assertThat(analyzer.challenges()).hasSize(2);
    }

    @Test
    void photoAttackIsVetoedByCnnTexture() {
        open(Scenarios.policy(Workflow.RESIDENT).build());
        SessionState s = driver.feed(Scenarios.analysis(Scenarios.goodQuality(), Scenarios.neutralFace().build(), Scenarios.photoAttackSignals(), Scenarios.embedding(0)));
        assertThat(s.phase()).isEqualTo(Phase.FAILED);
        assertThat(s.failure()).contains(FailureReason.PAD_DETECTED);
        assertThat(s.hint()).isEqualTo(Hint.RETRY_AVAILABLE);
        assertThat(s.canRetry()).isTrue();
        LivenessResult r = session.result();
        assertThat(r.decision()).isEqualTo(LivenessResult.Decision.ATTACK);
        assertThat(r.vetoSignal()).contains(Signals.CNN_TEXTURE);
        assertThat(r.attackClass()).contains(Signals.ATTACK_PRINT);
        AuditRecord pad = audit.last(PAD_DETECTED).orElseThrow();
        assertThat(pad.attributes()).containsEntry("attackClass", "PRINT").containsEntry("vetoSignal", "cnn_texture");
        assertThat(audit.events()).containsExactly(SESSION_STARTED, PAD_DETECTED, SESSION_FAILED);
        assertThat(metrics.snapshot().padByAttackClass()).containsEntry("PRINT", 1L);
    }

    @Test
    void screenAttackIsVetoedByMoire() {
        open(Scenarios.policy(Workflow.SUPERVISOR).build());
        driver.feed(Scenarios.live(), 2);
        SessionState s = driver.feed(Scenarios.analysis(Scenarios.goodQuality(), Scenarios.neutralFace().build(), Scenarios.screenAttackSignals(), Scenarios.embedding(0)));
        assertThat(s.failure()).contains(FailureReason.PAD_DETECTED);
        assertThat(session.result().vetoSignal()).contains(Signals.MOIRE);
        assertThat(session.result().attackClass()).contains(Signals.ATTACK_SCREEN);
    }

    @Test
    void fusedScoreCollapseIsPadEvenWithoutSingleVeto() {
        open(Scenarios.policy(Workflow.RESIDENT).passiveMinFrames(3).build());
        FrameAnalysis a = Scenarios.analysis(Scenarios.goodQuality(), Scenarios.neutralFace().build(), Scenarios.collapsingSignals(), null);
        SessionState s = driver.feedUntilLeaves(a, Phase.IDLE, 1);
        s = driver.feedUntilLeaves(a, Phase.PASSIVE_EVALUATING, 10);
        assertThat(s.failure()).contains(FailureReason.PAD_DETECTED);
        assertThat(session.result().vetoSignal()).contains(LivenessOrchestrator.FUSION_VETO);
        assertThat(session.result().attackClass()).contains(Signals.ATTACK_UNKNOWN);
        assertThat(session.result().framesAnalyzed()).isEqualTo(3);
    }

    @Test
    void padDuringChallengeFailsSession() {
        open(Scenarios.policy(Workflow.RESIDENT).build());
        driver.feedUntilLeaves(Scenarios.lowConfidence(), Phase.IDLE, 1);
        driver.feedUntilLeaves(Scenarios.lowConfidence(), Phase.PASSIVE_EVALUATING, 20);
        assertThat(session.state().phase()).isEqualTo(Phase.ACTIVE_CHALLENGE);
        SessionState s = driver.feed(Scenarios.analysis(Scenarios.goodQuality(), Scenarios.neutralFace().build(), Scenarios.screenAttackSignals(), Scenarios.embedding(0)));
        assertThat(s.failure()).contains(FailureReason.PAD_DETECTED);
        assertThat(session.result().challenges()).hasSize(1);
        assertThat(session.result().challenges().get(0).result()).isEqualTo(ChallengeOutcome.Result.FAILED);
        assertThat(session.currentChallenge()).isEmpty();
    }

    @Test
    void replayFailsChallengeTiming() {
        LivenessPolicy p = Scenarios.policy(Workflow.OPERATOR).activeMode(LivenessPolicy.ActiveMode.ALWAYS).minChallenges(1).maxChallenges(1)
                .challengePool(EnumSet.of(ChallengeType.BLINK)).requireDepthRevealingChallenge(false).minActionLatencyMillis(500).build();
        open(p);
        driver.feedUntilLeaves(Scenarios.live(), Phase.IDLE, 1);
        driver.feedUntilLeaves(Scenarios.live(), Phase.PASSIVE_EVALUATING, 20);
        assertThat(session.state().phase()).isEqualTo(Phase.ACTIVE_CHALLENGE);
        assertThat(audit.events()).contains(PASSIVE_PASSED, CHALLENGE_ISSUED);
        // A replayed video "answers" 200 ms after the prompt, below half the 500 ms human minimum.
        driver.feed(Scenarios.withFace(Scenarios.neutralFace().build(), Scenarios.liveSignals()));
        driver.feed(Scenarios.withFace(Scenarios.neutralFace().eyeAspect(0.1, 0.1).build(), Scenarios.liveSignals()));
        SessionState s = driver.feed(Scenarios.withFace(Scenarios.neutralFace().build(), Scenarios.liveSignals()));
        assertThat(s.failure()).contains(FailureReason.PAD_DETECTED);
        LivenessResult r = session.result();
        assertThat(r.vetoSignal()).contains(Signals.CHALLENGE_TIMING);
        assertThat(r.attackClass()).contains(Signals.ATTACK_VIDEO);
        assertThat(r.challenges()).singleElement().satisfies(c -> {
            assertThat(c.result()).isEqualTo(ChallengeOutcome.Result.FAILED);
            assertThat(c.latencyMillis()).isEqualTo(200);
        });
    }

    @Test
    void timingVetoIgnoredWhenTimingSignalDisabled() {
        LivenessPolicy p = Scenarios.policy(Workflow.OPERATOR).activeMode(LivenessPolicy.ActiveMode.ALWAYS).minChallenges(1).maxChallenges(1)
                .challengePool(EnumSet.of(ChallengeType.BLINK)).requireDepthRevealingChallenge(false).minActionLatencyMillis(500)
                .signalWeight(Signals.CHALLENGE_TIMING, 0.0).build();
        open(p);
        driver.feedUntilLeaves(Scenarios.live(), Phase.IDLE, 1);
        driver.feedUntilLeaves(Scenarios.live(), Phase.PASSIVE_EVALUATING, 20);
        driver.feed(Scenarios.withFace(Scenarios.neutralFace().build(), Scenarios.liveSignals()));
        driver.feed(Scenarios.withFace(Scenarios.neutralFace().eyeAspect(0.1, 0.1).build(), Scenarios.liveSignals()));
        assertThat(driver.feed(Scenarios.withFace(Scenarios.neutralFace().build(), Scenarios.liveSignals())).phase()).isEqualTo(Phase.PASSED);
    }

    @Test
    void identitySwapMidChallengeFails() {
        open(Scenarios.policy(Workflow.RESIDENT).build());
        driver.feedUntilLeaves(Scenarios.lowConfidence(), Phase.IDLE, 1);
        driver.feedUntilLeaves(Scenarios.lowConfidence(), Phase.PASSIVE_EVALUATING, 20);
        assertThat(session.state().phase()).isEqualTo(Phase.ACTIVE_CHALLENGE);
        FrameAnalysis impostor = Scenarios.analysis(Scenarios.goodQuality(), Scenarios.neutralFace().build(), Scenarios.lowConfidenceSignals(), Scenarios.embedding(3));
        assertThat(driver.feed(impostor).phase()).isEqualTo(Phase.ACTIVE_CHALLENGE);
        assertThat(driver.feed(impostor).phase()).isEqualTo(Phase.ACTIVE_CHALLENGE);
        SessionState s = driver.feed(impostor);
        assertThat(s.failure()).contains(FailureReason.IDENTITY_DRIFT);
        LivenessResult r = session.result();
        assertThat(r.decision()).isEqualTo(LivenessResult.Decision.ATTACK);
        assertThat(r.vetoSignal()).contains(Signals.IDENTITY);
        assertThat(audit.last(LivenessAuditEvent.IDENTITY_DRIFT).orElseThrow().attributes()).containsEntry("distance", "1.0000");
        assertThat(r.challenges()).singleElement().extracting(ChallengeOutcome::result).isEqualTo(ChallengeOutcome.Result.FAILED);
    }

    @Test
    void identityDriftIgnoredWhenContinuityNotRequired() {
        open(Scenarios.policy(Workflow.RESIDENT).requireIdentityContinuity(false).activeMode(LivenessPolicy.ActiveMode.NEVER).build());
        driver.feed(Scenarios.live(), 5);
        FrameAnalysis impostor = Scenarios.analysis(Scenarios.goodQuality(), Scenarios.neutralFace().build(), Scenarios.liveSignals(), Scenarios.embedding(3));
        assertThat(driver.feedUntilLeaves(impostor, Phase.PASSIVE_EVALUATING, 10).phase()).isEqualTo(Phase.PASSED);
    }

    @Test
    void challengeTimeoutThenRetryThenLock() {
        open(Scenarios.policy(Workflow.RESIDENT).maxRetries(2).onMaxRetries(LivenessPolicy.OnMaxRetries.LOCK_AND_ALERT).build());
        FrameAnalysis idle = Scenarios.lowConfidence();
        driver.feedUntilLeaves(idle, Phase.IDLE, 1);
        driver.feedUntilLeaves(idle, Phase.PASSIVE_EVALUATING, 20);
        assertThat(session.state().phase()).isEqualTo(Phase.ACTIVE_CHALLENGE);
        SessionState s = driver.feedUntilLeaves(idle, Phase.ACTIVE_CHALLENGE, 60);
        assertThat(s.phase()).isEqualTo(Phase.FAILED);
        assertThat(s.failure()).contains(FailureReason.CHALLENGE_TIMEOUT);
        assertThat(s.attempt()).isEqualTo(1);
        assertThat(session.result().challenges()).singleElement().extracting(ChallengeOutcome::result).isEqualTo(ChallengeOutcome.Result.TIMEOUT);
        assertThat(audit.events()).contains(CHALLENGE_TIMEOUT);

        assertThat(session.retry()).isTrue();
        assertThat(analyzer.resets()).isEqualTo(1);
        s = session.state();
        assertThat(s.phase()).isEqualTo(Phase.FACE_ACQUIRING);
        assertThat(s.attempt()).isEqualTo(2);
        assertThat(s.hint()).isEqualTo(Hint.TRY_AGAIN);
        driver.feedUntilLeaves(idle, Phase.FACE_ACQUIRING, 1);
        driver.feedUntilLeaves(idle, Phase.PASSIVE_EVALUATING, 20);
        assertThat(session.state().phase()).isEqualTo(Phase.ACTIVE_CHALLENGE);
        // Second timeout fires from tick() alone, without frames (device stalled).
        time.advance(4000);
        s = session.tick();
        assertThat(s.phase()).isEqualTo(Phase.LOCKED);
        assertThat(s.isTerminal()).isTrue();
        assertThat(s.hint()).isEqualTo(Hint.MAX_RETRIES_REACHED);
        assertThat(s.failure()).contains(FailureReason.CHALLENGE_TIMEOUT);
        assertThat(session.retry()).isFalse();
        assertThat(phases()).containsSubsequence(Phase.ACTIVE_CHALLENGE, Phase.FAILED, Phase.FACE_ACQUIRING, Phase.PASSIVE_EVALUATING,
                Phase.ACTIVE_CHALLENGE, Phase.FAILED, Phase.LOCKED);
        assertThat(audit.last(MAX_RETRIES_REACHED).orElseThrow().attributes()).containsEntry("action", "LOCK_AND_ALERT");
        assertThat(audit.find(SESSION_STARTED)).hasSize(2);
        assertThat(audit.find(SESSION_STARTED).get(1).attributes()).containsEntry("retry", "true");
        LivenessResult r = session.result();
        assertThat(r.attemptsUsed()).isEqualTo(2);
        assertThat(r.challenges()).hasSize(2);
        MetricsSnapshot m = metrics.snapshot();
        assertThat(m.retries()).isEqualTo(1);
        assertThat(m.failuresByReason()).containsEntry("CHALLENGE_TIMEOUT", 2L);
    }

    @Test
    void multipleFacesGuidanceThenTimeout() {
        open(Scenarios.policy(Workflow.RESIDENT).maxRetries(1).build());
        SessionState s = driver.feed(Scenarios.multipleFaces(), 3);
        assertThat(s.phase()).isEqualTo(Phase.FACE_ACQUIRING);
        assertThat(s.hint()).isEqualTo(Hint.ONLY_ONE_FACE);
        assertThat(s.progress()).isEqualTo(0.1);
        time.advance(20_000);
        s = session.tick();
        assertThat(s.phase()).isEqualTo(Phase.LOCKED);
        assertThat(s.failure()).contains(FailureReason.MULTIPLE_FACES);
        assertThat(session.result().decision()).isEqualTo(LivenessResult.Decision.NOT_LIVE);
    }

    /** The acquisition timeout ends a faceless attempt long before the session timeout (docs/design/08 row 4). */
    @Test
    void acquisitionTimeoutFailsFasterThanTheSessionTimeout() {
        open(Scenarios.policy(Workflow.RESIDENT).sessionTimeoutMillis(60_000).acquisitionTimeoutMillis(15_000).build());
        session.start();
        session.deviceReady();
        time.advance(14_000);
        assertThat(session.tick().phase()).isEqualTo(Phase.FACE_ACQUIRING);
        time.advance(1_500);
        SessionState s = session.tick();
        assertThat(s.phase()).isEqualTo(Phase.FAILED);
        assertThat(s.failure()).contains(FailureReason.FACE_NOT_DETECTED);
    }

    @Test
    void noFaceGuidanceThenTimeout() {
        open(Scenarios.policy(Workflow.RESIDENT).build());
        session.start();
        session.deviceReady();
        time.advance(20_000);
        assertThat(session.tick().failure()).contains(FailureReason.FACE_NOT_DETECTED);
        open(Scenarios.policy(Workflow.RESIDENT).build());
        SessionState s = driver.feed(Scenarios.noFace(), 2);
        assertThat(s.hint()).isEqualTo(Hint.LOOK_AT_CAMERA);
        time.advance(20_000);
        assertThat(driver.feed(Scenarios.noFace()).failure()).contains(FailureReason.FACE_NOT_DETECTED);
    }

    @Test
    void poorQualityGivesPositioningHints() {
        open(Scenarios.policy(Workflow.RESIDENT).build());
        assertThat(driver.feed(Scenarios.poorQuality(new FaceQuality(1, 0.9, 0.1, 0.9, 1, 0.9))).hint()).isEqualTo(Hint.IMPROVE_LIGHTING);
        FrameAnalysis far = Scenarios.analysis(Scenarios.goodQuality(), Scenarios.neutralFace().box(0.45, 0.45, 0.1, 0.1).build(), Scenarios.liveSignals(), null);
        assertThat(driver.feed(far).hint()).isEqualTo(Hint.MOVE_CLOSER);
        assertThat(session.state().phase()).isEqualTo(Phase.FACE_ACQUIRING);
        // Poor frames never reach fusion.
        assertThat(session.captureDiagnostics().lastSignalScores()).isEmpty();
    }

    @Test
    void policyDisabledPassesImmediatelyButIsAuditable() {
        open(Scenarios.policy(Workflow.OPERATOR).enabled(false).build());
        SessionState s = session.start();
        assertThat(s.phase()).isEqualTo(Phase.PASSED);
        LivenessResult r = session.result();
        assertThat(r.decision()).isEqualTo(LivenessResult.Decision.LIVE);
        assertThat(r.failure()).contains(FailureReason.POLICY_DISABLED);
        assertThat(audit.last(SESSION_PASSED).orElseThrow().attributes()).containsEntry("policyDisabled", "true");
        assertThat(session.start()).isSameAs(session.state());
        assertThat(driver.feed(Scenarios.live()).phase()).isEqualTo(Phase.PASSED);
        assertThat(analyzer.analyzed()).isZero();
    }

    /**
     * Regression for the divergence found by the model-based conformance test (formal/README.md): completing every
     * drawn challenge used to pass the session even when the re-evaluated fused score stayed below the threshold,
     * violating the TLA+ invariant PassedImpliesPolicy.
     */
    @Test
    void challengesDoNotReplaceLivenessEvidence() {
        open(Scenarios.policy(Workflow.RESIDENT).minChallenges(2).maxChallenges(2).build());
        driver.feedUntilLeaves(Scenarios.lowConfidence(), Phase.IDLE, 1);
        assertThat(driver.feedUntilLeaves(Scenarios.lowConfidence(), Phase.PASSIVE_EVALUATING, 20).phase()).isEqualTo(Phase.ACTIVE_CHALLENGE);
        SessionState s = driver.performAllChallenges(Scenarios.lowConfidenceSignals());
        assertThat(s.phase()).isEqualTo(Phase.FAILED);
        assertThat(s.failure()).contains(FailureReason.LIVENESS_BELOW_THRESHOLD);
        assertThat(session.result().challenges()).hasSize(2).allMatch(c -> c.result() == ChallengeOutcome.Result.PASSED);
        assertThat(session.result().fusedLivenessScore()).isLessThan(0.8);
        assertThat(audit.find(LivenessAuditEvent.PASSIVE_BELOW_THRESHOLD)).hasSize(2);
    }

    /**
     * Regression for the second divergence found by the conformance test: after a device failure, retry() went
     * straight to FACE_ACQUIRING, so a camera that was still unplugged timed out as FACE_NOT_DETECTED instead of a
     * device failure. The model (action Retry) re-enters DEVICE_CONNECTING.
     */
    @Test
    void retryAfterDeviceFailureReconnectsTheDevice() {
        open(Scenarios.policy(Workflow.RESIDENT).maxRetries(4).build());
        session.start();
        session.deviceReady();
        assertThat(session.deviceFault(FailureReason.CAMERA_UNAVAILABLE, "unplugged").phase()).isEqualTo(Phase.FAILED);
        assertThat(session.retry()).isTrue();
        assertThat(session.state().phase()).isEqualTo(Phase.DEVICE_CONNECTING);
        time.advance(20_000);
        SessionState s = session.tick();
        assertThat(s.phase()).isEqualTo(Phase.FAILED);
        assertThat(s.failure()).contains(FailureReason.DEVICE_CONNECTION_FAILURE);
        // A non-device failure still retries straight into acquisition, and frames reconnect implicitly.
        assertThat(session.retry()).isTrue();
        assertThat(session.state().phase()).isEqualTo(Phase.DEVICE_CONNECTING);
        assertThat(driver.feed(Scenarios.live()).phase()).isEqualTo(Phase.PASSIVE_EVALUATING);
        analyzer.failNext(new IllegalStateException("model crashed"));
        assertThat(driver.feed(Scenarios.live()).failure()).contains(FailureReason.ENGINE_ERROR);
        assertThat(session.retry()).isTrue();
        assertThat(session.state().phase()).isEqualTo(Phase.FACE_ACQUIRING);
    }

    @Test
    void assistedCaptureUsesAccessibilityPool() {
        LivenessPolicy p = Scenarios.policy(Workflow.RESIDENT).minChallenges(3).maxChallenges(3)
                .accessibilityPool(EnumSet.of(ChallengeType.BLINK, ChallengeType.SMILE, ChallengeType.HOLD_STILL, ChallengeType.TURN_LEFT)).build();
        open(p, Scenarios.context(Workflow.RESIDENT, true), ENGINE);
        driver.feedUntilLeaves(Scenarios.lowConfidence(), Phase.IDLE, 1);
        driver.feedUntilLeaves(Scenarios.lowConfidence(), Phase.PASSIVE_EVALUATING, 20);
        assertThat(session.challengeSequence()).containsExactlyInAnyOrder(ChallengeType.BLINK, ChallengeType.SMILE, ChallengeType.HOLD_STILL);
        assertThat(driver.performAllChallenges(Scenarios.liveSignals()).phase()).isEqualTo(Phase.PASSED);
        assertThat(audit.find(SESSION_STARTED).get(0).attributes()).containsEntry("assistedCapture", "true");
    }

    @Test
    void activeModeNeverFailsBelowThreshold() {
        open(Scenarios.policy(Workflow.RESIDENT).activeMode(LivenessPolicy.ActiveMode.NEVER).build());
        driver.feedUntilLeaves(Scenarios.lowConfidence(), Phase.IDLE, 1);
        SessionState s = driver.feedUntilLeaves(Scenarios.lowConfidence(), Phase.PASSIVE_EVALUATING, 20);
        assertThat(s.failure()).contains(FailureReason.LIVENESS_BELOW_THRESHOLD);
        assertThat(session.result().decision()).isEqualTo(LivenessResult.Decision.NOT_LIVE);
    }

    @Test
    void alwaysModeRunsChallengesAfterPassivePass() {
        open(Scenarios.policy(Workflow.SUPERVISOR).activeMode(LivenessPolicy.ActiveMode.ALWAYS).minChallenges(1).maxChallenges(1).build());
        driver.feedUntilLeaves(Scenarios.live(), Phase.IDLE, 1);
        driver.feedUntilLeaves(Scenarios.live(), Phase.PASSIVE_EVALUATING, 20);
        assertThat(session.state().phase()).isEqualTo(Phase.ACTIVE_CHALLENGE);
        assertThat(session.challengeSequence()).singleElement().matches(ChallengeType::revealsDepth);
        assertThat(driver.performAllChallenges(Scenarios.liveSignals()).phase()).isEqualTo(Phase.PASSED);
    }

    @Test
    void noSupportedChallengesFallsBackToPassiveDecision() {
        EngineInfo noChallenges = new EngineInfo("bare", "1", null, null, EnumSet.noneOf(ChallengeType.class), Set.of(), false);
        open(Scenarios.policy(Workflow.OPERATOR).activeMode(LivenessPolicy.ActiveMode.ALWAYS).build(), Scenarios.context(Workflow.OPERATOR, false), noChallenges);
        driver.feedUntilLeaves(Scenarios.live(), Phase.IDLE, 1);
        assertThat(driver.feedUntilLeaves(Scenarios.live(), Phase.PASSIVE_EVALUATING, 20).phase()).isEqualTo(Phase.PASSED);
        assertThat(session.result().modelBundleVersion()).isEqualTo("none");

        open(Scenarios.policy(Workflow.OPERATOR).build(), Scenarios.context(Workflow.OPERATOR, false), noChallenges);
        driver.feedUntilLeaves(Scenarios.lowConfidence(), Phase.IDLE, 1);
        assertThat(driver.feedUntilLeaves(Scenarios.lowConfidence(), Phase.PASSIVE_EVALUATING, 20).failure()).contains(FailureReason.LIVENESS_BELOW_THRESHOLD);
    }

    @Test
    void withoutEngineInfoAllChallengesAreAllowed() {
        open(Scenarios.policy(Workflow.RESIDENT).build(), Scenarios.context(Workflow.RESIDENT, false), null);
        driver.feedUntilLeaves(Scenarios.lowConfidence(), Phase.IDLE, 1);
        driver.feedUntilLeaves(Scenarios.lowConfidence(), Phase.PASSIVE_EVALUATING, 20);
        assertThat(driver.performAllChallenges(Scenarios.liveSignals()).phase()).isEqualTo(Phase.PASSED);
        assertThat(session.result().engineId()).isEqualTo("unknown");
        assertThat(session.captureDiagnostics().engineId()).isEqualTo("unknown");
    }

    @Test
    void sessionTimeoutDuringPassive() {
        open(Scenarios.policy(Workflow.RESIDENT).passiveMinFrames(1000).build());
        driver.feed(Scenarios.live(), 3);
        assertThat(session.state().phase()).isEqualTo(Phase.PASSIVE_EVALUATING);
        assertThat(session.state().progress()).isBetween(0.1, 0.5);
        time.advance(20_000);
        assertThat(session.tick().failure()).contains(FailureReason.SESSION_TIMEOUT);
    }

    @Test
    void sessionTimeoutDuringChallengeAbortsChallenge() {
        open(Scenarios.policy(Workflow.RESIDENT).sessionTimeoutMillis(1500).challengeTimeoutMillis(10_000).build());
        driver.feedUntilLeaves(Scenarios.lowConfidence(), Phase.IDLE, 1);
        driver.feedUntilLeaves(Scenarios.lowConfidence(), Phase.PASSIVE_EVALUATING, 20);
        assertThat(session.state().phase()).isEqualTo(Phase.ACTIVE_CHALLENGE);
        SessionState s = driver.feedUntilLeaves(Scenarios.lowConfidence(), Phase.ACTIVE_CHALLENGE, 20);
        assertThat(s.failure()).contains(FailureReason.SESSION_TIMEOUT);
        assertThat(session.result().challenges()).singleElement().extracting(ChallengeOutcome::result).isEqualTo(ChallengeOutcome.Result.TIMEOUT);
    }

    @Test
    void engineErrorsAndInvalidFrames() {
        open(Scenarios.policy(Workflow.RESIDENT).maxRetries(3).build());
        analyzer.failNext(new IllegalStateException("model crashed"));
        SessionState s = session.feed(Frames.frame());
        assertThat(s.failure()).contains(FailureReason.ENGINE_ERROR);
        assertThat(session.result().decision()).isEqualTo(LivenessResult.Decision.ERROR);
        assertThat(session.retry()).isTrue();
        analyzer.returnNullNext();
        assertThat(session.feed(Frames.frame()).failure()).contains(FailureReason.INVALID_FRAME_DATA);
        assertThat(session.retry()).isTrue();
        // an analyzer that rejects the frame itself (undecodable JPEG, truncated buffer) is bad data, not an engine fault
        analyzer.failNext(new IllegalArgumentException("truncated JPEG"));
        assertThat(session.feed(Frames.frame()).failure()).contains(FailureReason.INVALID_FRAME_DATA);
        assertThatThrownBy(() -> session.feed(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void deviceLifecycle() {
        open(Scenarios.policy(Workflow.RESIDENT).build());
        assertThat(session.deviceFault(FailureReason.DEVICE_UNAVAILABLE, "ignored while idle").phase()).isEqualTo(Phase.IDLE);
        assertThat(session.start().phase()).isEqualTo(Phase.DEVICE_CONNECTING);
        assertThat(session.tick().phase()).isEqualTo(Phase.DEVICE_CONNECTING);
        assertThat(session.deviceReady().phase()).isEqualTo(Phase.FACE_ACQUIRING);
        SessionState s = session.deviceReconnecting("usb glitch");
        assertThat(s.hint()).isEqualTo(Hint.DEVICE_RECONNECTING);
        assertThat(session.deviceReady().hint()).isEqualTo(Hint.NONE);
        assertThat(session.captureDiagnostics().deviceHealth()).isEqualTo("CONNECTED");
        s = session.deviceFault(FailureReason.DEVICE_CONNECTION_FAILURE, "unplugged");
        assertThat(s.phase()).isEqualTo(Phase.FAILED);
        assertThat(s.hint()).isEqualTo(Hint.DEVICE_DISCONNECTED);
        assertThat(session.result().decision()).isEqualTo(LivenessResult.Decision.ERROR);
        assertThat(session.deviceFault(FailureReason.DEVICE_CONNECTION_FAILURE, "again").hint()).isEqualTo(Hint.DEVICE_DISCONNECTED);
        assertThat(session.deviceReconnecting("not live").phase()).isEqualTo(Phase.FAILED);
        assertThat(audit.find(LivenessAuditEvent.DEVICE_FAULT)).hasSize(3);
        assertThat(metrics.snapshot().deviceFaultsByKind()).containsEntry("RECONNECTING", 1L).containsEntry("DEVICE_CONNECTION_FAILURE", 2L);
        assertThat(session.deviceReady().phase()).isEqualTo(Phase.FAILED);
    }

    @Test
    void deviceConnectTimeout() {
        open(Scenarios.policy(Workflow.RESIDENT).maxRetries(1).build());
        session.start();
        time.advance(19_000);
        assertThat(session.tick().phase()).isEqualTo(Phase.DEVICE_CONNECTING);
        time.advance(1_000);
        SessionState s = session.tick();
        assertThat(s.phase()).isEqualTo(Phase.LOCKED);
        assertThat(s.failure()).contains(FailureReason.DEVICE_CONNECTION_FAILURE);
        assertThat(session.result().decision()).isEqualTo(LivenessResult.Decision.ERROR);
        // A late first frame counts as the device connecting; the attempt budget then decides.
        open(Scenarios.policy(Workflow.RESIDENT).maxRetries(1).build());
        session.start();
        time.advance(20_000);
        assertThat(session.feed(Frames.frame()).failure()).contains(FailureReason.FACE_NOT_DETECTED);
    }

    @Test
    void cancelAndClose() {
        open(Scenarios.policy(Workflow.RESIDENT).build());
        driver.feedUntilLeaves(Scenarios.lowConfidence(), Phase.IDLE, 1);
        driver.feedUntilLeaves(Scenarios.lowConfidence(), Phase.PASSIVE_EVALUATING, 20);
        session.cancel();
        SessionState s = session.state();
        assertThat(s.phase()).isEqualTo(Phase.CANCELLED);
        assertThat(s.isTerminal()).isTrue();
        LivenessResult r = session.result();
        assertThat(r.decision()).isEqualTo(LivenessResult.Decision.CANCELLED);
        assertThat(r.failure()).contains(FailureReason.CANCELLED);
        assertThat(r.challenges()).singleElement().extracting(ChallengeOutcome::result).isEqualTo(ChallengeOutcome.Result.ABORTED);
        session.close();
        session.close();
        assertThat(analyzer.closes()).isEqualTo(1);

        open(Scenarios.policy(Workflow.RESIDENT).build());
        assertThatThrownBy(() -> session.result()).isInstanceOf(IllegalStateException.class);
        session.close();
        assertThat(session.state().phase()).isEqualTo(Phase.CANCELLED);
        assertThat(session.result().totalMillis()).isZero();
    }

    @Test
    void listenersAreIsolatedAndOnlyNotifiedOnChange() {
        open(Scenarios.policy(Workflow.RESIDENT).build());
        session.addListener((prev, cur) -> { throw new IllegalStateException("ui bug"); });
        driver.feed(Scenarios.live(), 3);
        int n = transitions.size();
        driver.feed(Scenarios.live());
        assertThat(transitions).hasSize(n); // same phase and hint: no notification
        assertThatThrownBy(() -> session.addListener(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void auditSinkFailureNeverBreaksSession() {
        session = LivenessOrchestrator.builder(analyzer, Scenarios.policy(Workflow.RESIDENT).build(), Scenarios.context(Workflow.RESIDENT, false))
                .timeSource(time).auditSink(r -> { throw new IllegalStateException("disk full"); }).build();
        driver = new Scenarios.Driver(session, analyzer, time);
        driver.feedUntilLeaves(Scenarios.live(), Phase.IDLE, 1);
        assertThat(driver.feedUntilLeaves(Scenarios.live(), Phase.PASSIVE_EVALUATING, 20).phase()).isEqualTo(Phase.PASSED);
    }

    @Test
    void diagnosticsSnapshot() {
        open(Scenarios.policy(Workflow.RESIDENT).build());
        driver.feedUntilLeaves(Scenarios.lowConfidence(), Phase.IDLE, 1);
        driver.feedUntilLeaves(Scenarios.lowConfidence(), Phase.PASSIVE_EVALUATING, 20);
        driver.feed(Scenarios.lowConfidence());
        DiagnosticSnapshot d = session.captureDiagnostics();
        assertThat(d.phase()).isEqualTo("ACTIVE_CHALLENGE");
        assertThat(d.engineId()).isEqualTo("fake-engine");
        assertThat(d.modelBundleId()).isEqualTo("bundle-a");
        assertThat(d.framesPerSecond()).isBetween(9.9, 10.1);
        assertThat(d.lastSignalScores()).containsKeys(Signals.CNN_TEXTURE, Signals.MOIRE);
        assertThat(d.extra()).containsKeys("challenge", "actionProgress", "identityDistance", "attempt", "hint");
        assertThat(d.policyVersion()).isEqualTo("test-1");
        assertThat(session.actionProgress()).isBetween(0.0, 1.0);
        assertThat(session.policy().policyVersion()).isEqualTo("test-1");
        assertThat(session.context().sessionId()).isEqualTo("session-resident");
    }

    @Test
    void constructorsAndBuilderValidation() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        LivenessPolicy p = Scenarios.policy(Workflow.RESIDENT).build();
        SessionContext ctx = Scenarios.context(Workflow.RESIDENT, false);
        LivenessOrchestrator a = new LivenessOrchestrator(analyzer, p, ctx, clock, new Random(1), audit, metrics);
        assertThat(a.start().phase()).isEqualTo(Phase.DEVICE_CONNECTING);
        long[] nanos = {0};
        LivenessOrchestrator b = new LivenessOrchestrator(analyzer, p, ctx, () -> nanos[0], new Random(1), audit, metrics);
        assertThat(b.state().phase()).isEqualTo(Phase.IDLE);
        assertThatThrownBy(() -> LivenessOrchestrator.builder(analyzer, p, Scenarios.context(Workflow.OPERATOR, false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LivenessOrchestrator.builder(null, p, ctx)).isInstanceOf(NullPointerException.class);
        LivenessOrchestrator c = LivenessOrchestrator.builder(analyzer, p, ctx).clock(clock).fusionAlpha(0.5).anchorFrames(2)
                .actionThresholds(io.mosip.vivid.core.action.ActionThresholds.defaults()).build();
        assertThat(c.state().maxAttempts()).isEqualTo(2);
        assertThatThrownBy(() -> LivenessOrchestrator.builder(analyzer, p, ctx).fusionAlpha(0).build()).isInstanceOf(IllegalArgumentException.class);
    }
}
