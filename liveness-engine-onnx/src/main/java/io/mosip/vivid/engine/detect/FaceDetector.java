package io.mosip.vivid.engine.detect;

import io.mosip.vivid.engine.image.RgbImage;

import java.util.List;

/** Detects faces with five landmarks. Implementations must be thread-safe (one instance is shared by all analyzers). */
public interface FaceDetector {
    /** Detections sorted by descending score, after non-maximum suppression. */
    List<FaceDetection> detect(RgbImage frame);
}
