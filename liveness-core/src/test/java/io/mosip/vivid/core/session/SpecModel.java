package io.mosip.vivid.core.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executable transliteration of {@code formal/LivenessOrchestrator.tla}: the state record, the constants of a TLC
 * configuration, and the {@code Next} relation with every action's parameters made explicit.
 *
 * <p>The transliteration is not trusted on its own: {@link SpecConformanceTest} checks, whenever a TLC state-graph
 * dump is available, that breadth-first search over this class reaches exactly the states and edges TLC reached for
 * the same configuration. It then drives the real {@link LivenessOrchestrator} along random walks of this relation.</p>
 */
final class SpecModel {

    static final Set<String> TERMINAL = Set.of("PASSED", "LOCKED", "CANCELLED");
    static final Set<String> EVALUATING = Set.of("PASSIVE_EVALUATING", "ACTIVE_CHALLENGE");
    static final List<String> MODES = List.of("NEVER", "ON_LOW_CONFIDENCE", "ALWAYS");
    static final List<String> DEVICE_REASONS = List.of("DEVICE_UNAVAILABLE", "DEVICE_CONNECTION_FAILURE", "CAMERA_UNAVAILABLE");
    static final List<String> ACQUIRE_REASONS = List.of("FACE_NOT_DETECTED", "MULTIPLE_FACES", "POOR_FACE_QUALITY");
    static final List<String> ENGINE_REASONS = List.of("INVALID_FRAME_DATA", "ENGINE_ERROR");
    static final List<String> OUTCOMES = List.of("NONE", "PASSED", "FAILED");

    /** CONSTANTS of one .cfg file. */
    record Constants(String name, int maxRetries, int minChallenges, int maxChallenges, int scoreMax, int threshold,
                     int passiveMinFrames, int minLatencyTicks, int timeoutTicks) {

        static Constants parse(Path cfg) throws IOException {
            String text = Files.readString(cfg);
            Map<String, Integer> v = new HashMap<>();
            Matcher m = Pattern.compile("^\\s*(\\w+)\\s*=\\s*(\\d+)\\s*$", Pattern.MULTILINE).matcher(text);
            while (m.find()) {
                v.put(m.group(1), Integer.parseInt(m.group(2)));
            }
            return new Constants(cfg.getFileName().toString(), v.get("MaxRetries"), v.get("MinChallenges"), v.get("MaxChallenges"),
                    v.get("ScoreMax"), v.get("Threshold"), v.get("PassiveMinFrames"), v.get("MinLatencyTicks"), v.get("TimeoutTicks"));
        }
    }

    /** One assignment of the spec's thirteen variables. */
    record State(String phase, String failure, int attempt, String activeMode, int fusedScore, boolean padVeto,
                 boolean identityDrift, boolean passiveBelow, int frames, int seqLen, int challengeIdx,
                 int challengesPassed, int ticks) {

        State phase(String p) { return new State(p, failure, attempt, activeMode, fusedScore, padVeto, identityDrift, passiveBelow, frames, seqLen, challengeIdx, challengesPassed, ticks); }
        State fail(String reason) { return new State("FAILED", reason, attempt, activeMode, fusedScore, padVeto, identityDrift, passiveBelow, frames, seqLen, challengeIdx, challengesPassed, ticks); }
        State score(int s) { return new State(phase, failure, attempt, activeMode, s, padVeto, identityDrift, passiveBelow, frames, seqLen, challengeIdx, challengesPassed, ticks); }
        State flags(boolean v, boolean d) { return new State(phase, failure, attempt, activeMode, fusedScore, v, d, passiveBelow, frames, seqLen, challengeIdx, challengesPassed, ticks); }
        State below(boolean b) { return new State(phase, failure, attempt, activeMode, fusedScore, padVeto, identityDrift, b, frames, seqLen, challengeIdx, challengesPassed, ticks); }
        State frames(int f) { return new State(phase, failure, attempt, activeMode, fusedScore, padVeto, identityDrift, passiveBelow, f, seqLen, challengeIdx, challengesPassed, ticks); }
        State challenge(int len, int idx, int passed, int t) { return new State(phase, failure, attempt, activeMode, fusedScore, padVeto, identityDrift, passiveBelow, frames, len, idx, passed, t); }
        State attempt(int a, String p) {
            // start()/retry(): new attempt with the per-attempt bookkeeping reset (ResetAttempt)
            return new State(p, "NONE", a, activeMode, 0, false, false, false, 0, 0, 0, 0, 0);
        }

        boolean terminal() { return TERMINAL.contains(phase); }

        /** TLC's rendering of the state, used to match dump nodes. */
        String key() {
            return phase + "|" + failure + "|" + attempt + "|" + activeMode + "|" + fusedScore + "|" + padVeto + "|" + identityDrift
                    + "|" + passiveBelow + "|" + frames + "|" + seqLen + "|" + challengeIdx + "|" + challengesPassed + "|" + ticks;
        }
    }

    /**
     * One labelled transition. {@code args} holds the existential witnesses in the order the action declares them
     * (reason; s, v, d; o, s, v, d; n).
     */
    record Step(String action, List<Object> args, State from, State to) {
        boolean internal() { return action.equals("PassiveDecide") || action.equals("Lock"); }
        Object arg(int i) { return args.get(i); }
    }

    final Constants c;

    SpecModel(Constants c) {
        this.c = c;
    }

    List<State> init() {
        List<State> out = new ArrayList<>();
        for (String m : MODES) {
            out.add(new State("IDLE", "NONE", 0, m, 0, false, false, false, 0, 0, 0, 0, 0));
        }
        return out;
    }

    /** Every non-stuttering successor of {@code s} (the {@code Done} self-loop is omitted). */
    List<Step> next(State s) {
        List<Step> out = new ArrayList<>();
        String p = s.phase();
        // ---- client
        if (p.equals("IDLE")) {
            out.add(new Step("Start", List.of(), s, s.attempt(1, "DEVICE_CONNECTING")));
        }
        if (p.equals("FAILED") && s.attempt() < c.maxRetries()) {
            String to = DEVICE_REASONS.contains(s.failure()) ? "DEVICE_CONNECTING" : "FACE_ACQUIRING";
            out.add(new Step("Retry", List.of(), s, s.attempt(s.attempt() + 1, to)));
        }
        if (!s.terminal()) {
            State t = s.phase("CANCELLED");
            out.add(new Step("Cancel", List.of(), s, new State(t.phase(), "CANCELLED", t.attempt(), t.activeMode(), t.fusedScore(),
                    t.padVeto(), t.identityDrift(), t.passiveBelow(), t.frames(), t.seqLen(), t.challengeIdx(), t.challengesPassed(), t.ticks())));
        }
        // ---- device
        if (p.equals("DEVICE_CONNECTING")) {
            out.add(new Step("DeviceConnected", List.of(), s, s.phase("FACE_ACQUIRING")));
        }
        if (p.equals("DEVICE_CONNECTING") || p.equals("FACE_ACQUIRING") || EVALUATING.contains(p)) {
            for (String r : DEVICE_REASONS) {
                out.add(new Step("DeviceFailed", List.of(r), s, s.fail(r)));
            }
        }
        // ---- acquisition
        if (p.equals("FACE_ACQUIRING")) {
            out.add(new Step("FaceAcquired", List.of(), s, s.phase("PASSIVE_EVALUATING").frames(0)));
            for (String r : ACQUIRE_REASONS) {
                out.add(new Step("AcquireFailed", List.of(r), s, s.fail(r)));
            }
        }
        // ---- passive window
        if (p.equals("PASSIVE_EVALUATING") && s.frames() < c.passiveMinFrames()) {
            for (int sc = 0; sc <= c.scoreMax(); sc++) {
                for (boolean v : new boolean[] {false, true}) {
                    for (boolean d : new boolean[] {false, true}) {
                        boolean veto = s.padVeto() || v;
                        boolean drift = s.identityDrift() || d;
                        State t = s.score(sc).flags(veto, drift).frames(s.frames() + 1);
                        t = drift ? t.fail("IDENTITY_DRIFT") : veto ? t.fail("PAD_DETECTED") : t;
                        out.add(new Step("PassiveFrame", List.of(sc, v, d), s, t));
                    }
                }
            }
        }
        if (p.equals("PASSIVE_EVALUATING") && s.frames() == c.passiveMinFrames() && !s.padVeto() && !s.identityDrift()) {
            if (s.fusedScore() >= c.threshold()) {
                if (s.activeMode().equals("ALWAYS")) {
                    addStartActive(out, s, s.below(false));
                } else {
                    out.add(new Step("PassiveDecide", List.of(), s, s.phase("PASSED")));
                }
            } else if (s.activeMode().equals("NEVER")) {
                out.add(new Step("PassiveDecide", List.of(), s, s.below(true).fail("LIVENESS_BELOW_THRESHOLD")));
            } else {
                addStartActive(out, s, s.below(true));
            }
        }
        // ---- active challenge
        if (p.equals("ACTIVE_CHALLENGE")) {
            for (String o : OUTCOMES) {
                if (o.equals("NONE") && !(s.ticks() + 1 < c.timeoutTicks())) {
                    continue;
                }
                for (int sc = 0; sc <= c.scoreMax(); sc++) {
                    for (boolean v : new boolean[] {false, true}) {
                        for (boolean d : new boolean[] {false, true}) {
                            out.add(new Step("ChallengeFrame", List.of(o, sc, v, d), s, challengeFrame(s, o, sc, v, d)));
                        }
                    }
                }
            }
            out.add(new Step("ChallengeTimeout", List.of(), s, s.fail("CHALLENGE_TIMEOUT")));
        }
        // ---- faults
        if (p.equals("FACE_ACQUIRING") || EVALUATING.contains(p)) {
            for (String r : ENGINE_REASONS) {
                out.add(new Step("EngineFault", List.of(r), s, s.fail(r)));
            }
        }
        if (EVALUATING.contains(p)) {
            out.add(new Step("SessionTimeout", List.of(), s, s.fail("SESSION_TIMEOUT")));
        }
        // ---- core
        if (p.equals("FAILED") && s.attempt() == c.maxRetries()) {
            out.add(new Step("Lock", List.of(), s, s.phase("LOCKED")));
        }
        return out;
    }

    private void addStartActive(List<Step> out, State from, State s) {
        for (int n = c.minChallenges(); n <= c.maxChallenges(); n++) {
            out.add(new Step("PassiveDecide", List.of(n), from, s.phase("ACTIVE_CHALLENGE").challenge(n, 1, 0, 0)));
        }
    }

    private State challengeFrame(State s, String o, int sc, boolean v, boolean d) {
        boolean tooFast = o.equals("PASSED") && s.ticks() < c.minLatencyTicks();
        boolean veto = s.padVeto() || v;
        boolean drift = s.identityDrift() || d;
        State t = s.score(sc).flags(veto, drift);
        if (drift) {
            return t.fail("IDENTITY_DRIFT");
        }
        if (veto || tooFast) {
            return t.flags(true, drift).fail("PAD_DETECTED");
        }
        if (o.equals("NONE")) {
            return t.challenge(s.seqLen(), s.challengeIdx(), s.challengesPassed(), s.ticks() + 1);
        }
        if (o.equals("FAILED")) {
            return t.fail("ACTIVE_CHALLENGE_FAILED");
        }
        int passed = s.challengesPassed() + 1;
        if (s.challengeIdx() < s.seqLen()) {
            return t.challenge(s.seqLen(), s.challengeIdx() + 1, passed, 0);
        }
        t = t.challenge(s.seqLen(), s.challengeIdx(), passed, s.ticks());
        return sc >= c.threshold() ? t.phase("PASSED") : t.fail("LIVENESS_BELOW_THRESHOLD");
    }
}
