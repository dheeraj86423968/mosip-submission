package io.mosip.vivid.e2e;

import io.mosip.vivid.engine.onnx.AntiSpoofModelConfig;
import io.mosip.vivid.engine.onnx.EngineConfig;
import io.mosip.vivid.engine.onnx.OnnxEngineFactory;
import io.mosip.vivid.engine.onnx.OnnxLivenessEngine;

import java.nio.file.Path;

/**
 * The production ONNX engine configured for the mock device's cartoon faces.
 * <p>
 * YuNet is trained on photographs and scores drawn faces near its decision boundary: in testing it detected a
 * rendered left turn but missed the exact mirror image. The production detector threshold (0.7) is right for
 * real faces, so it is unchanged in the engine; only these synthetic-subject tests lower it.
 * <p>
 * The S1 crop of the bundled anti-spoof model is pinned to the earlier 1.5x box scale: the 1.2x default was chosen on
 * real faces (docs/testing/proxy-eval/improvement-2026-11.md), and on a drawn face the tighter crop is pure cartoon
 * texture with no background, which the network reads as an attack. Drawn faces cannot evaluate passive PAD either
 * way (docs/testing/e2e-findings.md). Every other model, signal and threshold is the production configuration.
 */
final class SyntheticSubjectEngine {
    static final double SYNTHETIC_DETECTOR_THRESHOLD = Double.parseDouble(System.getProperty("e2e.detectorThreshold", "0.35"));
    /** S1 crop scale for drawn faces (the pre-November 2026 default of the bundled model). */
    static final double SYNTHETIC_S1_CROP_SCALE = Double.parseDouble(System.getProperty("e2e.s1CropScale", "1.5"));

    private SyntheticSubjectEngine() {}

    /** True when the model directory holds the YuNet detector; the repository ships only licence files there. */
    static boolean available() {
        return OnnxEngineFactory.findModelDirectory().map(d -> {
            try (java.util.stream.Stream<Path> s = java.nio.file.Files.list(d)) {
                return s.anyMatch(p -> { String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT); return n.contains("yunet") && n.endsWith(".onnx"); });
            } catch (java.io.IOException e) { return false; }
        }).orElse(false);
    }

    static OnnxLivenessEngine create() {
        Path dir = OnnxEngineFactory.findModelDirectory().orElseThrow();
        return new OnnxLivenessEngine(syntheticCrop(OnnxEngineFactory.discover(dir), dir).detectorThresholds(SYNTHETIC_DETECTOR_THRESHOLD, 0.3).build());
    }

    /** Re-adds the discovered anti-spoof models with the bundled model's crop pinned to {@link #SYNTHETIC_S1_CROP_SCALE}. */
    static EngineConfig.Builder syntheticCrop(EngineConfig.Builder b, Path dir) {
        java.util.List<AntiSpoofModelConfig> found = OnnxEngineFactory.discover(dir).build().antiSpoofModels();
        b.clearAntiSpoofModels();
        for (AntiSpoofModelConfig m : found) {
            b.antiSpoofModel(AntiSpoofModelConfig.isSilentFace(m.path()) ? m : new AntiSpoofModelConfig(m.path(), m.inputSize(),
                    SYNTHETIC_S1_CROP_SCALE, m.bgr(), m.realClassIndex(), m.inputScale(), m.flip(), m.fitCrop()));
        }
        return b;
    }
}
