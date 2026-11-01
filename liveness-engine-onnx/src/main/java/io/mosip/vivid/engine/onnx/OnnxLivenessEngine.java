package io.mosip.vivid.engine.onnx;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.EngineInfo;
import io.mosip.vivid.api.FrameAnalysis;
import io.mosip.vivid.api.FrameAnalyzer;
import io.mosip.vivid.api.LivenessEngine;
import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.PixelFormat;
import io.mosip.vivid.engine.detect.FaceDetector;
import io.mosip.vivid.engine.detect.YuNetDetector;
import io.mosip.vivid.engine.embed.FaceEmbedder;
import io.mosip.vivid.engine.embed.SFaceEmbedder;
import io.mosip.vivid.engine.image.RgbImage;
import io.mosip.vivid.engine.signal.CnnTextureSignal;
import io.mosip.vivid.engine.signal.ColorStatsSignal;
import io.mosip.vivid.engine.signal.DepthSignal;
import io.mosip.vivid.engine.signal.IrSignal;
import io.mosip.vivid.engine.signal.InjectionSignal;
import io.mosip.vivid.engine.signal.MoireSignal;
import io.mosip.vivid.engine.signal.ParallaxSignal;
import io.mosip.vivid.engine.signal.RppgSignal;
import io.mosip.vivid.engine.signal.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Desktop {@link LivenessEngine} on ONNX Runtime. Loads every model once from an {@link EngineConfig}; the ONNX
 * sessions are thread-safe and shared by all analyzers, so {@link #newAnalyzer()} is cheap and each session gets its
 * own temporal state (rPPG buffer, parallax reference).
 *
 * <p>The face detector is mandatory. The anti-spoofing CNN and the embedding model are optional: when absent, S1 is not
 * offered and {@code producesEmbeddings} is false, and the engine still runs the hand-crafted signals.
 */
public final class OnnxLivenessEngine implements LivenessEngine {
    public static final String ENGINE_ID = "mosip-vivid-onnx";
    public static final String ENGINE_VERSION = "0.1.0";
    private static final Logger LOG = LoggerFactory.getLogger(OnnxLivenessEngine.class);

    private final EngineConfig config;
    private final List<OrtModel> models = new ArrayList<>();
    private final FaceDetector detector;
    private final List<CnnTextureSignal.Model> antiSpoof = new ArrayList<>();
    private final FaceEmbedder embedder;
    private final EngineInfo info;

    public OnnxLivenessEngine(EngineConfig config) {
        this.config = Objects.requireNonNull(config);
        try {
            OrtModel det = track(OrtModel.load(config.detectorModel()
                    .orElseThrow(() -> new EngineException("no face detector model configured")), config.threads(), config.executionProvider()));
            this.detector = new YuNetDetector(det, config.detectorScoreThreshold(), config.detectorNmsThreshold(), 5);
            if (config.isEnabled(Signals.CNN_TEXTURE)) {
                for (AntiSpoofModelConfig a : config.antiSpoofModels()) {
                    OrtModel m = track(OrtModel.load(a.path(), config.threads(), config.executionProvider()));
                    antiSpoof.add(new CnnTextureSignal.Model(m, a.inputSize(), a.cropScale(), a.bgr(), a.realClassIndex(), a.inputScale(), a.flip(), a.fitCrop()));
                }
            }
            this.embedder = config.embeddingModel().map(p -> (FaceEmbedder) new SFaceEmbedder(track(OrtModel.load(p, config.threads(), config.executionProvider())))).orElse(null);
        } catch (RuntimeException e) {
            close();
            throw e;
        }
        if (config.landmarkModel().isPresent()) {
            LOG.warn("dense landmark model {} configured but not supported yet; using 5-point geometry", config.landmarkModel().get());
        }
        Set<ChallengeType> challenges = EnumSet.allOf(ChallengeType.class);
        // Brow raise from 5 landmarks is too weak to verify a challenge reliably.
        challenges.remove(ChallengeType.RAISE_EYEBROWS);
        Set<String> ids = new LinkedHashSet<>();
        for (Signal s : createSignals()) ids.add(s.id());
        this.info = new EngineInfo(ENGINE_ID, ENGINE_VERSION, config.bundleId(), config.bundleVersion(), challenges, ids, embedder != null);
        LOG.info("engine ready: signals={} embeddings={} bundle={}@{}", ids, embedder != null, config.bundleId(), config.bundleVersion());
    }

    private OrtModel track(OrtModel m) {
        models.add(m);
        return m;
    }

    @Override public EngineInfo info() { return info; }

    public EngineConfig config() { return config; }

    @Override
    public OnnxFrameAnalyzer newAnalyzer() {
        return new OnnxFrameAnalyzer(detector, embedder, config.embeddingEveryFrames(), createSignals(), config.detectorScoreThreshold());
    }

    private List<Signal> createSignals() {
        List<Signal> s = new ArrayList<>();
        if (config.isEnabled(Signals.CNN_TEXTURE) && !antiSpoof.isEmpty()) {
            s.add(new CnnTextureSignal(antiSpoof, config.calibration(Signals.CNN_TEXTURE)));
        }
        if (config.isEnabled(Signals.MOIRE)) s.add(new MoireSignal(config.calibration(Signals.MOIRE)));
        if (config.isEnabled(Signals.COLOR_STATS)) s.add(new ColorStatsSignal(config.calibration(Signals.COLOR_STATS)));
        if (config.isEnabled(Signals.RPPG)) s.add(new RppgSignal(config.calibration(Signals.RPPG)));
        if (config.isEnabled(Signals.PARALLAX)) s.add(new ParallaxSignal(config.calibration(Signals.PARALLAX)));
        if (config.isEnabled(Signals.QUALITY)) s.add(new io.mosip.vivid.engine.signal.QualitySignal());
        if (config.isEnabled(Signals.INJECTION)) s.add(new InjectionSignal(config.sourceIsSystemCamera()));
        // S8: abstain (emit nothing) unless the frame carries a depth / IR plane, so RGB-only devices are unaffected.
        if (config.isEnabled(Signals.DEPTH)) s.add(new DepthSignal(config.depthIr(), config.calibration(Signals.DEPTH)));
        if (config.isEnabled(Signals.IR)) s.add(new IrSignal(config.depthIr(), config.calibration(Signals.IR)));
        return s;
    }

    /**
     * Runs every configured self-test image through a fresh analyzer and checks the named signal's attack probability
     * against the expected range. A face must be detected in every image.
     */
    @Override
    public SelfTestResult selfTest() {
        if (config.selfTests().isEmpty()) return SelfTestResult.skipped("no self-test images configured");
        StringBuilder detail = new StringBuilder();
        boolean ok = true;
        for (EngineConfig.SelfTestCase c : config.selfTests()) {
            String line;
            try {
                BufferedImage img = ImageIO.read(c.image().toFile());
                if (img == null) throw new IOException("unreadable image");
                RgbImage rgb = RgbImage.fromBufferedImage(img);
                FaceFrame frame = FaceFrame.builder().width(rgb.width()).height(rgb.height()).format(PixelFormat.RGB888)
                        .pixels(rgb.data()).timestampNanos(System.nanoTime()).build();
                FrameAnalyzer a = newAnalyzer();
                FrameAnalysis r = a.analyze(frame);
                Double p = null;
                for (SignalScore s : r.signals()) if (s.signalId().equals(c.signalId())) p = s.attackProbability();
                if (r.face().isEmpty()) { ok = false; line = "FAIL " + c + ": no single face"; }
                else if (p == null) { ok = false; line = "FAIL " + c + ": signal not produced"; }
                else if (p < c.min() || p > c.max()) { ok = false; line = String.format("FAIL %s: got %.3f", c, p); }
                else line = String.format("ok %s: %.3f", c, p);
            } catch (IOException | RuntimeException e) {
                ok = false;
                line = "FAIL " + c + ": " + e.getMessage();
            }
            detail.append(line).append('\n');
        }
        String d = detail.toString().trim();
        if (!ok) LOG.error("self-test failed:\n{}", d);
        return ok ? SelfTestResult.pass(d) : SelfTestResult.fail(d);
    }

    @Override
    public void close() {
        for (OrtModel m : models) m.close();
        models.clear();
    }
}
