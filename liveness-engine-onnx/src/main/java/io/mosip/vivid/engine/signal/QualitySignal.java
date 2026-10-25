package io.mosip.vivid.engine.signal;

import io.mosip.vivid.api.FaceObservation;
import io.mosip.vivid.api.FaceQuality;
import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.engine.detect.FaceDetection;
import io.mosip.vivid.engine.image.FloatImage;
import io.mosip.vivid.engine.image.ImageOps;

/**
 * S9 - capture quality gate. Produces {@link FaceQuality} (all components in [0,1], 1 = best):
 * <ul>
 *   <li>sharpness - Laplacian variance of the 96 px face crop, {@code min(1, var / 120)};</li>
 *   <li>illumination - mean luma triangle-mapped (best between 90 and 170) times {@code 1 - 2 * clipped fraction};</li>
 *   <li>pose - {@code 1 - max(|yaw| / 45, |pitch| / 35)};</li>
 *   <li>occlusion - detector score mapped from the occlusion floor (0.6 by default) to floor + 0.3, times landmark-plausibility (eyes level, nose between eyes,
 *       mouth below nose). A covered face lowers YuNet's score and distorts the landmark geometry;</li>
 *   <li>size - inter-ocular distance: 0 at 20 px, 1 between 50 and 160 px, falling to 0 at 260 px (too close).</li>
 * </ul>
 * As a {@code SignalScore} it reports {@code 1 - overall} with confidence 0 so a confidence-weighted fusion ignores it;
 * the orchestrator gates on {@code FrameAnalysis.quality()} instead.
 */
public final class QualitySignal implements Signal {
    /** Detector score below which a face counts as fully occluded, for the production detector threshold of 0.7. */
    public static final double DEFAULT_OCCLUSION_FLOOR = 0.6;
    private static final double OCCLUSION_SPAN = 0.3;

    private final double occlusionFloor;
    private FaceQuality last;

    /** Quality assessment for the production detector operating point. */
    public QualitySignal() {
        this(DEFAULT_OCCLUSION_FLOOR + 0.1);
    }

    /**
     * Quality assessment relative to the detector's configured score threshold. The occlusion floor sits 0.1
     * below that threshold (never above {@link #DEFAULT_OCCLUSION_FLOOR}), so a detector run at a lower operating
     * point does not read every accepted face as occluded. At the production threshold of 0.7 the floor is 0.6.
     */
    public QualitySignal(double detectorScoreThreshold) {
        this.occlusionFloor = Math.min(DEFAULT_OCCLUSION_FLOOR, detectorScoreThreshold - 0.1);
    }

    @Override public String id() { return Signals.QUALITY; }

    @Override
    public SignalScore score(FaceContext ctx) {
        FaceQuality q = ctx.quality().orElseGet(() -> assess(ctx, 1));
        return SignalScore.of(id(), 1 - q.overall(), 0.0);
    }

    /** Computes quality for the primary face; {@code faceCount} is reported as-is (overall is 0 unless it is 1). */
    public FaceQuality assess(FaceContext ctx, int faceCount) {
        FaceDetection f = ctx.face();
        FloatImage face = ctx.grayFace(96);
        double sharp = ImageOps.clamp01(ImageOps.laplacianVariance(face) / 120.0);
        double mean = ImageOps.meanStd(face)[0];
        double clipped = ImageOps.clippedFraction(face, 5, 250);
        double light;
        if (mean < 90) light = ImageOps.clamp01((mean - 30) / 60.0);
        else if (mean > 170) light = ImageOps.clamp01((230 - mean) / 60.0);
        else light = 1;
        light *= ImageOps.clamp01(1 - 2 * clipped);
        double yaw = 0, pitch = 0;
        FaceObservation o = ctx.observation().orElse(null);
        if (o != null) { yaw = o.yaw(); pitch = o.pitch(); }
        double pose = ImageOps.clamp01(1 - Math.max(Math.abs(yaw) / 45.0, Math.abs(pitch) / 35.0));
        double occlusion = ImageOps.clamp01((f.score() - occlusionFloor) / OCCLUSION_SPAN) * landmarkPlausibility(f);
        double iod = f.interOcular();
        double size;
        if (iod < 50) size = ImageOps.clamp01((iod - 20) / 30.0);
        else if (iod > 160) size = ImageOps.clamp01((260 - iod) / 100.0);
        else size = 1;
        last = new FaceQuality(faceCount, sharp, light, pose, occlusion, size);
        return last;
    }

    /** 1 for an anatomically plausible landmark layout, lower when points are out of order. */
    static double landmarkPlausibility(FaceDetection f) {
        double iod = Math.max(1e-6, f.interOcular());
        double score = 1;
        double eyeDy = Math.abs(f.landmarkY(1) - f.landmarkY(0)) / iod;
        if (eyeDy > 0.35) score -= 0.4;                                   // eyes far from level (after typical roll)
        double nx = f.landmarkX(2);
        if (nx < Math.min(f.landmarkX(0), f.landmarkX(1)) - 0.3 * iod || nx > Math.max(f.landmarkX(0), f.landmarkX(1)) + 0.3 * iod) score -= 0.3;
        double mouthY = (f.landmarkY(3) + f.landmarkY(4)) / 2;
        if (mouthY <= f.landmarkY(2)) score -= 0.4;                       // mouth not below nose
        if (f.landmarkY(2) <= f.eyeMidY()) score -= 0.3;                  // nose not below eyes
        return ImageOps.clamp01(score);
    }

    public FaceQuality last() { return last; }

    @Override public void reset() { last = null; }
}
