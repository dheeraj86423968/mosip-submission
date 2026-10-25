package io.mosip.vivid.demo;

import io.mosip.vivid.api.SessionState;
import io.mosip.vivid.api.Workflow;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Headless smoke test of the whole attack-lab pipeline: real orchestrator, real ONNX engine, device-mock frames,
 * the Swing view painted offscreen, PNG screenshots and the AVI. Skipped when the ONNX models are not installed.
 */
class HeadlessSessionTest {
    private static DemoEngine engine;
    private static final AtomicBoolean injection = new AtomicBoolean();

    @BeforeAll
    static void setUp() {
        System.setProperty("java.awt.headless", "true");
        Optional<Path> models = DemoSetup.findModels(null);
        assumeTrue(models.isPresent(), "ONNX models not installed (liveness-engine-onnx/models)");
        engine = new DemoEngine(DemoSetup.engine(models.get(), DemoSetup.SYNTHETIC_DETECTOR_THRESHOLD), injection::get);
    }

    @AfterAll
    static void tearDown() {
        if (engine != null) engine.close();
    }

    @Test
    void liveSubjectPassesTheOperatorChallengesAndIsRecorded(@TempDir Path dir) throws Exception {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        Recorder r = new Recorder(engine, injection, dir, 5, 400, 0, new PrintStream(log, true)).videoFormat(640, 0.5f);
        Recorder.Take take = new Recorder.Take("smoke", "", Workflow.OPERATOR, Subject.LIVE, 3, List.of(),
                Map.of("passive", "passive.png", "challenge", "challenge.png", "result", "result.png"));
        List<Recorder.TakeResult> results = r.record(List.of(take), "smoke.avi");

        Recorder.TakeResult res = results.get(0);
        assertThat(res.finalState().phase()).as(log.toString()).isEqualTo(SessionState.Phase.PASSED);
        assertThat(res.decision()).isEqualTo("LIVE");
        assertThat(res.evidenceHash()).matches("[0-9a-f]{64}");
        for (String f : List.of("passive.png", "challenge.png", "result.png")) {
            Path p = dir.resolve(f);
            assertThat(p).exists();
            assertThat(Files.size(p)).isLessThan(300 * 1024);
            BufferedImage img = ImageIO.read(p.toFile());
            assertThat(img.getWidth()).isEqualTo(LabView.W);
            assertThat(img.getHeight()).isEqualTo(LabView.H);
        }
        assertThat(Files.size(dir.resolve("smoke.avi"))).isGreaterThan(10_000);
    }

    @Test
    void injectedStreamIsVetoedAsAnAttack(@TempDir Path dir) throws Exception {
        Recorder r = new Recorder(engine, injection, dir, 5, 0, 0, new PrintStream(new ByteArrayOutputStream()));
        Recorder.Take take = new Recorder.Take("injected", "", Workflow.RESIDENT, Subject.INJECTED, 3, List.of(), Map.of());
        Recorder.TakeResult res = r.record(List.of(take), null).get(0);
        assertThat(res.decision()).isEqualTo("ATTACK");
        assertThat(res.finalState().failure()).hasValueSatisfying(f -> assertThat(f.name()).isEqualTo("PAD_DETECTED"));
    }
}
