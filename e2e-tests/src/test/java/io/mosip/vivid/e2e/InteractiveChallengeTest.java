package io.mosip.vivid.e2e;

import io.mosip.vivid.api.*;
import io.mosip.vivid.core.action.ActionThresholds;
import io.mosip.vivid.core.session.LivenessOrchestrator;
import io.mosip.vivid.core.session.LivenessSessionFactory;
import io.mosip.vivid.core.session.TimeSource;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.PixelFormat;
import io.mosip.vivid.device.mock.render.ExpressionEvent;
import io.mosip.vivid.device.mock.render.ExpressionEvent.Action;
import io.mosip.vivid.device.mock.render.ExpressionTimeline;
import io.mosip.vivid.device.mock.render.FaceState;
import io.mosip.vivid.device.mock.render.SyntheticFaceRenderer;
import io.mosip.vivid.engine.onnx.OnnxEngineFactory;
import io.mosip.vivid.telemetry.audit.InMemoryAuditSink;
import io.mosip.vivid.telemetry.audit.LivenessAuditEvent;
import io.mosip.vivid.telemetry.metrics.LivenessMetrics;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Closed-loop challenge-response test through the real orchestrator and the real ONNX engine.
 * A responsive subject watches the session and performs each prompted action after a human reaction delay;
 * a replay subject plays a fixed recording that blinks and turns on its own schedule and ignores prompts.
 * The mock's scripted scenarios cannot test this, because they cannot react to a randomly drawn prompt.
 */
class InteractiveChallengeTest {
    private static final Logger LOG = LoggerFactory.getLogger(InteractiveChallengeTest.class);
    private static final int FPS = 15;
    private static final long FRAME_MS = 1000 / FPS;
    private static final long REACTION_MS = 700;
    private static final int TRIALS = Integer.getInteger("e2e.trials", 12);
    /**
     * Pitch tests take pitch and vertical gaze from the drawn landmarks ({@link DrawnLandmarkPitchEngine}). Set
     * {@code -De2e.yunetPitch=true} to measure them with YuNet's landmarks instead (expected to fail on the cartoon).
     */
    private static final boolean DRAWN_PITCH = !Boolean.getBoolean("e2e.yunetPitch");
    /** Actions whose landmarks YuNet localises on the drawn face. */
    private static final Set<ChallengeType> DRAWN_FACE_POOL =
            EnumSet.of(ChallengeType.BLINK, ChallengeType.SMILE, ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT, ChallengeType.OPEN_MOUTH);
    /** The same plus the three pitch challenges. */
    private static final Set<ChallengeType> PITCH_POOL = EnumSet.of(ChallengeType.BLINK, ChallengeType.SMILE, ChallengeType.TURN_LEFT,
            ChallengeType.TURN_RIGHT, ChallengeType.OPEN_MOUTH, ChallengeType.NOD, ChallengeType.LOOK_UP, ChallengeType.LOOK_DOWN);
    private static final long MAX_LATENCY_MS = Long.getLong("e2e.maxLatencyMs", 3000);
    private static final long CHALLENGE_TIMEOUT_MS = Long.getLong("e2e.challengeTimeoutMs", 8000);

    private interface Subject { void onChallenge(Challenge c, long nowMs, List<ExpressionEvent> events); }

    /** Performs exactly the prompted action after a reaction delay. */
    private static final Subject RESPONSIVE = (c, now, ev) -> {
        long t = now + REACTION_MS;
        switch (c.type()) {
            case BLINK -> ev.add(ExpressionEvent.blinkAt(t));
            case BLINK_TWICE -> { ev.add(ExpressionEvent.blinkAt(t)); ev.add(ExpressionEvent.blinkAt(t + 600)); }
            case SMILE -> ev.add(ExpressionEvent.between(Action.SMILE, t, t + 1200, 1.0));
            case OPEN_MOUTH -> ev.add(ExpressionEvent.between(Action.OPEN_MOUTH, t, t + 1200, 1.0));
            case RAISE_EYEBROWS -> ev.add(ExpressionEvent.between(Action.RAISE_EYEBROWS, t, t + 1200, 1.0));
            case TURN_LEFT -> ev.add(ExpressionEvent.between(Action.YAW, t, t + 1500, 30));
            case TURN_RIGHT -> ev.add(ExpressionEvent.between(Action.YAW, t, t + 1500, -30));
            case LOOK_UP -> ev.add(ExpressionEvent.between(Action.PITCH, t, t + 1500, 22));
            case LOOK_DOWN -> ev.add(ExpressionEvent.between(Action.PITCH, t, t + 1500, -22));
            // A nod: head down about 20 degrees and back within a second. (The mock's NOD action is a full sine cycle
            // that swings up as far as it went down, which a person rarely does and which reads as an unprompted LOOK_UP.)
            case NOD -> ev.add(ExpressionEvent.between(Action.PITCH, t, t + 1000, -20));
            case HOLD_STILL -> ev.add(ExpressionEvent.between(Action.HOLD_STILL, t, t + 2500, 1.0));
        }
    };

    /** A recording: ignores prompts entirely. Its own schedule is fixed in {@link #replayRecording()}. */
    private static final Subject REPLAY = (c, now, ev) -> { };

    private static List<ExpressionEvent> replayRecording() {
        List<ExpressionEvent> ev = new ArrayList<>();
        for (long t = 800; t < 40_000; t += 3_100) ev.add(ExpressionEvent.blinkAt(t));
        for (long t = 5_000; t < 40_000; t += 9_000) ev.add(ExpressionEvent.between(Action.YAW, t, t + 1500, 28));
        for (long t = 9_500; t < 40_000; t += 9_000) ev.add(ExpressionEvent.between(Action.SMILE, t, t + 1200, 1.0));
        return ev;
    }

    private record Outcome(SessionState.Phase phase, LivenessResult.Decision decision, String reason, List<ChallengeType> challenges, long ms) {}

    /** Deterministic challenge draws so the measured pass counts are reproducible regression values. */
    private static SecureRandom seeded(long seed) {
        try {
            SecureRandom r = SecureRandom.getInstance("SHA1PRNG");
            r.setSeed(seed);
            return r;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @param drawnPitch take pitch from the drawn landmarks through the engine's pose estimator (see
     *                   {@link DrawnLandmarkPitchEngine}) instead of from YuNet's landmarks
     */
    private static Outcome runOnce(LivenessEngine engine, LivenessPolicy policy, Subject subject, List<ExpressionEvent> initial, long seed, boolean drawnPitch) {
        AtomicLong clock = new AtomicLong();
        SyntheticFaceRenderer renderer = new SyntheticFaceRenderer(640, 480);
        AtomicReference<double[]> drawn = new AtomicReference<>();
        LivenessEngine used = drawnPitch ? new DrawnLandmarkPitchEngine(engine, drawn::get) : engine;
        InMemoryAuditSink audit = new InMemoryAuditSink(2000);
        LivenessSessionFactory factory = new LivenessSessionFactory(used, w -> policy, audit, new LivenessMetrics(),
                TimeSource.of(clock::get), seeded(seed), ActionThresholds.defaults());
        SessionContext ctx = factory.newContext(policy.workflow(), Locale.ENGLISH, false, false);
        List<ExpressionEvent> events = new ArrayList<>(initial);
        Set<Integer> answered = new HashSet<>();
        try (LivenessOrchestrator session = factory.open(policy, ctx)) {
            SessionState state = session.state();
            long t = 0;
            for (int seq = 0; t < 45_000; seq++, t += FRAME_MS) {
                state.challenge().filter(c -> answered.add(c.index())).ifPresent(c -> subject.onChallenge(c, clockMs(clock), events));
                FaceState face = ExpressionTimeline.of(events).stateAt(t);
                BufferedImage img = renderer.render(face);
                drawn.set(renderer.landmarks(face));
                clock.set(t * 1_000_000L);
                state = session.feed(toFrame(img, seq, t));
                if (state.isTerminal() || state.phase() == SessionState.Phase.FAILED) break;
            }
            LivenessResult r = session.result();
            String reason = (r == null ? "none" : r.failure().map(Enum::name).orElse("-"))
                    + audit.last(LivenessAuditEvent.UNPROMPTED_ACTION).map(u -> " (unprompted " + u.attributes().get("unprompted") + " during " + u.attributes().get("type") + ")").orElse("");
            return new Outcome(state.phase(), r == null ? null : r.decision(), reason,
                    session.challengeSequence(), t);
        }
    }

    private static long clockMs(AtomicLong clock) { return clock.get() / 1_000_000L; }

    private static FaceFrame toFrame(BufferedImage img, long seq, long tMs) {
        int w = img.getWidth(), h = img.getHeight();
        byte[] rgb = new byte[w * h * 3];
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0, j = 0; i < px.length; i++) {
            rgb[j++] = (byte) (px[i] >> 16); rgb[j++] = (byte) (px[i] >> 8); rgb[j++] = (byte) px[i];
        }
        return FaceFrame.builder().sequence(seq).timestampNanos(tMs * 1_000_000L).width(w).height(h).format(PixelFormat.RGB888).pixels(rgb).build();
    }

    private static LivenessPolicy alwaysChallenge(int min, int max, Set<ChallengeType> pool) {
        return LivenessPolicy.builder(Workflow.RESIDENT).policyVersion("e2e-interactive")
                .activeMode(LivenessPolicy.ActiveMode.ALWAYS).minChallenges(min).maxChallenges(max).maxRetries(1)
                .challengePool(pool)
                // Isolate challenge-response: the cartoon subject has no pulse and is planar, so rPPG and parallax
                // correctly reject it within seconds (see pulselessPlanarSubjectIsRejectedByRppgAndParallax).
                .signalWeight(Signals.RPPG, 0).signalWeight(Signals.PARALLAX, 0)
                .maxActionLatencyMillis(MAX_LATENCY_MS).challengeTimeoutMillis(CHALLENGE_TIMEOUT_MS)
                .build();
    }

    private static Map<String, Integer> trials(LivenessEngine engine, LivenessPolicy policy, Subject subject, List<ExpressionEvent> initial, String label) {
        return trials(engine, policy, subject, initial, label, false);
    }

    private static Map<String, Integer> trials(LivenessEngine engine, LivenessPolicy policy, Subject subject, List<ExpressionEvent> initial, String label, boolean drawnPitch) {
        Map<String, Integer> tally = new TreeMap<>();
        for (int i = 0; i < TRIALS; i++) {
            Outcome o = runOnce(engine, policy, subject, initial, 1_000L + i, drawnPitch);
            String key = o.phase() == SessionState.Phase.PASSED ? "PASSED" : o.phase() + "/" + o.reason();
            tally.merge(key, 1, Integer::sum);
            LOG.warn("{} trial {}: {} {} challenges={} at {} ms", label, i + 1, o.phase(), o.reason(), o.challenges(), o.ms());
        }
        LOG.warn("{} summary: {}", label, tally);
        return tally;
    }

    /**
     * Two-challenge sequences. A live subject must always pass. The replay can only pass when the draw is exactly the
     * two gestures it performs, in its own order, inside human reaction time. This is a known residual weakness of
     * two-challenge sequences (measured 3 of 12 and 5 of 30 seeded trials); the bound guards against regression.
     * {@link #threeChallengeSequences()} shows the fix.
     */
    @Test
    void twoChallengeSequences() {
        assumeTrue(SyntheticSubjectEngine.available());
        try (LivenessEngine engine = SyntheticSubjectEngine.create()) {
            LivenessPolicy policy = alwaysChallenge(2, 2, DRAWN_FACE_POOL);
            assertThat(trials(engine, policy, RESPONSIVE, List.of(ExpressionEvent.blinkAt(900)), "2ch-live").getOrDefault("PASSED", 0))
                    .as("responsive live subject").isEqualTo(TRIALS);
            assertThat(trials(engine, policy, REPLAY, replayRecording(), "2ch-replay").getOrDefault("PASSED", 0))
                    .as("prompt-ignoring replay").isLessThanOrEqualTo(Math.max(1, TRIALS / 4));
        }
    }

    /** Three-challenge sequences: a recording with two gestures can no longer match the whole draw. */
    @Test
    void threeChallengeSequences() {
        assumeTrue(SyntheticSubjectEngine.available());
        try (LivenessEngine engine = SyntheticSubjectEngine.create()) {
            LivenessPolicy policy = alwaysChallenge(3, 3, DRAWN_FACE_POOL);
            assertThat(trials(engine, policy, RESPONSIVE, List.of(ExpressionEvent.blinkAt(900)), "3ch-live").getOrDefault("PASSED", 0))
                    .as("responsive live subject").isEqualTo(TRIALS);
            assertThat(trials(engine, policy, REPLAY, replayRecording(), "3ch-replay").getOrDefault("PASSED", 0))
                    .as("prompt-ignoring replay").isZero();
        }
    }

    /**
     * NOD, LOOK_UP and LOOK_DOWN in the pool, two-challenge sequences. YuNet cannot localise the drawn nose, so pitch
     * comes from the drawn landmarks through the engine's own estimator and per-session baseline
     * ({@link DrawnLandmarkPitchEngine}); everything else is as in {@link #twoChallengeSequences()}.
     */
    @Test
    void twoChallengeSequencesWithPitchChallenges() {
        assumeTrue(SyntheticSubjectEngine.available());
        try (LivenessEngine engine = SyntheticSubjectEngine.create()) {
            LivenessPolicy policy = alwaysChallenge(2, 2, PITCH_POOL);
            assertThat(trials(engine, policy, RESPONSIVE, List.of(ExpressionEvent.blinkAt(900)), "2ch-pitch-live", DRAWN_PITCH).getOrDefault("PASSED", 0))
                    .as("responsive live subject").isEqualTo(TRIALS);
            assertThat(trials(engine, policy, REPLAY, replayRecording(), "2ch-pitch-replay", DRAWN_PITCH).getOrDefault("PASSED", 0))
                    .as("prompt-ignoring replay").isLessThanOrEqualTo(Math.max(1, TRIALS / 4));
        }
    }

    /** As {@link #twoChallengeSequencesWithPitchChallenges()} with three challenges. */
    @Test
    void threeChallengeSequencesWithPitchChallenges() {
        assumeTrue(SyntheticSubjectEngine.available());
        try (LivenessEngine engine = SyntheticSubjectEngine.create()) {
            LivenessPolicy policy = alwaysChallenge(3, 3, PITCH_POOL);
            assertThat(trials(engine, policy, RESPONSIVE, List.of(ExpressionEvent.blinkAt(900)), "3ch-pitch-live", DRAWN_PITCH).getOrDefault("PASSED", 0))
                    .as("responsive live subject").isEqualTo(TRIALS);
            assertThat(trials(engine, policy, REPLAY, replayRecording(), "3ch-pitch-replay", DRAWN_PITCH).getOrDefault("PASSED", 0))
                    .as("prompt-ignoring replay").isZero();
        }
    }
}
