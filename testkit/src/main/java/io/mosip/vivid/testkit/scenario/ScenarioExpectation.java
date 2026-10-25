package io.mosip.vivid.testkit.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosip.vivid.api.FailureReason;
import io.mosip.vivid.api.LivenessResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What a scenario should produce, loaded from {@code <scenario>.expect.json}:
 * <pre>{@code
 * {
 *   "scenario": "print-attack",
 *   "expectedDecision": "ATTACK" | "NOT_LIVE" | "LIVE" | "ERROR" | "CANCELLED" | ["ATTACK","NOT_LIVE"],
 *   "allowedFailureReasons": ["PAD_DETECTED", "ACTIVE_CHALLENGE_FAILED"],   // if present, the failure must be one of these
 *   "expectedAttackClass": "PRINT",                                          // optional
 *   "maxDurationMs": 20000,                                                   // scenario time budget
 *   "notes": "free text"
 * }
 * }</pre>
 */
public final class ScenarioExpectation {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Outcome of checking a run against an expectation. */
    public record Verdict(boolean passed, List<String> problems) {
        public static Verdict ok() { return new Verdict(true, List.of()); }
    }

    private final String scenario;
    private final Set<LivenessResult.Decision> expectedDecisions;
    private final Set<FailureReason> allowedFailureReasons;   // empty = any
    private final String expectedAttackClass;                 // nullable
    private final long maxDurationMs;                          // -1 = unlimited
    private final String notes;

    private ScenarioExpectation(String scenario, Set<LivenessResult.Decision> decisions, Set<FailureReason> reasons, String attackClass, long maxDurationMs, String notes) {
        this.scenario = scenario; this.expectedDecisions = Collections.unmodifiableSet(decisions);
        this.allowedFailureReasons = Collections.unmodifiableSet(reasons); this.expectedAttackClass = attackClass;
        this.maxDurationMs = maxDurationMs; this.notes = notes == null ? "" : notes;
    }

    public static ScenarioExpectation of(String scenario, LivenessResult.Decision decision) {
        return new ScenarioExpectation(scenario, EnumSet.of(decision), EnumSet.noneOf(FailureReason.class), null, -1, "");
    }

    public static ScenarioExpectation load(Path file) {
        try (InputStream in = Files.newInputStream(file)) { return parse(MAPPER.readTree(in)); }
        catch (IOException e) { throw new UncheckedIOException("cannot read expectation " + file, e); }
    }

    /** Load {@code /expectations/<scenario>.expect.json} from the classpath, if present. */
    public static Optional<ScenarioExpectation> bundled(String scenarioName) {
        try (InputStream in = ScenarioExpectation.class.getResourceAsStream("/expectations/" + scenarioName + ".expect.json")) {
            if (in == null) return Optional.empty();
            return Optional.of(parse(MAPPER.readTree(in)));
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public static ScenarioExpectation parse(JsonNode n) {
        if (n == null || !n.isObject()) throw new IllegalArgumentException("expectation must be a JSON object");
        String scenario = n.path("scenario").asText("");
        Set<LivenessResult.Decision> decisions = EnumSet.noneOf(LivenessResult.Decision.class);
        JsonNode d = n.get("expectedDecision");
        if (d == null) throw new IllegalArgumentException("expectation needs 'expectedDecision'");
        if (d.isArray()) d.forEach(x -> decisions.add(enumValue(LivenessResult.Decision.class, x.asText())));
        else decisions.add(enumValue(LivenessResult.Decision.class, d.asText()));
        Set<FailureReason> reasons = EnumSet.noneOf(FailureReason.class);
        for (JsonNode r : n.path("allowedFailureReasons")) reasons.add(enumValue(FailureReason.class, r.asText()));
        String ac = n.hasNonNull("expectedAttackClass") ? n.get("expectedAttackClass").asText() : null;
        return new ScenarioExpectation(scenario, decisions, reasons, ac, n.path("maxDurationMs").asLong(-1), n.path("notes").asText(""));
    }

    private static <E extends Enum<E>> E enumValue(Class<E> t, String s) {
        try { return Enum.valueOf(t, s.trim().toUpperCase(Locale.ROOT).replace('-', '_')); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("'" + s + "' is not one of " + List.of(t.getEnumConstants())); }
    }

    public String scenario() { return scenario; }
    public Set<LivenessResult.Decision> expectedDecisions() { return expectedDecisions; }
    public Set<FailureReason> allowedFailureReasons() { return allowedFailureReasons; }
    public Optional<String> expectedAttackClass() { return Optional.ofNullable(expectedAttackClass); }
    public long maxDurationMs() { return maxDurationMs; }
    public String notes() { return notes; }

    /** Check a run. */
    public Verdict evaluate(ScenarioRun run) {
        List<String> problems = new ArrayList<>();
        run.error().ifPresent(e -> problems.add("session threw " + e));
        Optional<LivenessResult> r = run.result();
        if (r.isEmpty()) {
            problems.add("no LivenessResult; final state " + run.finalState() + (run.timedOut() ? " (timed out)" : ""));
        } else {
            LivenessResult res = r.get();
            if (!expectedDecisions.contains(res.decision())) problems.add("decision " + res.decision() + ", expected " + expectedDecisions);
            if (!allowedFailureReasons.isEmpty()) {
                Optional<FailureReason> f = res.failure();
                if (f.isEmpty()) { if (res.decision() != LivenessResult.Decision.LIVE) problems.add("no failure reason, expected one of " + allowedFailureReasons); }
                else if (!allowedFailureReasons.contains(f.get())) problems.add("failure reason " + f.get() + " not in " + allowedFailureReasons);
            }
            if (expectedAttackClass != null && !expectedAttackClass.equalsIgnoreCase(res.attackClass().orElse(""))) {
                problems.add("attack class " + res.attackClass().orElse("none") + ", expected " + expectedAttackClass);
            }
        }
        if (maxDurationMs >= 0 && run.scenarioMillis() > maxDurationMs) problems.add("took " + run.scenarioMillis() + " ms of scenario time, limit " + maxDurationMs);
        return problems.isEmpty() ? Verdict.ok() : new Verdict(false, problems);
    }
}
