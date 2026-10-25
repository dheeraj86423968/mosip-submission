package io.mosip.vivid.api;

/** Per-frame capture quality. All in [0,1] where 1 is best, except faceCount. */
public final class FaceQuality {
    private final int faceCount;
    private final double sharpness;
    private final double illumination;
    private final double poseQuality;
    private final double occlusion;   // 1 = no occlusion
    private final double sizeQuality; // 1 = ideal inter-ocular distance
    private final double overall;

    public FaceQuality(int faceCount, double sharpness, double illumination, double poseQuality, double occlusion, double sizeQuality) {
        this.faceCount = faceCount; this.sharpness = sharpness; this.illumination = illumination;
        this.poseQuality = poseQuality; this.occlusion = occlusion; this.sizeQuality = sizeQuality;
        this.overall = faceCount == 1 ? Math.min(Math.min(sharpness, illumination), Math.min(Math.min(poseQuality, occlusion), sizeQuality)) : 0;
    }
    public static FaceQuality noFace() { return new FaceQuality(0, 0, 0, 0, 0, 0); }
    public int faceCount() { return faceCount; }
    public double sharpness() { return sharpness; }
    public double illumination() { return illumination; }
    public double poseQuality() { return poseQuality; }
    public double occlusion() { return occlusion; }
    public double sizeQuality() { return sizeQuality; }
    public double overall() { return overall; }
}
