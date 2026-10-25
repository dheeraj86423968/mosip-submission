package io.mosip.vivid.testkit.redteam;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosip.vivid.api.LivenessEngine;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.LivenessResult;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.engine.onnx.OnnxEngineFactory;
import io.mosip.vivid.engine.onnx.OnnxLivenessEngine;
import io.mosip.vivid.testkit.corpus.CorpusItem;
import io.mosip.vivid.testkit.corpus.CorpusScoreMain;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Replays every committed red-team fixture ({@code src/test/resources/redteam/fixtures/*.json}): re-renders the variant
 * from its parameters, runs it through the real orchestrator and ONNX engine in the fixture's mode, and asserts that
 * the attack is <b>rejected</b>. A fixture marked {@code "knownBypass": true} records a bypass that is not fixed: while
 * it is still accepted the test is reported as <i>skipped</i> with the reason (never as passed), and once it is
 * rejected the test passes and asks for the flag to be cleared. Skipped without models.
 */
class RedTeamFixtureReplayTest {
    static final Path MODELS = Path.of("..", "liveness-engine-onnx", "models").toAbsolutePath().normalize();
    static final Path FIXTURES = Path.of("src", "test", "resources", "redteam", "fixtures");
    private static final List<LivenessEngine> ENGINES = new ArrayList<>();

    static boolean modelsPresent() throws Exception {
        if (!Files.isDirectory(MODELS)) return false;
        try (var s = Files.list(MODELS)) { return s.filter(p -> p.toString().endsWith(".onnx")).count() >= 3; }
    }

    @AfterAll
    static void close() { ENGINES.forEach(LivenessEngine::close); }

    @TestFactory
    Stream<DynamicTest> committedFixturesAreRejected() throws Exception {
        List<Path> files;
        try (var s = Files.list(FIXTURES)) { files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList(); }
        assertThat(files).as("committed fixtures in %s", FIXTURES).isNotEmpty();
        return files.stream().map(f -> DynamicTest.dynamicTest(f.getFileName().toString(), () -> replay(f)));
    }

    private void replay(Path file) throws Exception {
        JsonNode fx = RedTeamVariant.JSON.readTree(file.toFile());
        RedTeamVariant v = RedTeamVariant.fromJson(fx);       // fails on a stale generator or edited parameters
        assumeTrue(modelsPresent(), "ONNX models not installed; see liveness-engine-onnx/models/README.md");
        Path dir = Files.createTempDirectory("redteam-fixture");
        try {
            String sha = RedTeamRenderer.write(v, dir.resolve(v.itemId()));
            if (fx.hasNonNull("framesSha256") && !fx.get("framesSha256").asText().equals(sha))
                System.out.printf(Locale.ROOT, "[redteam] %s: frames differ from the recorded render (other JDK/platform?); replaying anyway%n", v.id());
            String mode = fx.path("mode").asText("passive");
            LivenessPolicy policy = CorpusScoreMain.policyFor(Workflow.valueOf(fx.path("workflow").asText("RESIDENT")),
                    CorpusScoreMain.Mode.valueOf(mode.toUpperCase(Locale.ROOT)), Double.NaN);
            LivenessEngine engine = new OnnxLivenessEngine(OnnxEngineFactory.discover(MODELS).detectorThresholds(fx.path("detectorThreshold").asDouble(0.35), 0.3).build());
            ENGINES.add(engine);
            LivenessResult r = CorpusScoreMain.replay(engine, policy, CorpusItem.load(dir.resolve(v.itemId()), 15)).result();
            double threshold = fx.path("threshold").asDouble(policy.passiveThreshold());
            boolean accepted = r.decision() == LivenessResult.Decision.LIVE && r.fusedLivenessScore() >= threshold;
            String what = String.format(Locale.ROOT, "%s (%s, %s mode): decision %s, failure %s, score %.3f", v.id(), v.itemId(), mode,
                    r.decision(), r.failure().map(Enum::name).orElse("-"), r.fusedLivenessScore());
            System.out.println("[redteam] " + what);
            if (fx.path("knownBypass").asBoolean(false)) {
                if (accepted) abort("KNOWN BYPASS, still accepted (not fixed): " + what);
                System.out.println("[redteam] known bypass now rejected; set knownBypass=false in " + file.getFileName() + " so it guards against a relapse");
                return;
            }
            assertThat(accepted).as("red-team regression fixture must be rejected: %s", what).isFalse();
        } finally {
            try (var s = Files.walk(dir)) { s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        }
    }
}
