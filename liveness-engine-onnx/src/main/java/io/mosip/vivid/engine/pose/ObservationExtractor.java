package io.mosip.vivid.engine.pose;

import io.mosip.vivid.api.FaceObservation;
import io.mosip.vivid.engine.detect.FaceDetection;
import io.mosip.vivid.engine.image.FloatImage;
import io.mosip.vivid.engine.image.ImageOps;

/**
 * Builds the model-agnostic {@link FaceObservation} from a frame's grayscale raster and a five-landmark detection.
 *
 * <p>Measures and their derivation (all normalised by the inter-ocular distance, IOD):
 * <ul>
 *   <li>eye aspect ratio: height of the dark iris band in a {@code 0.5 IOD x 0.4 IOD} patch around each eye centre,
 *       divided by the patch width (open eye about 0.25-0.35, closed about 0.05-0.1, i.e. the same range as the
 *       landmark-based EAR);</li>
 *   <li>mouth aspect ratio: height of the dark oral-cavity band in a patch spanning the mouth corners, divided by the
 *       mouth width (closed about 0.05-0.15, wide open about 0.4-0.7);</li>
 *   <li>smile score: mouth width / IOD mapped linearly from 0.75 (0) to 1.10 (1);</li>
 *   <li>brow raise: distance from the eye centre to the dark eyebrow band above it, / IOD, mapped from 0.30 (0) to 0.50 (1);
 *       weak and not advertised as a supported challenge;</li>
 *   <li>gaze: horizontal centroid of the iris band inside the eye patch, vertical band position, each mapped to [-1,1].</li>
 * </ul>
 */
public final class ObservationExtractor {
    private static final double EYE_PATCH_W = 0.5, EYE_PATCH_H = 0.4;
    private static final double MIN_IOD_PX = 12;

    private ObservationExtractor() {}

    /**
     * @param gray      full-frame luma
     * @param det       detection in pixel coordinates
     * @param frameW    frame width in pixels (for normalisation)
     * @param frameH    frame height in pixels
     */
    public static FaceObservation extract(FloatImage gray, FaceDetection det, int frameW, int frameH) {
        return extract(gray, det, frameW, frameH, HeadPose.estimate(det.landmarks()));
    }

    /** As {@link #extract(FloatImage, FaceDetection, int, int)} with a pose computed by the caller (for example with a
     *  per-session {@link PitchBaseline}). */
    public static FaceObservation extract(FloatImage gray, FaceDetection det, int frameW, int frameH, HeadPose pose) {
        double[] lm = det.landmarks();
        double iod = det.interOcular();
        FaceObservation.Builder b = FaceObservation.builder()
                .box(det.x() / frameW, det.y() / frameH, det.w() / frameW, det.h() / frameH)
                .pose(pose.yaw(), pose.pitch(), pose.roll());
        double[] norm = new double[lm.length];
        for (int i = 0; i < lm.length; i += 2) { norm[i] = lm[i] / frameW; norm[i + 1] = lm[i + 1] / frameH; }
        b.landmarks(norm);

        if (iod < MIN_IOD_PX) {
            // Too small to measure expressions; keep the builder defaults (eyes open, neutral).
            return b.build();
        }
        EyeMeasure right = measureEye(gray, lm[0], lm[1], iod);   // subject's right eye (image left)
        EyeMeasure left = measureEye(gray, lm[2], lm[3], iod);    // subject's left eye (image right)
        b.eyeAspect(left.aspect, right.aspect);
        b.gaze(ImageOps.clamp01((left.gazeX + right.gazeX) / 2 * 0.5 + 0.5) * 2 - 1,
               ImageOps.clamp01((left.gazeY + right.gazeY) / 2 * 0.5 + 0.5) * 2 - 1);

        double mouthW = Math.hypot(lm[8] - lm[6], lm[9] - lm[7]);
        b.mouthAspect(measureMouth(gray, lm[6], lm[7], lm[8], lm[9], mouthW));
        b.smileScore(ImageOps.clamp01((mouthW / iod - 0.75) / 0.35));
        b.browRaiseScore((measureBrow(gray, lm[0], lm[1], iod) + measureBrow(gray, lm[2], lm[3], iod)) / 2);
        return b.build();
    }

    private static final class EyeMeasure {
        double aspect, gazeX, gazeY;
    }

    static EyeMeasure measureEye(FloatImage gray, double cx, double cy, double iod) {
        int w = Math.max(8, (int) Math.round(iod * EYE_PATCH_W));
        int h = Math.max(6, (int) Math.round(iod * EYE_PATCH_H));
        FloatImage patch = ImageOps.crop(gray, (int) Math.round(cx - w / 2.0), (int) Math.round(cy - h / 2.0), w, h, ImageOps.Padding.CLAMP);
        DarkBand band = DarkBand.find(patch);
        EyeMeasure m = new EyeMeasure();
        if (band.contrast < 8) {
            // No structure at all (flat patch): report "open" so blinks are not hallucinated, gaze centred.
            m.aspect = 0.3; m.gazeX = 0; m.gazeY = 0;
            return m;
        }
        m.aspect = clamp(band.height() / (double) w, 0.02, 0.6);
        m.gazeX = clamp((band.centroidX - (w - 1) / 2.0) / (w / 2.0) * 3.0, -1, 1);
        m.gazeY = clamp((band.centreY() - (h - 1) / 2.0) / (h / 2.0) * 3.0, -1, 1);
        return m;
    }

    static double measureMouth(FloatImage gray, double x0, double y0, double x1, double y1, double mouthW) {
        int w = Math.max(8, (int) Math.round(mouthW));
        int h = Math.max(6, (int) Math.round(mouthW * 0.9));
        double cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
        FloatImage patch = ImageOps.crop(gray, (int) Math.round(cx - w / 2.0), (int) Math.round(cy - h / 2.0), w, h, ImageOps.Padding.CLAMP);
        DarkBand band = DarkBand.find(patch);
        if (band.contrast < 8) return 0.05;
        return clamp(band.height() / (double) w, 0.0, 1.0);
    }

    static double measureBrow(FloatImage gray, double ex, double ey, double iod) {
        int w = Math.max(8, (int) Math.round(iod * 0.5));
        int top = (int) Math.round(ey - 0.6 * iod), bottom = (int) Math.round(ey - 0.12 * iod);
        int h = Math.max(4, bottom - top);
        FloatImage patch = ImageOps.crop(gray, (int) Math.round(ex - w / 2.0), top, w, h, ImageOps.Padding.CLAMP);
        DarkBand band = DarkBand.find(patch);
        if (band.contrast < 8) return 0;
        double browY = top + band.centreY();
        double dist = (ey - browY) / iod;
        return clamp((dist - 0.30) / 0.20, 0, 1);
    }

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
