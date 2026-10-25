package io.mosip.vivid.device.mock.render;

/**
 * Instantaneous pose and expression of the synthetic face. All values are plain numbers so tests can assert
 * on them and renderers can be swapped. Angles in degrees; expression intensities in [0,1].
 */
public final class FaceState {
    private final double yawDeg;
    private final double pitchDeg;
    private final double eyeOpen;
    private final double mouthOpen;
    private final double smile;
    private final double browRaise;

    public FaceState(double yawDeg, double pitchDeg, double eyeOpen, double mouthOpen, double smile, double browRaise) {
        this.yawDeg = yawDeg; this.pitchDeg = pitchDeg;
        this.eyeOpen = clamp01(eyeOpen); this.mouthOpen = clamp01(mouthOpen);
        this.smile = clamp01(smile); this.browRaise = clamp01(browRaise);
    }

    /** Neutral: frontal, eyes open, mouth closed, no smile. */
    public static FaceState neutral() { return new FaceState(0, 0, 1, 0, 0, 0); }

    private static double clamp01(double v) { return Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v)); }

    /** Positive = subject turned to their left (camera right), matching {@code FaceObservation.yaw}. */
    public double yawDeg() { return yawDeg; }
    /** Positive = looking up. */
    public double pitchDeg() { return pitchDeg; }
    /** 1 = fully open, 0 = closed (blink). */
    public double eyeOpen() { return eyeOpen; }
    /** 1 = mouth wide open. */
    public double mouthOpen() { return mouthOpen; }
    /** 1 = broad smile. */
    public double smile() { return smile; }
    /** 1 = eyebrows fully raised. */
    public double browRaise() { return browRaise; }

    @Override public String toString() {
        return String.format("FaceState{yaw=%.1f pitch=%.1f eye=%.2f mouth=%.2f smile=%.2f brow=%.2f}", yawDeg, pitchDeg, eyeOpen, mouthOpen, smile, browRaise);
    }
}
