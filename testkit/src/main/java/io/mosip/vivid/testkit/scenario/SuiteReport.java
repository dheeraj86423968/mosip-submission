package io.mosip.vivid.testkit.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosip.vivid.api.LivenessResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Result of {@link ScenarioSuite#run}: per-case verdicts plus JUnit-style summary, JSON and HTML renderings. */
public final class SuiteReport {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One case's run and verdict. */
    public record CaseResult(ScenarioSuite.Case scenarioCase, ScenarioRun run, ScenarioExpectation.Verdict verdict) {
        public String name() { return scenarioCase.name(); }
        public boolean passed() { return verdict.passed(); }
    }

    private final String suiteName;
    private final List<CaseResult> results;
    private final long wallMillis;

    SuiteReport(String suiteName, List<CaseResult> results, long wallMillis) {
        this.suiteName = suiteName; this.results = Collections.unmodifiableList(List.copyOf(results)); this.wallMillis = wallMillis;
    }

    public String suiteName() { return suiteName; }
    public List<CaseResult> results() { return results; }
    public long wallMillis() { return wallMillis; }
    public int total() { return results.size(); }
    public long failures() { return results.stream().filter(r -> !r.passed()).count(); }
    public long errors() { return results.stream().filter(r -> r.run().error().isPresent()).count(); }
    public boolean allPassed() { return failures() == 0; }

    /** JUnit-style plain-text summary. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "Scenario suite '%s': run %d, failures %d, errors %d, time %.1fs%n", suiteName, total(), failures(), errors(), wallMillis / 1000.0));
        for (CaseResult r : results) {
            sb.append(String.format(Locale.ROOT, "  %-6s %-28s decision=%-10s label=%-13s frames=%-4d %dms%s%n",
                    r.passed() ? "PASS" : "FAIL", r.name(), r.run().decisionText(), r.scenarioCase().scenario().primaryLabel(),
                    r.run().framesFed(), r.run().scenarioMillis(), r.passed() ? "" : "  <- " + String.join("; ", r.verdict().problems())));
        }
        return sb.toString();
    }

    public ObjectNode toJsonNode() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("suite", suiteName).put("total", total()).put("failures", failures()).put("errors", errors()).put("wallMillis", wallMillis);
        ArrayNode cases = root.putArray("cases");
        for (CaseResult r : results) {
            ObjectNode c = cases.addObject();
            c.put("scenario", r.name()).put("label", r.scenarioCase().scenario().primaryLabel().name()).put("passed", r.passed());
            c.put("decision", r.run().decisionText()).put("finalPhase", r.run().finalState().phase().name());
            c.put("framesFed", r.run().framesFed()).put("scenarioMillis", r.run().scenarioMillis()).put("wallMillis", r.run().wallMillis()).put("timedOut", r.run().timedOut());
            r.run().result().ifPresent(res -> {
                ObjectNode o = c.putObject("result");
                o.put("decision", res.decision().name()).put("fusedLivenessScore", res.fusedLivenessScore()).put("attemptsUsed", res.attemptsUsed())
                 .put("framesAnalyzed", res.framesAnalyzed()).put("totalMillis", res.totalMillis());
                res.failure().ifPresent(f -> o.put("failure", f.name()));
                res.attackClass().ifPresent(a -> o.put("attackClass", a));
                res.vetoSignal().ifPresent(v -> o.put("vetoSignal", v));
                ObjectNode sig = o.putObject("signalScores");
                res.signalScores().forEach(sig::put);
                ArrayNode ch = o.putArray("challenges");
                res.challenges().forEach(x -> ch.addObject().put("type", x.challenge().type().name()).put("result", x.result().name()).put("latencyMillis", x.latencyMillis()).put("durationMillis", x.durationMillis()));
            });
            r.scenarioCase().expectation().ifPresent(e -> {
                ObjectNode x = c.putObject("expectation");
                ArrayNode d = x.putArray("expectedDecision"); e.expectedDecisions().forEach(v -> d.add(v.name()));
                ArrayNode f = x.putArray("allowedFailureReasons"); e.allowedFailureReasons().forEach(v -> f.add(v.name()));
                e.expectedAttackClass().ifPresent(a -> x.put("expectedAttackClass", a));
                x.put("maxDurationMs", e.maxDurationMs());
            });
            ArrayNode problems = c.putArray("problems"); r.verdict().problems().forEach(problems::add);
            ArrayNode trace = c.putArray("trace");
            for (StateTransition t : r.run().trace()) {
                ObjectNode n = trace.addObject().put("scenarioMs", t.scenarioMs()).put("wallMs", t.wallMs()).put("from", t.previous().phase().name()).put("to", t.current().phase().name()).put("hint", t.current().hint().name());
                t.current().challenge().ifPresent(ch -> n.put("challenge", ch.type().name()));
                t.current().failure().ifPresent(f -> n.put("failure", f.name()));
            }
            ArrayNode health = c.putArray("health"); r.run().healthEvents().forEach(h -> health.add(h.toString()));
            r.run().error().ifPresent(e -> c.put("error", e.toString()));
        }
        return root;
    }

    public String toJson() {
        try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toJsonNode()); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }

    /** Self-contained HTML (inline CSS, no external assets). */
    public String toHtml() {
        StringBuilder h = new StringBuilder(16384);
        h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Scenario suite ").append(esc(suiteName)).append("</title><style>")
         .append(io.mosip.vivid.testkit.metrics.Iso30107Report.CSS)
         .append(".pass{color:#1f8a4c;font-weight:600}.fail{color:#c0392b;font-weight:600}.trace{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.8rem;white-space:pre-wrap;margin:0;color:var(--muted)}")
         .append("</style></head><body><main>");
        h.append("<h1>Scenario suite: ").append(esc(suiteName)).append("</h1>");
        h.append("<section class=\"tiles\">");
        tile(h, "Scenarios", String.valueOf(total()), "");
        tile(h, "Passed", String.valueOf(total() - failures()), "");
        tile(h, "Failed", String.valueOf(failures()), errors() + " with errors");
        tile(h, "Wall time", String.format(Locale.ROOT, "%.1fs", wallMillis / 1000.0), "");
        h.append("</section>");
        h.append("<table><thead><tr><th>Result</th><th>Scenario</th><th>Label</th><th>Decision</th><th>Failure</th><th>Frames</th><th>Scenario time</th><th>Problems</th></tr></thead><tbody>");
        for (CaseResult r : results) {
            LivenessResult res = r.run().result().orElse(null);
            h.append("<tr><td class=\"").append(r.passed() ? "pass\">PASS" : "fail\">FAIL").append("</td><td>").append(esc(r.name())).append("</td><td>")
             .append(r.scenarioCase().scenario().primaryLabel()).append("</td><td>").append(esc(r.run().decisionText())).append("</td><td>")
             .append(res == null ? "" : res.failure().map(Enum::name).orElse("")).append("</td><td>").append(r.run().framesFed()).append("</td><td>")
             .append(r.run().scenarioMillis()).append(" ms</td><td>").append(esc(String.join("; ", r.verdict().problems()))).append("</td></tr>");
        }
        h.append("</tbody></table>");
        for (CaseResult r : results) {
            h.append("<details><summary>").append(esc(r.name())).append(": state trace (").append(r.run().trace().size()).append(" transitions, ")
             .append(r.run().healthEvents().size()).append(" health events)</summary><pre class=\"trace\">");
            for (StateTransition t : r.run().trace()) h.append(esc(t.toString())).append('\n');
            if (!r.run().healthEvents().isEmpty()) h.append("health: ").append(esc(r.run().healthEvents().toString())).append('\n');
            h.append("</pre></details>");
        }
        h.append("<footer>Generated by mosip-vivid testkit. Scenario time is the mock device's virtual clock; wall time depends on pacing.</footer></main></body></html>");
        return h.toString();
    }

    /** Write {@code <baseName>.json} and {@code <baseName>.html}. */
    public void write(Path dir, String baseName) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(baseName + ".json"), toJson(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(baseName + ".html"), toHtml(), StandardCharsets.UTF_8);
    }

    private static void tile(StringBuilder h, String label, String value, String note) {
        h.append("<div class=\"tile\"><div class=\"label\">").append(label).append("</div><div class=\"value\">").append(value).append("</div><div class=\"note\">").append(note).append("</div></div>");
    }
    private static String esc(String s) { return io.mosip.vivid.testkit.metrics.Iso30107Report.esc(s); }
}
