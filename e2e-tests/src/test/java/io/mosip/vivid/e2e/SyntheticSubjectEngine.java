package io.mosip.vivid.e2e;

import io.mosip.vivid.engine.onnx.OnnxEngineFactory;
import io.mosip.vivid.engine.onnx.OnnxLivenessEngine;

import java.nio.file.Path;

/**
 * The production ONNX engine configured for the mock device's cartoon faces.
 * <p>
 * YuNet is trained on photographs and scores drawn faces near its decision boundary: in testing it detected a
 * rendered left turn but missed the exact mirror image. The production detector threshold (0.7) is right for
 * real faces, so it is unchanged in the engine; only these synthetic-subject tests lower it. Every other model,
 * signal and threshold is the production configuration.
 */
final class SyntheticSubjectEngine {
    static final double SYNTHETIC_DETECTOR_THRESHOLD = Double.parseDouble(System.getProperty("e2e.detectorThreshold", "0.35"));

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
        return new OnnxLivenessEngine(OnnxEngineFactory.discover(dir).detectorThresholds(SYNTHETIC_DETECTOR_THRESHOLD, 0.3).build());
    }
}
