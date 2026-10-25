package io.mosip.vivid.core.testing;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.FaceObservation;
import io.mosip.vivid.api.FaceQuality;
import io.mosip.vivid.api.FrameAnalysis;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.SessionContext;
import io.mosip.vivid.api.SessionState;
import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.core.session.LivenessOrchestrator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Scenario vocabulary for orchestrator tests: signal sets for live subjects and attacks, face observations for
 * every challenge type, quality profiles, and a driver that feeds frames at a fixed cadence.
 */
public final class Scenarios {

    /** Frame interval used by the driver. */
    public static final long STEP_MS = 100;

    private Scenarios() {}

    // ---- policy / context ------------------------------------------------------------------------------------

    /** A fast policy for tests: 500 ms / 5 frame passive window, 4 s challenges, 20 s attempts. */
    public static LivenessPolicy.Builder policy(Workflow wf) {
        return LivenessPolicy.builder(wf).policyVersion("test-1")
                .passiveWindowMillis(500).passiveMinFrames(5).challengeTimeoutMillis(4000)
                .minActionLatencyMillis(150).maxActionLatencyMillis(3000).sessionTimeoutMillis(20_000)
                .maxRetries(2).passiveThreshold(0.8).padHardThreshold(0.9);
    }

    public static SessionContext context(Workflow wf, boolean assisted) {
        byte[] nonce = new byte[16];
        for (int i = 0; i < nonce.length; i++) {
            nonce[i] = (byte) (i * 17);
        }
        return new SessionContext(wf, "session-" + wf.name().toLowerCase(Locale.ROOT), nonce, Locale.ENGLISH, true, assisted);
    }

    // ---- signals ---------------------------------------------------------------------------------------------

    public static List<SignalScore> liveSignals() {
        return List.of(SignalScore.of(Signals.CNN_TEXTURE, 0.05, 0.9), SignalScore.of(Signals.MOIRE, 0.05, 0.9),
                SignalScore.of(Signals.QUALITY, 0.0, 1.0));
    }

    public static List<SignalScore> lowConfidenceSignals() {
        return List.of(SignalScore.of(Signals.CNN_TEXTURE, 0.40, 0.9), SignalScore.of(Signals.MOIRE, 0.35, 0.9));
    }

    public static List<SignalScore> photoAttackSignals() {
        return List.of(new SignalScore(Signals.CNN_TEXTURE, 0.97, 0.95, false, Signals.ATTACK_PRINT), SignalScore.of(Signals.MOIRE, 0.2, 0.9));
    }

    public static List<SignalScore> screenAttackSignals() {
        return List.of(SignalScore.of(Signals.CNN_TEXTURE, 0.5, 0.9), SignalScore.veto(Signals.MOIRE, Signals.ATTACK_SCREEN));
    }

    /** Each signal is individually below the veto confidence, but together they collapse the fused score. */
    public static List<SignalScore> collapsingSignals() {
        return List.of(SignalScore.of(Signals.CNN_TEXTURE, 0.95, 0.4), SignalScore.of(Signals.MOIRE, 0.95, 0.4),
                SignalScore.of(Signals.COLOR_STATS, 0.95, 0.4));
    }

    // ---- faces ---------------------------------------------------------------------------------------------

    public static FaceObservation.Builder neutralFace() {
        return FaceObservation.builder().box(0.3, 0.25, 0.4, 0.5).pose(0, 0, 0).eyeAspect(0.3, 0.3).mouthAspect(0.2);
    }

    public static FaceQuality goodQuality() {
        return new FaceQuality(1, 0.9, 0.9, 0.9, 1.0, 0.9);
    }

    public static float[] embedding(int axis) {
        float[] e = new float[8];
        e[axis] = 1f;
        return e;
    }

    // ---- analyses ------------------------------------------------------------------------------------------

    public static FrameAnalysis analysis(FaceQuality q, FaceObservation face, List<SignalScore> signals, float[] embedding) {
        return new FrameAnalysis(0, 0, q, face, signals, embedding, 1_000_000);
    }

    public static FrameAnalysis live() {
        return analysis(goodQuality(), neutralFace().build(), liveSignals(), embedding(0));
    }

    public static FrameAnalysis lowConfidence() {
        return analysis(goodQuality(), neutralFace().build(), lowConfidenceSignals(), embedding(0));
    }

    public static FrameAnalysis withFace(FaceObservation face, List<SignalScore> signals) {
        return analysis(goodQuality(), face, signals, embedding(0));
    }

    public static FrameAnalysis noFace() {
        return analysis(FaceQuality.noFace(), null, List.of(), null);
    }

    public static FrameAnalysis multipleFaces() {
        return analysis(new FaceQuality(2, 0.9, 0.9, 0.9, 1.0, 0.9), null, List.of(), null);
    }

    public static FrameAnalysis poorQuality(FaceQuality q) {
        return analysis(q, neutralFace().build(), liveSignals(), embedding(0));
    }

    // ---- challenge performances ------------------------------------------------------------------------------

    /**
     * Observations that complete the given challenge when fed at {@link #STEP_MS} intervals, starting with three
     * neutral frames so the onset latency falls inside the human window.
     */
    public static List<FaceObservation> perform(ChallengeType type) {
        List<FaceObservation> out = new ArrayList<>();
        FaceObservation neutral = neutralFace().build();
        out.add(neutral);
        out.add(neutral);
        out.add(neutral);
        switch (type) {
            case BLINK:
                out.add(neutralFace().eyeAspect(0.1, 0.1).build());
                out.add(neutral);
                out.add(neutral);
                break;
            case BLINK_TWICE:
                out.add(neutralFace().eyeAspect(0.1, 0.1).build());
                out.add(neutral);
                out.add(neutral);
                out.add(neutralFace().eyeAspect(0.1, 0.1).build());
                out.add(neutral);
                break;
            case SMILE:
                out.addAll(Collections.nCopies(4, neutralFace().smileScore(0.8).build()));
                break;
            case OPEN_MOUTH:
                out.addAll(Collections.nCopies(4, neutralFace().mouthAspect(0.7).build()));
                break;
            case RAISE_EYEBROWS:
                out.addAll(Collections.nCopies(4, neutralFace().browRaiseScore(0.8).build()));
                break;
            case TURN_LEFT:
                out.add(neutralFace().pose(25, 0, 0).build());
                out.add(neutralFace().pose(25, 0, 0).build());
                out.add(neutral);
                break;
            case TURN_RIGHT:
                out.add(neutralFace().pose(-25, 0, 0).build());
                out.add(neutralFace().pose(-25, 0, 0).build());
                out.add(neutral);
                break;
            case LOOK_UP:
                out.add(neutralFace().pose(0, 20, 0).build());
                out.add(neutral);
                break;
            case LOOK_DOWN:
                out.add(neutralFace().pose(0, -20, 0).build());
                out.add(neutral);
                break;
            case NOD:
                out.add(neutralFace().pose(0, -15, 0).build());
                out.add(neutral);
                break;
            case HOLD_STILL:
                out.addAll(Collections.nCopies(17, neutral));
                break;
            default:
                throw new IllegalArgumentException(type.name());
        }
        return out;
    }

    // ---- driver ----------------------------------------------------------------------------------------------

    /** Feeds scripted analyses to an orchestrator at a fixed cadence. */
    public static final class Driver {
        private final LivenessOrchestrator session;
        private final FakeFrameAnalyzer analyzer;
        private final FakeTime time;

        public Driver(LivenessOrchestrator session, FakeFrameAnalyzer analyzer, FakeTime time) {
            this.session = session;
            this.analyzer = analyzer;
            this.time = time;
        }

        /** Advances the clock by one step and feeds one analysis. */
        public SessionState feed(FrameAnalysis a) {
            time.advance(STEP_MS);
            analyzer.enqueue(a);
            return session.feed(Frames.frame(time.nanoTime()));
        }

        /** Feeds {@code n} copies of an analysis. */
        public SessionState feed(FrameAnalysis a, int n) {
            SessionState s = session.state();
            for (int i = 0; i < n; i++) {
                s = feed(a);
            }
            return s;
        }

        /** Feeds frames until the phase leaves {@code phase} or {@code max} frames were fed. */
        public SessionState feedUntilLeaves(FrameAnalysis a, SessionState.Phase phase, int max) {
            SessionState s = session.state();
            for (int i = 0; i < max && session.state().phase() == phase; i++) {
                s = feed(a);
            }
            return s;
        }

        /** Performs the current challenge with the given passive signals. */
        public SessionState performCurrentChallenge(List<SignalScore> signals) {
            io.mosip.vivid.api.Challenge current = session.currentChallenge().orElseThrow();
            SessionState s = session.state();
            for (FaceObservation face : perform(current.type())) {
                s = feed(withFace(face, signals));
                if (session.currentChallenge().map(c -> c != current).orElse(true)) {
                    break;
                }
            }
            return s;
        }

        /** Performs every challenge until the session leaves the active phase. */
        public SessionState performAllChallenges(List<SignalScore> signals) {
            SessionState s = session.state();
            int guard = 0;
            while (session.state().phase() == SessionState.Phase.ACTIVE_CHALLENGE && guard++ < 10) {
                s = performCurrentChallenge(signals);
            }
            return s;
        }
    }
}
