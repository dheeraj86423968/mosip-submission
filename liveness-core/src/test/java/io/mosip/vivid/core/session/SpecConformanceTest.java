package io.mosip.vivid.core.session;

import io.mosip.vivid.api.ChallengeOutcome;
import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.FaceObservation;
import io.mosip.vivid.api.FaceQuality;
import io.mosip.vivid.api.FailureReason;
import io.mosip.vivid.api.FrameAnalysis;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.LivenessResult;
import io.mosip.vivid.api.SessionState;
import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.core.action.ActionDetector;
import io.mosip.vivid.core.action.ActionStatus;
import io.mosip.vivid.core.session.SpecModel.Constants;
import io.mosip.vivid.core.session.SpecModel.State;
import io.mosip.vivid.core.session.SpecModel.Step;
import io.mosip.vivid.core.testing.FakeFrameAnalyzer;
import io.mosip.vivid.core.testing.FakeTime;
import io.mosip.vivid.core.testing.Frames;
import io.mosip.vivid.core.testing.Scenarios;
import io.mosip.vivid.telemetry.audit.InMemoryAuditSink;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Model-based trace conformance between the verified TLA+ model ({@code formal/LivenessOrchestrator.tla}) and the
 * hand-written {@link LivenessOrchestrator} (PLAN.md section 17.7).
 *
 * <p>Thousands of random walks over the spec's {@code Next} relation ({@link SpecModel}) are replayed against the real
 * orchestrator with a fake clock, a scripted engine and scripted action detectors. Every environment or client
 * action is mapped to concrete orchestrator inputs (frames with chosen scores, vetoes and embeddings, detector
 * outcomes, clock jumps, device faults, retry/cancel); after every step the orchestrator's observable state must equal
 * the spec state reached by that action followed by the orchestrator's own internal steps ({@code PassiveDecide},
 * {@code Lock}), which the Java code takes in the same call. Between steps, stuttering inputs (gated frames, ticks,
 * refused retries, device blips, input after a terminal phase) must leave the state unchanged.</p>
 *
 * <p>When a TLC state-graph dump is present ({@code VIVID_TLC_DUMP_DIR}, written by {@code formal/conformance.sh}),
 * the transliteration itself is checked first: breadth-first search over {@link SpecModel} must reach exactly TLC's
 * states and edges.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SpecConformanceTest {

    static final int TRACES = Integer.getInteger("vivid.conformance.traces", 3000);
    static final long SEED = Long.getLong("vivid.conformance.seed", 20261024L);
    static final int MAX_STEPS = 80;

    // ---- mapping of the abstract model onto concrete policy values ------------------------------------------------
    static final double THRESHOLD = 0.80;
    static final double SCORE_STEP = 0.04;
    static final long PASSIVE_WINDOW_MS = 1;
    static final long SESSION_TIMEOUT_MS = 50_000;
    /** Longer than the attempt timer so that a session timeout during a challenge is observable (spec SessionTimeout). */
    static final long CHALLENGE_TIMEOUT_MS = 100_000;
    static final long MIN_LATENCY_MS = 200;
    static final long IN_WINDOW_ONSET_MS = 500;
    static final long TOO_FAST_ONSET_MS = 10;
    static final float[] ANCHOR = Scenarios.embedding(0);
    static final float[] OTHER_PERSON = Scenarios.embedding(1);
    static final List<String> CONFIGS = List.of("LivenessOrchestrator.cfg", "LivenessOrchestrator-supervisor.cfg");

    // ---- reports shared by the tests in this class --------------------------------------------------------------
    private static final Set<String> observedPhaseTransitions = new TreeSet<>();

    @Order(1)
    @ParameterizedTest
    @ValueSource(strings = {"LivenessOrchestrator.cfg", "LivenessOrchestrator-supervisor.cfg"})
    void transliterationMatchesTlcStateGraph(String cfg) throws IOException {
        Path dump = dumpFor(cfg);
        Assumptions.assumeTrue(dump != null, "no TLC dump; run formal/conformance.sh to check the transliteration against TLC");
        SpecModel model = new SpecModel(Constants.parse(formalDir().resolve(cfg)));
        TlcStateGraph tlc = TlcStateGraph.read(dump);
        Set<String> states = new HashSet<>();
        Set<String> edges = new HashSet<>();
        Deque<State> queue = new ArrayDeque<>(model.init());
        model.init().forEach(s -> states.add(s.key()));
        while (!queue.isEmpty()) {
            State s = queue.poll();
            for (Step st : model.next(s)) {
                edges.add(s.key() + " -> " + st.to().key());
                if (states.add(st.to().key())) {
                    queue.add(st.to());
                }
            }
        }
        assertThat(states).as("states reached by SpecModel vs TLC for " + cfg).hasSameSizeAs(tlc.states).isEqualTo(tlc.states);
        assertThat(edges).as("edges of SpecModel vs TLC for " + cfg).hasSameSizeAs(tlc.edges).isEqualTo(tlc.edges);
        System.out.printf("[conformance] %s: SpecModel == TLC state graph (%d states, %d edges)%n", cfg, states.size(), edges.size());
    }

    @Order(2)
    @ParameterizedTest
    @ValueSource(strings = {"LivenessOrchestrator.cfg", "LivenessOrchestrator-supervisor.cfg"})
    void orchestratorRefinesTheSpecOnRandomTraces(String cfg) throws IOException {
        SpecModel model = new SpecModel(Constants.parse(formalDir().resolve(cfg)));
        Path dump = dumpFor(cfg);
        TlcStateGraph tlc = dump == null ? null : TlcStateGraph.read(dump);
        Coverage cov = new Coverage();
        for (int trace = 0; trace < TRACES; trace++) {
            runTrace(model, new Random(SEED * 31 + trace + cfg.hashCode()), cov, trace, tlc);
        }
        String report = cov.report(cfg, model, tlc);
        System.out.println(report);
        Path out = Path.of("target", "conformance");
        Files.createDirectories(out);
        Files.writeString(out.resolve(cfg.replace(".cfg", ".txt")), report);
        // Every spec action the Java orchestrator can be driven through was exercised.
        assertThat(cov.actions.keySet()).containsAll(List.of("Start", "Retry", "Cancel", "DeviceConnected", "DeviceFailed", "FaceAcquired",
                "AcquireFailed", "PassiveFrame", "PassiveDecide", "ChallengeFrame", "ChallengeTimeout", "EngineFault", "SessionTimeout", "Lock"));
        assertThat(cov.terminal.keySet()).contains("PASSED", "LOCKED", "CANCELLED");
        observedPhaseTransitions.addAll(cov.phaseTransitions);
    }

    @Test
    @Order(3)
    void generatedTablesMatchTheApiAndCoverObservedTransitions() throws IOException {
        assertThat(Arrays.stream(GeneratedSpecTables.SpecPhase.values()).map(Enum::name))
                .containsExactlyElementsOf(Arrays.stream(SessionState.Phase.values()).map(Enum::name).collect(Collectors.toList()));
        Set<String> javaReasons = Arrays.stream(FailureReason.values()).map(Enum::name).collect(Collectors.toSet());
        javaReasons.add("NONE");
        assertThat(javaReasons).containsAll(GeneratedSpecTables.FAILURE_REASONS);
        for (SessionState.Phase p : SessionState.Phase.values()) {
            SessionState s = new SessionState(p, SessionState.Hint.NONE, null, null, 0, 1, 0, 0, 0);
            assertThat(GeneratedSpecTables.TERMINAL_PHASES.contains(p.name())).as(p.name()).isEqualTo(s.isTerminal());
        }
        // The walk of this test class covers both configurations; run it here too so the check does not depend on order.
        if (observedPhaseTransitions.isEmpty()) {
            for (String cfg : CONFIGS) {
                Coverage cov = new Coverage();
                SpecModel model = new SpecModel(Constants.parse(formalDir().resolve(cfg)));
                for (int t = 0; t < 3000; t++) {
                    runTrace(model, new Random(SEED + t), cov, t, null);
                }
                observedPhaseTransitions.addAll(cov.phaseTransitions);
            }
        }
        assertThat(GeneratedSpecTables.PHASE_TRANSITIONS).containsAll(observedPhaseTransitions);
        assertThat(observedPhaseTransitions).hasSize(GeneratedSpecTables.TRANSITION_COUNT);
    }

    // ---- one trace ------------------------------------------------------------------------------------------------

    private void runTrace(SpecModel model, Random rnd, Coverage cov, int traceNo, TlcStateGraph tlc) {
        List<State> init = model.init();
        State spec = init.get(rnd.nextInt(init.size()));
        JavaSide java = new JavaSide(model.c, spec.activeMode(), rnd);
        List<String> log = new ArrayList<>();
        log.add("init " + spec);
        cov.visit(spec);
        try {
            String m0 = java.mismatch(spec);
            if (m0 != null) fail("initial state differs: " + m0);
            for (int n = 0; n < MAX_STEPS && !spec.terminal(); n++) {
                State from = spec;
                List<Step> options = new ArrayList<>();
                for (Step s : model.next(spec)) {
                    if (!s.internal() && java.realisable(s)) {
                        options.add(s);
                    } else if (!s.internal()) {
                        cov.unrealisable.merge(s.action() + label(s), 1, Integer::sum);
                    }
                }
                if (options.isEmpty()) {
                    fail("no realisable action from " + spec);
                }
                List<Step> path = new ArrayList<>();
                Step chosen = pick(options, rnd);
                path.add(chosen);
                if (chosen.action().equals("FaceAcquired")) {
                    // The orchestrator scores the frame that passes the quality gate: FaceAcquired and the first
                    // PassiveFrame are one feed() call.
                    List<Step> first = new ArrayList<>();
                    for (Step s : model.next(chosen.to())) {
                        if (s.action().equals("PassiveFrame") && java.realisable(s)) first.add(s);
                    }
                    path.add(pick(first, rnd));
                }
                java.realise(path);
                State mid = path.get(path.size() - 1).to();
                List<List<Step>> closures = closures(model, mid);
                List<Step> matched = null;
                List<String> mismatches = new ArrayList<>();
                for (List<Step> c : closures) {
                    State target = c.isEmpty() ? mid : c.get(c.size() - 1).to();
                    String m = java.mismatch(target);
                    if (m == null) {
                        matched = c;
                        break;
                    }
                    mismatches.add(m);
                }
                path.forEach(s -> log.add(s.action() + label(s)));
                if (matched == null) {
                    fail("Java orchestrator diverged from the spec after " + path.stream().map(s -> s.action() + label(s)).collect(Collectors.toList())
                            + "\n  from    " + from + "\n  options " + mismatches + "\n  java    " + java.describe() + "\n  trace   " + String.join("\n          ", log));
                }
                path.addAll(matched);
                matched.forEach(s -> log.add(s.action() + label(s)));
                for (Step s : path) {
                    cov.step(s, tlc);
                }
                spec = path.get(path.size() - 1).to();
                cov.visit(spec);
                java.afterStep(path, spec);
                if (rnd.nextDouble() < 0.35) {
                    String probe = java.stutter(spec, model);
                    if (probe != null) {
                        cov.stutters.merge(probe, 1, Integer::sum);
                        String m = java.mismatch(spec);
                        if (m != null) {
                            fail("stuttering input '" + probe + "' changed the orchestrator state: " + m + "\n  trace " + String.join("\n        ", log));
                        }
                    }
                }
            }
            cov.traces++;
            cov.terminal.merge(spec.phase() + (spec.terminal() ? "" : "(cut)"), 1, Integer::sum);
            cov.lengths += log.size() - 1;
            java.checkResultInvariants(spec);
        } catch (AssertionError e) {
            throw new AssertionError("trace #" + traceNo + ": " + e.getMessage(), e);
        }
    }

    /** All sequences of internal steps the orchestrator may take after {@code s} (empty sequence when none is enabled). */
    private static List<List<Step>> closures(SpecModel model, State s) {
        List<Step> internal = model.next(s).stream().filter(Step::internal).collect(Collectors.toList());
        List<List<Step>> out = new ArrayList<>();
        if (internal.isEmpty()) {
            out.add(new ArrayList<>());
            return out;
        }
        for (Step st : internal) {
            for (List<Step> tail : closures(model, st.to())) {
                List<Step> c = new ArrayList<>();
                c.add(st);
                c.addAll(tail);
                out.add(c);
            }
        }
        return out;
    }

    /** Weighted choice: cancels are rare so walks go deep, vetoes and drift are occasional. */
    private static Step pick(List<Step> options, Random rnd) {
        double[] w = new double[options.size()];
        double total = 0;
        for (int i = 0; i < w.length; i++) {
            Step s = options.get(i);
            double x = 1;
            switch (s.action()) {
                case "Cancel": x = 0.1; break;
                case "PassiveFrame":
                    x = ((Boolean) s.arg(1) ? 0.08 : 1) * ((Boolean) s.arg(2) ? 0.3 : 1);
                    break;
                case "ChallengeFrame":
                    x = ((Boolean) s.arg(2) ? 0.08 : 1) * ((Boolean) s.arg(3) ? 0.3 : 1) * ("FAILED".equals(s.arg(0)) ? 0.3 : 1);
                    break;
                case "DeviceFailed": case "EngineFault": case "AcquireFailed": x = 0.15; break;
                case "ChallengeTimeout": x = 0.3; break;
                case "SessionTimeout": x = 0.15; break;
                default: x = s.args().isEmpty() ? 6 : 1;
            }
            w[i] = x;
            total += x;
        }
        double r = rnd.nextDouble() * total;
        for (int i = 0; i < w.length; i++) {
            r -= w[i];
            if (r <= 0) return options.get(i);
        }
        return options.get(options.size() - 1);
    }

    static String label(Step s) {
        return s.args().isEmpty() ? "" : s.args().toString();
    }

    // ---- the Java side ------------------------------------------------------------------------------------------

    /** Scripted detector: reports exactly the outcome the walk chose for the frame. */
    static final class ScriptedDetector implements ActionDetector {
        final ChallengeType type;
        final long issuedAt;
        ActionStatus status = ActionStatus.WAITING;
        ActionStatus next = ActionStatus.WAITING;
        long onset = -1;
        long completed = -1;

        ScriptedDetector(ChallengeType type, long issuedAt) {
            this.type = type;
            this.issuedAt = issuedAt;
        }

        @Override public ChallengeType type() { return type; }
        @Override public long issuedAtNanos() { return issuedAt; }
        @Override public ActionStatus update(FaceObservation face, long nowNanos) {
            if (status.isTerminal()) return status;
            status = next;
            if (status == ActionStatus.COMPLETED) completed = nowNanos;
            return status;
        }
        @Override public ActionStatus status() { return status; }
        @Override public OptionalLong onsetNanos() { return onset < 0 ? OptionalLong.empty() : OptionalLong.of(onset); }
        @Override public OptionalLong completedNanos() { return completed < 0 ? OptionalLong.empty() : OptionalLong.of(completed); }
        @Override public double progress() { return status == ActionStatus.COMPLETED ? 1 : 0; }
    }

    /** The orchestrator under test plus the hidden environment state the walk needs to choose concrete inputs. */
    static final class JavaSide {
        final Constants c;
        final Random rnd;
        final FakeTime time = new FakeTime();
        final FakeFrameAnalyzer analyzer = new FakeFrameAnalyzer();
        final InMemoryAuditSink audit = new InMemoryAuditSink(100_000);
        final LivenessOrchestrator o;
        final LivenessPolicy policy;
        ScriptedDetector detector;
        long attemptStart;
        int outcomesBefore;
        /** Identity continuity: anchor established, and the current streak of drifting frames (veto at 3). */
        boolean anchored;
        int driftStreak;
        /** The S6 timing signal is pinned in the fusion once a challenge was answered in time (session-wide). */
        boolean timingPinned;
        /** Last quality-gate failure fed in FACE_ACQUIRING during this attempt. */
        FailureReason lastGate;

        JavaSide(Constants c, String mode, Random rnd) {
            this.c = c;
            this.rnd = rnd;
            policy = LivenessPolicy.builder(Workflow.RESIDENT).policyVersion("conformance-" + c.name())
                    .activeMode(LivenessPolicy.ActiveMode.valueOf(mode))
                    .passiveThreshold(THRESHOLD).padHardThreshold(0.95)
                    .passiveMinFrames(c.passiveMinFrames()).passiveWindowMillis(PASSIVE_WINDOW_MS)
                    .minChallenges(c.minChallenges()).maxChallenges(c.maxChallenges())
                    .challengePool(EnumSet.of(ChallengeType.SMILE, ChallengeType.OPEN_MOUTH, ChallengeType.RAISE_EYEBROWS,
                            ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT))
                    .challengeTimeoutMillis(CHALLENGE_TIMEOUT_MS).sessionTimeoutMillis(SESSION_TIMEOUT_MS)
                    .acquisitionTimeoutMillis(SESSION_TIMEOUT_MS)   // the spec has one attempt timer
                    .minActionLatencyMillis(MIN_LATENCY_MS).maxActionLatencyMillis(3000)
                    .maxRetries(c.maxRetries()).requireIdentityContinuity(true).identityDriftThreshold(0.45)
                    .build();
            analyzer.fallback(() -> {
                throw new IllegalStateException("orchestrator analysed a frame the trace did not feed");
            });
            o = LivenessOrchestrator.builder(analyzer, policy, Scenarios.context(Workflow.RESIDENT, false))
                    .timeSource(time).random(new Random(rnd.nextLong())).auditSink(audit)
                    .fusionAlpha(1.0).anchorFrames(1)
                    .actionDetectorFactory((type, thresholds, issuedAt) -> detector = new ScriptedDetector(type, issuedAt))
                    .build();
        }

        // -- realisability: which spec steps can be produced by concrete inputs in the current hidden state

        boolean realisable(Step s) {
            switch (s.action()) {
                case "PassiveFrame":
                    return identityOk((Boolean) s.arg(2));
                case "ChallengeFrame":
                    return identityOk((Boolean) s.arg(3));
                default:
                    return true;
            }
        }

        private boolean identityOk(boolean drift) {
            // The orchestrator vetoes on the third consecutive drifting frame after the anchor exists.
            return !drift || (anchored && driftStreak == 2);
        }

        // -- concrete inputs

        void realise(List<Step> path) {
            Step s = path.get(0);
            State from = s.from();
            switch (s.action()) {
                case "Start":
                    o.start();
                    attemptStart = time.nanoTime();
                    break;
                case "Retry":
                    outcomesBefore = o.result().challenges().size();
                    assertThat(o.retry()).as("retry() refused although the spec enables Retry").isTrue();
                    attemptStart = time.nanoTime();
                    driftStreak = 0;
                    lastGate = null;
                    break;
                case "Cancel":
                    o.cancel();
                    break;
                case "DeviceConnected":
                    o.deviceReady();
                    break;
                case "DeviceFailed": {
                    FailureReason r = FailureReason.valueOf((String) s.arg(0));
                    if (from.phase().equals("DEVICE_CONNECTING") && r == FailureReason.DEVICE_CONNECTION_FAILURE && rnd.nextBoolean()) {
                        jumpTo(attemptStart + ms(SESSION_TIMEOUT_MS));
                        o.tick();
                    } else {
                        o.deviceFault(r, "conformance");
                    }
                    break;
                }
                case "FaceAcquired":
                    feedScored(path.get(1), false);
                    break;
                case "PassiveFrame":
                    feedScored(s, false);
                    break;
                case "ChallengeFrame":
                    feedScored(s, true);
                    break;
                case "AcquireFailed": {
                    FailureReason r = FailureReason.valueOf((String) s.arg(0));
                    if (!(r == FailureReason.FACE_NOT_DETECTED && lastGate == null && rnd.nextBoolean())) {
                        feedGated(r);
                    }
                    jumpTo(attemptStart + ms(SESSION_TIMEOUT_MS));
                    if (rnd.nextBoolean()) o.tick(); else o.feed(Frames.frame(time.nanoTime()));
                    break;
                }
                case "ChallengeTimeout":
                    jumpTo(o.currentChallenge().orElseThrow().issuedAtNanos() + ms(CHALLENGE_TIMEOUT_MS));
                    if (rnd.nextBoolean()) o.tick(); else o.feed(Frames.frame(time.nanoTime()));
                    break;
                case "SessionTimeout":
                    jumpTo(attemptStart + ms(SESSION_TIMEOUT_MS));
                    if (rnd.nextBoolean()) o.tick(); else o.feed(Frames.frame(time.nanoTime()));
                    break;
                case "EngineFault":
                    if (s.arg(0).equals("ENGINE_ERROR")) analyzer.failNext(new IllegalStateException("scripted engine fault"));
                    else analyzer.returnNullNext();
                    time.advance(1);
                    o.feed(Frames.frame(time.nanoTime()));
                    break;
                default:
                    throw new IllegalArgumentException(s.action());
            }
            assertThat(analyzer.remaining()).as("unconsumed scripted analyses").isZero();
        }

        /** One analysed frame carrying score s, PAD veto v and (for challenge frames) detector outcome o. */
        private void feedScored(Step s, boolean challenge) {
            int score = (Integer) s.arg(challenge ? 1 : 0);
            boolean veto = (Boolean) s.arg(challenge ? 2 : 1);
            boolean drift = (Boolean) s.arg(challenge ? 3 : 2);
            String outcome = challenge ? (String) s.arg(0) : null;
            float[] embedding;
            if (!anchored) {
                embedding = ANCHOR;
                anchored = true;
            } else if (drift) {
                embedding = OTHER_PERSON;
            } else if (driftStreak < 2 && rnd.nextDouble() < 0.6) {
                embedding = OTHER_PERSON;   // a drifting frame that does not (yet) veto
                driftStreak++;
            } else {
                embedding = ANCHOR;
                driftStreak = 0;
            }
            long advanceTo = time.nanoTime() + ms(1);
            if (challenge) {
                ScriptedDetector d = detector;
                assertThat(d).isNotNull();
                switch (outcome) {
                    case "NONE": d.next = ActionStatus.WAITING; break;
                    case "FAILED": d.next = ActionStatus.FAILED; break;
                    default: {
                        boolean tooFast = s.from().ticks() < c.minLatencyTicks();
                        d.next = ActionStatus.COMPLETED;
                        d.onset = d.issuedAt + ms(tooFast ? TOO_FAST_ONSET_MS : IN_WINDOW_ONSET_MS);
                        advanceTo = Math.max(advanceTo, d.onset + ms(tooFast ? 1 : 100));
                    }
                }
            }
            List<SignalScore> signals = new ArrayList<>();
            signals.add(SignalScore.of(Signals.CNN_TEXTURE, attackProbabilityFor(score), 1.0));
            signals.add(SignalScore.of(Signals.QUALITY, 0.0, 1.0));
            if (veto) {
                signals.add(SignalScore.veto(Signals.MOIRE, Signals.ATTACK_SCREEN));
            }
            time.advance((advanceTo - time.nanoTime()) / 1_000_000L);
            analyzer.enqueue(Scenarios.analysis(Scenarios.goodQuality(), Scenarios.neutralFace().build(), signals, embedding));
            o.feed(Frames.frame(time.nanoTime()));
            if (challenge && "PASSED".equals(outcome) && !veto && !drift && s.from().ticks() >= c.minLatencyTicks()) {
                timingPinned = true;
            }
            if (drift) {
                driftStreak = 0;
            }
        }

        /** Attack probability of the frame signal that makes the fused liveness equal the concrete value of score s. */
        double attackProbabilityFor(int score) {
            double a = 1 - liveness(score);
            if (!timingPinned) {
                return a;
            }
            // fused logit = (w*c*logit(p) + w_t*c_t*logit(0.05)) / (w*c + w_t*c_t) with w = w_t = 1, c = 1, c_t = 0.9
            double l = logit(a) * 1.9 - 0.9 * logit(0.05);
            return 1 / (1 + Math.exp(-l));
        }

        double liveness(int score) {
            return THRESHOLD + (score - c.threshold() + 0.5) * SCORE_STEP;
        }

        private void feedGated(FailureReason r) {
            FrameAnalysis a;
            switch (r) {
                case FACE_NOT_DETECTED: a = Scenarios.noFace(); break;
                case MULTIPLE_FACES: a = Scenarios.multipleFaces(); break;
                default: a = Scenarios.poorQuality(new FaceQuality(1, 0.1, 0.1, 0.1, 1.0, 0.1));
            }
            time.advance(1);
            analyzer.enqueue(a);
            o.feed(Frames.frame(time.nanoTime()));
            lastGate = r;
        }

        void afterStep(List<Step> path, State spec) {
        }

        // -- stuttering inputs: none of these may change the abstract state

        String stutter(State spec, SpecModel model) {
            String p = spec.phase();
            List<String> probes = new ArrayList<>();
            boolean live = p.equals("FACE_ACQUIRING") || SpecModel.EVALUATING.contains(p);
            boolean retryEnabled = model.next(spec).stream().anyMatch(s -> s.action().equals("Retry"));
            if (!retryEnabled) probes.add("retry-refused");
            if (!p.equals("IDLE")) probes.add("start-again");
            if (!spec.terminal()) probes.add("tick");
            if (live) {
                probes.add("gated-frame");
                probes.add("device-blip");
            }
            if (p.equals("FAILED") || spec.terminal()) probes.add("frame-ignored");
            if (p.equals("IDLE") || p.equals("FAILED") || spec.terminal()) probes.add("fault-ignored");
            if (spec.terminal()) probes.add("cancel-ignored");
            String probe = probes.get(rnd.nextInt(probes.size()));
            switch (probe) {
                case "retry-refused": assertThat(o.retry()).as("retry() accepted although the spec disables Retry in " + spec).isFalse(); break;
                case "start-again": o.start(); break;
                case "tick": time.advance(1); o.tick(); break;
                case "gated-frame": {
                    FailureReason r = List.of(FailureReason.FACE_NOT_DETECTED, FailureReason.MULTIPLE_FACES, FailureReason.POOR_FACE_QUALITY)
                            .get(rnd.nextInt(3));
                    feedGated(r);
                    if (!p.equals("FACE_ACQUIRING")) lastGate = null;
                    break;
                }
                case "device-blip": o.deviceReconnecting("conformance blip"); o.deviceReady(); break;
                case "frame-ignored": time.advance(1); o.feed(Frames.frame(time.nanoTime())); break;
                case "fault-ignored": o.deviceFault(FailureReason.DEVICE_UNAVAILABLE, "conformance"); break;
                case "cancel-ignored": o.cancel(); break;
                default: throw new IllegalStateException(probe);
            }
            assertThat(analyzer.remaining()).as("unconsumed scripted analyses").isZero();
            return probe;
        }

        // -- abstraction function: the observable Java state must equal the spec state on these variables

        String mismatch(State e) {
            SessionState st = o.state();
            List<String> diffs = new ArrayList<>();
            if (!st.phase().name().equals(e.phase())) diffs.add("phase " + st.phase() + "≠" + e.phase());
            String failure = st.failure().map(Enum::name).orElse("NONE");
            if (!failure.equals(e.failure())) diffs.add("failure " + failure + "≠" + e.failure());
            if (st.attempt() != e.attempt()) diffs.add("attempt " + st.attempt() + "≠" + e.attempt());
            if (st.maxAttempts() != c.maxRetries()) diffs.add("maxAttempts " + st.maxAttempts());
            boolean scoreMatters = e.phase().equals("PASSIVE_EVALUATING") || e.phase().equals("ACTIVE_CHALLENGE") || e.phase().equals("PASSED");
            if (scoreMatters && Math.abs(st.passiveScore() - liveness(e.fusedScore())) > 1e-9) {
                diffs.add("fusedScore " + st.passiveScore() + "≠" + liveness(e.fusedScore()) + " (s=" + e.fusedScore() + ")");
            }
            if (e.phase().equals("PASSIVE_EVALUATING") && o.passiveFramesForConformance() != e.frames()) {
                diffs.add("frames " + o.passiveFramesForConformance() + "≠" + e.frames());
            }
            if (e.phase().equals("ACTIVE_CHALLENGE")) {
                int len = o.challengeSequence().size();
                int idx = st.challenge().map(ch -> ch.index() + 1).orElse(0);
                if (len != e.seqLen()) diffs.add("seqLen " + len + "≠" + e.seqLen());
                if (idx != e.challengeIdx()) diffs.add("challengeIdx " + idx + "≠" + e.challengeIdx());
                if (st.challenge().map(ch -> ch.total() != e.seqLen()).orElse(true)) diffs.add("challenge.total");
            }
            if (e.phase().equals("PASSED")) {
                LivenessResult r = o.result();
                long passed = r.challenges().subList(outcomesBefore, r.challenges().size()).stream()
                        .filter(x -> x.result() == ChallengeOutcome.Result.PASSED).count();
                if (passed != e.challengesPassed()) diffs.add("challengesPassed " + passed + "≠" + e.challengesPassed());
                if (r.decision() != LivenessResult.Decision.LIVE) diffs.add("decision " + r.decision());
            }
            if (e.phase().equals("FAILED") || e.phase().equals("LOCKED")) {
                LivenessResult.Decision want = Set.of("PAD_DETECTED", "IDENTITY_DRIFT").contains(e.failure()) ? LivenessResult.Decision.ATTACK
                        : Set.of("DEVICE_UNAVAILABLE", "DEVICE_CONNECTION_FAILURE", "CAMERA_UNAVAILABLE", "INVALID_FRAME_DATA", "ENGINE_ERROR")
                        .contains(e.failure()) ? LivenessResult.Decision.ERROR : LivenessResult.Decision.NOT_LIVE;
                if (o.result().decision() != want) diffs.add("decision " + o.result().decision() + "≠" + want);
            }
            return diffs.isEmpty() ? null : String.join(", ", diffs) + " (expected " + e + ")";
        }

        /** PassedImpliesPolicy evaluated on the Java result, independently of the abstraction function. */
        void checkResultInvariants(State spec) {
            if (o.state().phase() == SessionState.Phase.PASSED) {
                LivenessResult r = o.result();
                assertThat(r.fusedLivenessScore()).isGreaterThanOrEqualTo(THRESHOLD);
                assertThat(r.vetoSignal()).isEmpty();
                long passed = r.challenges().subList(outcomesBefore, r.challenges().size()).stream()
                        .filter(x -> x.result() == ChallengeOutcome.Result.PASSED).count();
                if (policy.activeMode() == LivenessPolicy.ActiveMode.ALWAYS) {
                    assertThat(passed).isGreaterThanOrEqualTo(policy.minChallenges());
                }
            }
        }

        String describe() {
            SessionState st = o.state();
            return st.phase() + " failure=" + st.failure().orElse(null) + " attempt=" + st.attempt() + " score=" + st.passiveScore()
                    + " challenge=" + st.challenge().orElse(null) + " seq=" + o.challengeSequence() + " frames=" + o.passiveFramesForConformance();
        }

        private void jumpTo(long nanos) {
            long now = time.nanoTime();
            if (nanos > now) time.advance((nanos - now + 999_999) / 1_000_000L);
        }

        private static long ms(long millis) {
            return millis * 1_000_000L;
        }

        private static double logit(double p) {
            return Math.log(p / (1 - p));
        }
    }

    // ---- coverage ----------------------------------------------------------------------------------------------

    static final class Coverage {
        int traces;
        long lengths;
        final Map<String, Integer> actions = new TreeMap<>();
        final Map<String, Integer> terminal = new TreeMap<>();
        final Map<String, Integer> stutters = new TreeMap<>();
        final Map<String, Integer> unrealisable = new TreeMap<>();
        final Map<String, Integer> failures = new TreeMap<>();
        final Set<String> states = new HashSet<>();
        final Set<String> edges = new HashSet<>();
        final Set<String> phaseTransitions = new TreeSet<>();

        void visit(State s) {
            states.add(s.key());
        }

        void step(Step s, TlcStateGraph tlc) {
            actions.merge(s.action(), 1, Integer::sum);
            String edge = s.from().key() + " -> " + s.to().key();
            edges.add(edge);
            phaseTransitions.add(s.from().phase() + " -" + s.action() + "-> " + s.to().phase());
            if (s.to().phase().equals("FAILED")) {
                failures.merge(s.to().failure(), 1, Integer::sum);
            }
            if (tlc != null && !tlc.edges.contains(edge)) {
                fail("walked edge is not in the TLC state graph: " + s.action() + label(s) + " " + edge);
            }
        }

        String report(String cfg, SpecModel model, TlcStateGraph tlc) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("configuration", cfg + " " + model.c);
            m.put("traces", traces + " (seed " + SEED + "), mean length " + String.format(java.util.Locale.ROOT, "%.1f", (double) lengths / Math.max(1, traces)) + " spec steps");
            m.put("final phases", terminal);
            m.put("spec steps replayed", actions.values().stream().mapToInt(Integer::intValue).sum() + " " + actions);
            m.put("failure reasons reached", failures);
            m.put("stuttering inputs checked", stutters.values().stream().mapToInt(Integer::intValue).sum() + " " + stutters);
            m.put("distinct spec states visited", states.size() + (tlc == null ? "" : " of " + tlc.states.size() + " reachable in TLC"));
            m.put("distinct spec edges walked", edges.size() + (tlc == null ? "" : " of " + tlc.edges.size() + " in TLC"));
            m.put("phase transitions observed", phaseTransitions.size() + " of " + GeneratedSpecTables.TRANSITION_COUNT + " generated");
            m.put("spec steps skipped as not producible by the Java environment", unrealisable);
            m.put("TLC cross-check", tlc == null ? "skipped (no dump)" : "every walked edge is a TLC edge");
            StringBuilder b = new StringBuilder("[conformance] " + cfg + "\n");
            m.forEach((k, v) -> b.append("  ").append(k).append(": ").append(v).append('\n'));
            return b.toString();
        }
    }

    // ---- locating the spec ---------------------------------------------------------------------------------------

    static Path formalDir() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 4 && p != null; i++, p = p.getParent()) {
            if (Files.exists(p.resolve("formal").resolve("LivenessOrchestrator.tla"))) {
                return p.resolve("formal");
            }
        }
        throw new IllegalStateException("formal/LivenessOrchestrator.tla not found above " + System.getProperty("user.dir"));
    }

    static Path dumpFor(String cfg) {
        String dir = System.getenv("VIVID_TLC_DUMP_DIR");
        if (dir == null) dir = System.getProperty("vivid.tlc.dumpDir");
        if (dir == null) return null;
        Path p = Path.of(dir).resolve(cfg.replace(".cfg", ".dot"));
        return Files.exists(p) ? p : null;
    }
}
