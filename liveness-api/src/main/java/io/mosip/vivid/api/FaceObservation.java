package io.mosip.vivid.api;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Geometry and expression features of the primary face in a frame, in a model-agnostic form so the
 * orchestrator can verify challenges without knowing which landmark model produced them.
 * Angles in degrees. Ratios are dimensionless. Landmarks are normalised to [0,1] of the frame.
 */
public final class FaceObservation {
    private final double boxX, boxY, boxW, boxH;      // normalised [0,1]
    private final double yaw, pitch, roll;            // degrees; yaw>0 means subject turned to their left (camera right)
    private final double leftEyeAspect, rightEyeAspect;
    private final double mouthAspect;
    private final double smileScore;                  // [0,1]
    private final double browRaiseScore;              // [0,1]
    private final double gazeX, gazeY;                // [-1,1] horizontal/vertical gaze direction, 0 = at camera
    private final double[] landmarks;                 // x0,y0,x1,y1,... normalised; may be empty
    private final Map<String, Double> blendshapes;    // optional named expression coefficients

    private FaceObservation(Builder b) {
        boxX = b.boxX; boxY = b.boxY; boxW = b.boxW; boxH = b.boxH;
        yaw = b.yaw; pitch = b.pitch; roll = b.roll;
        leftEyeAspect = b.leftEyeAspect; rightEyeAspect = b.rightEyeAspect; mouthAspect = b.mouthAspect;
        smileScore = b.smileScore; browRaiseScore = b.browRaiseScore; gazeX = b.gazeX; gazeY = b.gazeY;
        landmarks = b.landmarks == null ? new double[0] : b.landmarks.clone();
        blendshapes = b.blendshapes == null ? Map.of() : Collections.unmodifiableMap(b.blendshapes);
    }
    public static Builder builder() { return new Builder(); }

    public double boxX() { return boxX; } public double boxY() { return boxY; }
    public double boxW() { return boxW; } public double boxH() { return boxH; }
    public double yaw() { return yaw; } public double pitch() { return pitch; } public double roll() { return roll; }
    public double leftEyeAspect() { return leftEyeAspect; } public double rightEyeAspect() { return rightEyeAspect; }
    public double eyeAspect() { return (leftEyeAspect + rightEyeAspect) / 2.0; }
    public double mouthAspect() { return mouthAspect; }
    public double smileScore() { return smileScore; } public double browRaiseScore() { return browRaiseScore; }
    public double gazeX() { return gazeX; } public double gazeY() { return gazeY; }
    public double[] landmarks() { return landmarks.clone(); }
    public Map<String, Double> blendshapes() { return blendshapes; }
    /** Inter-ocular distance proxy: face box width in normalised units. */
    public double relativeSize() { return boxW; }
    public double centerX() { return boxX + boxW / 2; }
    public double centerY() { return boxY + boxH / 2; }

    public static final class Builder {
        private double boxX, boxY, boxW, boxH, yaw, pitch, roll, leftEyeAspect = 0.3, rightEyeAspect = 0.3, mouthAspect, smileScore, browRaiseScore, gazeX, gazeY;
        private double[] landmarks; private Map<String, Double> blendshapes;
        public Builder box(double x, double y, double w, double h) { boxX = x; boxY = y; boxW = w; boxH = h; return this; }
        public Builder pose(double yaw, double pitch, double roll) { this.yaw = yaw; this.pitch = pitch; this.roll = roll; return this; }
        public Builder eyeAspect(double left, double right) { leftEyeAspect = left; rightEyeAspect = right; return this; }
        public Builder mouthAspect(double v) { mouthAspect = v; return this; }
        public Builder smileScore(double v) { smileScore = v; return this; }
        public Builder browRaiseScore(double v) { browRaiseScore = v; return this; }
        public Builder gaze(double x, double y) { gazeX = x; gazeY = y; return this; }
        public Builder landmarks(double[] v) { landmarks = v; return this; }
        public Builder blendshapes(Map<String, Double> v) { blendshapes = Objects.requireNonNull(v); return this; }
        public FaceObservation build() { return new FaceObservation(this); }
    }
}
