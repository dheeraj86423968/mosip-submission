package io.mosip.vivid.testkit.scenario;

import io.mosip.vivid.device.mock.scenario.Scenario;
import io.mosip.vivid.device.mock.scenario.Scenarios;
import org.junit.jupiter.api.DynamicTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A set of scenarios with expectations, loaded from a directory ({@code *.json} scenarios with sibling
 * {@code *.expect.json} files) or from the bundled mock scenarios plus the expectations shipped in this module.
 * Run it to get a {@link SuiteReport}, or expose it to JUnit as dynamic tests.
 *
 * <pre>{@code
 * @TestFactory Stream<DynamicTest> scenarios() {
 *     return ScenarioSuite.bundled().dynamicTests(ctx -> engine.open(policy, ctx), ScenarioRunner.Options.defaults());
 * }
 * }</pre>
 */
public final class ScenarioSuite {
    private static final Logger LOG = LoggerFactory.getLogger(ScenarioSuite.class);

    /** One scenario and its expectation (absent = run for the report only, never fails). */
    public record Case(Scenario scenario, Optional<ScenarioExpectation> expectation) {
        public String name() { return scenario.name(); }
    }

    private final String name;
    private final List<Case> cases;

    public ScenarioSuite(String name, List<Case> cases) { this.name = Objects.requireNonNull(name); this.cases = Collections.unmodifiableList(List.copyOf(cases)); }

    /** All scenarios in {@code dir} (non-recursive), each paired with {@code <name>.expect.json} if present. */
    public static ScenarioSuite load(Path dir) {
        List<Case> cases = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json") && !p.toString().endsWith(".expect.json")).sorted().toList()) {
                Scenario s = Scenarios.load(f);
                Path exp = dir.resolve(stripJson(f.getFileName().toString()) + ".expect.json");
                cases.add(new Case(s, Files.isRegularFile(exp) ? Optional.of(ScenarioExpectation.load(exp)) : Optional.empty()));
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
        if (cases.isEmpty()) throw new IllegalArgumentException("no scenarios in " + dir);
        return new ScenarioSuite(dir.getFileName() == null ? dir.toString() : dir.getFileName().toString(), cases);
    }

    /** The nine bundled mock scenarios with the testkit's default expectations. */
    public static ScenarioSuite bundled() {
        List<Case> cases = new ArrayList<>();
        for (String n : Scenarios.bundledNames()) cases.add(new Case(Scenarios.bundled(n), ScenarioExpectation.bundled(n)));
        return new ScenarioSuite("bundled", cases);
    }

    private static String stripJson(String n) { return n.endsWith(".json") ? n.substring(0, n.length() - 5) : n; }

    public String name() { return name; }
    public List<Case> cases() { return cases; }

    /** Run every case sequentially. */
    public SuiteReport run(LivenessSessionFactory factory, ScenarioRunner.Options options) {
        ScenarioRunner runner = new ScenarioRunner(factory);
        List<SuiteReport.CaseResult> results = new ArrayList<>();
        long start = System.currentTimeMillis();
        for (Case c : cases) {
            LOG.info("running scenario '{}'", c.name());
            ScenarioRun run = runner.run(c.scenario(), options);
            ScenarioExpectation.Verdict v = c.expectation().map(e -> e.evaluate(run)).orElse(ScenarioExpectation.Verdict.ok());
            results.add(new SuiteReport.CaseResult(c, run, v));
        }
        return new SuiteReport(name, results, System.currentTimeMillis() - start);
    }

    /** JUnit 5 dynamic tests: one per case; cases without expectation only assert that the run did not throw. */
    public Stream<DynamicTest> dynamicTests(LivenessSessionFactory factory, ScenarioRunner.Options options) {
        ScenarioRunner runner = new ScenarioRunner(factory);
        return cases.stream().map(c -> DynamicTest.dynamicTest(c.name(), () -> {
            ScenarioRun run = runner.run(c.scenario(), options);
            run.error().ifPresent(e -> { throw new AssertionError("scenario '" + c.name() + "' threw", e); });
            c.expectation().ifPresent(e -> {
                ScenarioExpectation.Verdict v = e.evaluate(run);
                if (!v.passed()) throw new AssertionError("scenario '" + c.name() + "' failed: " + String.join("; ", v.problems()) + "\ntrace: " + run.trace());
            });
        }));
    }
}
