package io.mosip.vivid.testkit.scenario;

import io.mosip.vivid.api.FailureReason;
import io.mosip.vivid.api.LivenessResult;
import io.mosip.vivid.api.SessionState;
import io.mosip.vivid.device.mock.scenario.Scenarios;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioRunnerTest {
    @Test
    void runsScenarioToTerminalStateAndRecordsTrace() {
        ScenarioRunner runner = new ScenarioRunner(ctx -> new FakeSession(ctx, 12, null));
        ScenarioRun run = runner.run(Scenarios.bundled("live-pass"));
        assertThat(run.result()).isPresent();
        assertThat(run.result().get().decision()).isEqualTo(LivenessResult.Decision.LIVE);
        assertThat(run.framesFed()).isEqualTo(12);
        assertThat(run.finalState().phase()).isEqualTo(SessionState.Phase.PASSED);
        assertThat(run.trace()).extracting(t -> t.current().phase()).containsExactly(SessionState.Phase.PASSIVE_EVALUATING, SessionState.Phase.PASSED);
        assertThat(run.trace().get(1).scenarioMs()).isEqualTo(11 * 1000 / 15);   // 12th frame at 15 fps
        assertThat(run.timedOut()).isFalse();
        assertThat(run.healthEvents()).isNotEmpty();
        assertThat(run.decisionText()).isEqualTo("LIVE");
    }

    @Test
    void failedSessionWithoutRetryStopsTheRunAndExpectationEvaluates() {
        ScenarioRunner runner = new ScenarioRunner(ctx -> new FakeSession(ctx, 5, FailureReason.PAD_DETECTED));
        ScenarioRun run = runner.run(Scenarios.bundled("print-attack"));
        assertThat(run.result().get().decision()).isEqualTo(LivenessResult.Decision.ATTACK);
        assertThat(run.framesFed()).isEqualTo(5);
        ScenarioExpectation e = ScenarioExpectation.bundled("print-attack").orElseThrow();
        assertThat(e.evaluate(run).passed()).isTrue();
        ScenarioExpectation wrong = ScenarioExpectation.of("print-attack", LivenessResult.Decision.LIVE);
        ScenarioExpectation.Verdict v = wrong.evaluate(run);
        assertThat(v.passed()).isFalse();
        assertThat(v.problems()).anyMatch(p -> p.contains("decision ATTACK"));
    }

    @Test
    void sessionThatNeverDecidesTimesOutOnScenarioLimit() {
        ScenarioRunner runner = new ScenarioRunner(ctx -> new FakeSession(ctx, Integer.MAX_VALUE, null));
        ScenarioRun run = runner.run(Scenarios.bundled("live-pass"), ScenarioRunner.Options.defaults().maxScenarioMillis(2000));
        assertThat(run.result()).isEmpty();
        assertThat(run.timedOut()).isTrue();
        assertThat(run.framesFed()).isBetween(28, 32);
        assertThat(ScenarioExpectation.bundled("live-pass").orElseThrow().evaluate(run).problems()).anyMatch(p -> p.contains("no LivenessResult"));
    }

    @Test
    void healthEventsTickTheSession() {
        FakeSession[] holder = new FakeSession[1];
        ScenarioRunner runner = new ScenarioRunner(ctx -> holder[0] = new FakeSession(ctx, Integer.MAX_VALUE, null));
        ScenarioRun run = runner.run(Scenarios.bundled("stall"), ScenarioRunner.Options.defaults().maxScenarioMillis(12_000));
        assertThat(run.healthEvents()).extracting(h -> h.state().name()).contains("DEGRADED");
        assertThat(holder[0].ticks()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void suiteLoadsFromDirectoryAndProducesReports() throws Throwable {
        Path dir = Files.createTempDirectory("suite");
        Files.writeString(dir.resolve("a.json"), "{\"name\":\"a\",\"segments\":[{\"durationMs\":1000,\"fps\":10}]}");
        Files.writeString(dir.resolve("a.expect.json"), "{\"scenario\":\"a\",\"expectedDecision\":\"LIVE\",\"maxDurationMs\":5000}");
        Files.writeString(dir.resolve("b.json"), "{\"name\":\"b\",\"segments\":[{\"durationMs\":1000,\"fps\":10,\"label\":\"PRINT\",\"overlay\":\"PRINT_TEXTURE\"}]}");
        Files.writeString(dir.resolve("b.expect.json"), "{\"scenario\":\"b\",\"expectedDecision\":[\"ATTACK\",\"NOT_LIVE\"],\"allowedFailureReasons\":[\"PAD_DETECTED\"]}");
        Files.writeString(dir.resolve("c.json"), "{\"name\":\"c\",\"segments\":[{\"durationMs\":500,\"fps\":10,\"source\":\"blank\",\"label\":\"NONE\"}]}");
        ScenarioSuite suite = ScenarioSuite.load(dir);
        assertThat(suite.cases()).hasSize(3);
        SuiteReport report = suite.run(ctx -> new FakeSession(ctx, 3, null), ScenarioRunner.Options.defaults());
        assertThat(report.total()).isEqualTo(3);
        assertThat(report.failures()).isEqualTo(1);   // b expected an attack, fake always passes
        assertThat(report.summary()).contains("run 3, failures 1").contains("PASS   a").contains("FAIL   b").contains("decision LIVE, expected");
        String json = report.toJson();
        assertThat(json).contains("\"scenario\" : \"b\"").contains("\"passed\" : false").contains("\"trace\"");
        String html = report.toHtml();
        assertThat(html).startsWith("<!doctype html>").contains("class=\"fail\">FAIL").contains("PASSIVE_EVALUATING -&gt; PASSED");
        assertThat(html).doesNotContain("<script");
        report.write(dir.resolve("out"), "suite");
        assertThat(dir.resolve("out/suite.html")).exists();
        // dynamic tests: one per case, the failing one throws
        List<org.junit.jupiter.api.DynamicTest> tests = suite.dynamicTests(ctx -> new FakeSession(ctx, 3, null), ScenarioRunner.Options.defaults()).toList();
        assertThat(tests).hasSize(3);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tests.get(1).getExecutable().execute()).isInstanceOf(AssertionError.class).hasMessageContaining("'b' failed");
        tests.get(0).getExecutable().execute();
    }

    @Test
    void bundledSuiteHasAnExpectationForEveryScenario() {
        ScenarioSuite s = ScenarioSuite.bundled();
        assertThat(s.cases()).hasSize(9);
        assertThat(s.cases()).allMatch(c -> c.expectation().isPresent());
        assertThat(s.cases()).allMatch(c -> c.expectation().get().scenario().equals(c.name()));
    }
}
