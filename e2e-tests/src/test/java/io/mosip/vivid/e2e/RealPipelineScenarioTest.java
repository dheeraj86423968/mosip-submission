package io.mosip.vivid.e2e;

import io.mosip.vivid.api.LivenessEngine;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.core.action.ActionThresholds;
import io.mosip.vivid.core.session.LivenessSessionFactory;
import io.mosip.vivid.core.session.TimeSource;
import io.mosip.vivid.device.mock.Pacing;
import io.mosip.vivid.engine.onnx.OnnxEngineFactory;
import io.mosip.vivid.telemetry.audit.InMemoryAuditSink;
import io.mosip.vivid.telemetry.metrics.LivenessMetrics;
import io.mosip.vivid.testkit.scenario.ScenarioRunner;
import io.mosip.vivid.testkit.scenario.ScenarioSuite;
import io.mosip.vivid.testkit.scenario.SuiteReport;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs every bundled mock-device scenario through the real liveness-core orchestrator and the real ONNX engine.
 * Writes JSON and HTML reports to target/e2e-report. Skipped when the ONNX models are not present.
 */
class RealPipelineScenarioTest {

    @Test
    void defaultPolicy() throws Exception {
        run("default-policy", LivenessPolicy::defaults);
    }

    /** Same suite with active challenges always issued: the defence that a prompt-ignoring replay cannot pass. */
    @Test
    void alwaysChallengePolicy() throws Exception {
        run("always-challenge", w -> LivenessPolicy.builder(w).policyVersion("e2e-always")
                .activeMode(LivenessPolicy.ActiveMode.ALWAYS).minChallenges(1).maxChallenges(2).build());
    }

    private void run(String label, java.util.function.Function<Workflow, LivenessPolicy> policies) throws Exception {
        assumeTrue(SyntheticSubjectEngine.available(), "ONNX models not installed; see liveness-engine-onnx/models/README.md");
        try (LivenessEngine engine = SyntheticSubjectEngine.create()) {
            InMemoryAuditSink audit = new InMemoryAuditSink(5000);
            LivenessMetrics metrics = new LivenessMetrics();
            // One clock per session: each scenario's device timestamps start at zero.
            io.mosip.vivid.testkit.scenario.LivenessSessionFactory sessions = ctx -> {
                AtomicLong clock = new AtomicLong();
                LivenessSessionFactory core = new LivenessSessionFactory(engine, policies, audit, metrics,
                        TimeSource.of(clock::get), new SecureRandom(), ActionThresholds.defaults());
                return new FrameClockSession(core.open(ctx), clock);
            };

            ScenarioRunner.Options opts = ScenarioRunner.Options.defaults()
                    .pacing(Pacing.unthrottled()).workflow(Workflow.RESIDENT).maxWallMillis(180_000)
                    .maxScenarioMillis(75_000); // above the default 60 s session timeout so it can fire
            SuiteReport report = ScenarioSuite.bundled().run(sessions, opts);
            report.write(Path.of("target", "e2e-report"), label);
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("\n[{}]\n{}", label, report.summary());
            report.results().forEach(r -> org.slf4j.LoggerFactory.getLogger(getClass()).warn("{} -> {} passed={} frames={} trace={}",
                    r.name(), r.run().decisionText(), r.passed(), r.run().framesFed(),
                    r.run().trace().stream().map(Object::toString).reduce((a, b) -> a + " | " + b).orElse("")));
            assertThat(report.errors()).as("no scenario may crash the pipeline").isZero();
        }
    }
}
