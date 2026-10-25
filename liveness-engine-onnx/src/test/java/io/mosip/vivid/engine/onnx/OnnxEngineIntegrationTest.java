package io.mosip.vivid.engine.onnx;

import io.mosip.vivid.api.Challenge;
import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.EngineInfo;
import io.mosip.vivid.api.FrameAnalysis;
import io.mosip.vivid.api.LivenessEngine;
import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.PixelFormat;
import io.mosip.vivid.engine.detect.FaceDetection;
import io.mosip.vivid.engine.detect.FaceDetector;
import io.mosip.vivid.engine.detect.YuNetDetector;
import io.mosip.vivid.engine.embed.FaceEmbedder;
import io.mosip.vivid.engine.embed.SFaceEmbedder;
import io.mosip.vivid.engine.image.RgbImage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end run over the real models in {@code ./models}. Skipped when the models are absent so CI without
 * models stays green. Set {@code -Dvivid.testFaceImage=/path/to/face.jpg} to also run a real photograph through
 * the detector. Latency is logged (target: full analysis of a 640x480 RGB frame under 80 ms with 2 threads).
 */
class OnnxEngineIntegrationTest {
    private static final Logger LOG = LoggerFactory.getLogger(OnnxEngineIntegrationTest.class);
    private static OnnxLivenessEngine engine;
    private static EngineConfig config;

    @BeforeAll
    static void load() {
        Path models = Path.of("models");
        assumeTrue(Files.isRegularFile(models.resolve("face_detection_yunet_2023mar.onnx")), "YuNet model not present in ./models; skipping");
        config = OnnxEngineFactory.discover(models).threads(2).sourceIsSystemCamera(false).build();
        engine = new OnnxLivenessEngine(config);
    }

    @AfterAll
    static void close() {
        if (engine != null) engine.close();
    }

    static RgbImage generated640x480(long seed) {
        Random r = new Random(seed);
        byte[] d = new byte[640 * 480 * 3];
        for (int y = 0; y < 480; y++) for (int x = 0; x < 640; x++) {
            int i = (y * 640 + x) * 3;
            int v = (int) (120 + 60 * Math.sin(x / 40.0) * Math.cos(y / 30.0)) + r.nextInt(9) - 4;
            d[i] = (byte) Math.min(255, v + 40); d[i + 1] = (byte) v; d[i + 2] = (byte) Math.max(0, v - 30);
        }
        return new RgbImage(640, 480, d);
    }

    static FaceFrame frame(RgbImage img, long seq) {
        return FaceFrame.builder().sequence(seq).timestampNanos(seq * 66_000_000L).width(img.width()).height(img.height())
                .format(PixelFormat.RGB888).pixels(img.data()).build();
    }

    @Test
    void engineInfoReflectsLoadedModels() {
        EngineInfo info = engine.info();
        assertThat(info.engineId()).isEqualTo(OnnxLivenessEngine.ENGINE_ID);
        assertThat(info.signalIds()).contains(Signals.MOIRE, Signals.COLOR_STATS, Signals.RPPG, Signals.PARALLAX, Signals.QUALITY);
        assertThat(info.supportedChallenges()).contains(ChallengeType.BLINK, ChallengeType.TURN_LEFT).doesNotContain(ChallengeType.RAISE_EYEBROWS);
        assertThat(info.signalIds().contains(Signals.CNN_TEXTURE)).isEqualTo(!config.antiSpoofModels().isEmpty());
        assertThat(info.producesEmbeddings()).isEqualTo(config.embeddingModel().isPresent());
        assertThat(engine.selfTest().skipped).isTrue();
    }

    @Test
    void generatedFrameWithoutFaceRunsAndReportsNoFace() throws Exception {
        try (var analyzer = engine.newAnalyzer()) {
            FrameAnalysis a = analyzer.analyze(frame(generated640x480(1), 0));
            assertThat(a.face()).isEmpty();
            assertThat(a.quality().faceCount()).isZero();
            assertThat(a.processingNanos()).isPositive();
            // MJPEG path through the same analyzer
            ByteArrayOutputStream jpg = new ByteArrayOutputStream();
            ImageIO.write(generated640x480(2).toBufferedImage(), "jpg", jpg);
            FaceFrame mjpeg = FaceFrame.builder().sequence(1).width(640).height(480).format(PixelFormat.MJPEG).pixels(jpg.toByteArray()).build();
            assertThat(analyzer.analyze(mjpeg).quality().faceCount()).isZero();
        }
    }

    /**
     * Full-pipeline latency. A generated frame contains no real face, so the real YuNet detector runs and its result is
     * replaced by a fixed plausible face; every downstream stage (quality, observation, all signals, CNN, embedding on
     * every frame) then runs exactly as it would for a real face. This is the worst case: embedding normally runs every
     * K-th frame only.
     */
    @Test
    void fullFrameLatencyOn640x480() {
        YuNetDetector yunet = new YuNetDetector(OrtModel.load(config.detectorModel().orElseThrow(), 2), 0.7, 0.3, 5);
        FaceDetection fixed = new FaceDetection(250, 150, 140, 170, 0.95, new double[] {290, 210, 350, 210, 320, 245, 297, 275, 343, 275});
        FaceDetector forced = img -> { yunet.detect(img); return List.of(fixed); };
        FaceEmbedder embedder = config.embeddingModel().map(p -> (FaceEmbedder) new SFaceEmbedder(OrtModel.load(p, 2))).orElse(null);
        OnnxFrameAnalyzer analyzer = new OnnxFrameAnalyzer(forced, embedder, 1, engine.newAnalyzer().signals(), 0.7);
        analyzer.onChallenge(new Challenge(ChallengeType.TURN_LEFT, 0, 1, 0, 8000));
        RgbImage img = generated640x480(3);
        int warmup = 5, runs = 30;
        long[] t = new long[runs];
        FaceFrame f = null;
        for (int i = 0; i < warmup + runs; i++) {
            f = frame(img, i);
            FrameAnalysis a = analyzer.analyze(f);
            if (i >= warmup) t[i - warmup] = a.processingNanos();
            assertThat(a.face()).isPresent();
            assertThat(a.signals()).isNotEmpty();
            if (embedder != null) assertThat(a.embedding()).isPresent();
        }
        StringBuilder stages = new StringBuilder();
        analyzer.lastStageNanos().forEach((k, v) -> stages.append(k).append('=').append(String.format("%.1f", v / 1e6)).append(" "));
        LOG.info("LATENCY stages (last frame, ms): {}", stages.toString().trim());
        Arrays.sort(t);
        double median = t[runs / 2] / 1e6, p90 = t[(int) (runs * 0.9)] / 1e6, max = t[runs - 1] / 1e6;
        LOG.info("LATENCY full pipeline 640x480 RGB, 2 threads, embedding every frame: median={} ms p90={} ms max={} ms (target < 80 ms)",
                String.format("%.1f", median), String.format("%.1f", p90), String.format("%.1f", max));
        long d0 = System.nanoTime();
        for (int i = 0; i < 10; i++) yunet.detect(img);
        LOG.info("LATENCY YuNet detection alone (640x640 input): {} ms", String.format("%.1f", (System.nanoTime() - d0) / 1e7));
        assertThat(median).as("median full-frame latency in ms").isLessThan(250); // generous bound for slow CI machines
    }

    @Test
    void realPhotographIfProvided() throws Exception {
        String p = System.getProperty("vivid.testFaceImage", System.getenv("VIVID_TEST_FACE_IMAGE"));
        assumeTrue(p != null && Files.isRegularFile(Path.of(p)), "no test face image provided");
        BufferedImage bi = ImageIO.read(Path.of(p).toFile());
        RgbImage img = RgbImage.fromBufferedImage(bi);
        try (var analyzer = engine.newAnalyzer()) {
            FrameAnalysis first = analyzer.analyze(frame(img, 0));
            FrameAnalysis a = first;
            for (int i = 1; i < 3; i++) a = analyzer.analyze(frame(img, i));
            LOG.info("real image {}x{}: faces={} quality={} time={} ms", img.width(), img.height(), a.quality().faceCount(),
                    String.format("%.2f", a.quality().overall()), String.format("%.1f", a.processingNanos() / 1e6));
            a.face().ifPresent(o -> LOG.info("observation yaw={} pitch={} roll={} EAR={} MAR={} smile={}",
                    fmt(o.yaw()), fmt(o.pitch()), fmt(o.roll()), fmt(o.eyeAspect()), fmt(o.mouthAspect()), fmt(o.smileScore())));
            for (SignalScore s : a.signals()) LOG.info("  {}", s);
            assertThat(a.quality().faceCount()).isGreaterThanOrEqualTo(1);
            if (a.face().isPresent() && config.embeddingModel().isPresent()) {
                assertThat(a.embedding()).as("embedding only every K frames").isEmpty();
                float[] e = first.embedding().orElseThrow();
                double norm = 0;
                for (float v : e) norm += v * v;
                assertThat(norm).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-3));
            }
        }
    }

    @Test
    void selfTestWithBundledImage() throws Exception {
        String p = System.getProperty("vivid.testFaceImage", System.getenv("VIVID_TEST_FACE_IMAGE"));
        assumeTrue(p != null && Files.isRegularFile(Path.of(p)), "no test face image provided");
        EngineConfig.Builder b = OnnxEngineFactory.discover(Path.of("models"));
        b.selfTest(new EngineConfig.SelfTestCase(Path.of(p), Signals.QUALITY, 0, 1));
        try (LivenessEngine e = new OnnxLivenessEngine(b.build())) {
            LivenessEngine.SelfTestResult r = e.selfTest();
            LOG.info("self-test: passed={} {}", r.passed, r.detail);
            assertThat(r.skipped).isFalse();
        }
    }

    private static String fmt(double v) { return String.format("%.2f", v); }
}
