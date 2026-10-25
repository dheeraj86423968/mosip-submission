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

    static boolean available() { return OnnxEngineFactory.findModelDirectory().isPresent(); }

    static OnnxLivenessEngine create() {
        Path dir = OnnxEngineFactory.findModelDirectory().orElseThrow();
        return new OnnxLivenessEngine(OnnxEngineFactory.discover(dir).detectorThresholds(SYNTHETIC_DETECTOR_THRESHOLD, 0.3).build());
    }
}
