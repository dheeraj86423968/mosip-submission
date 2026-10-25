package io.mosip.vivid.engine.signal;

import io.mosip.vivid.api.Challenge;
import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.engine.image.ImageOps;
import io.mosip.vivid.engine.pose.HeadPose;
import io.mosip.vivid.engine.pose.Homography;

/**
 * S5 - motion parallax / planarity test, active only while a depth-revealing challenge (head turn, look up/down, nod)
 * is running.
 *
 * <p>The five landmarks of the first face frame after the challenge starts are the reference set. For each later
 * frame a planar motion model is fitted from the reference to the current landmarks and the RMS residual is divided
 * by the inter-ocular distance (IOD). A photo or a screen is a plane: under the weak-perspective conditions of a face
 * at arm's length its image motion is (almost exactly) affine, so the residual stays at landmark-noise level
 * (below about 1% of IOD). A real head is not planar: when it turns, the nose tip, which sits roughly half an IOD in
 * front of the eye/mouth plane, moves relative to the other four points, leaving a residual of several percent.
 *
 * <p>Five points give only four residual degrees of freedom against an affine model (six parameters) and two
 * against a homography (eight), so the affine model is used for the decision and the homography residual is kept for
 * diagnostics. The largest normalised residual seen during the challenge is mapped through
 * {@code raw = (0.02 - r) / 0.006} and Platt calibration. Confidence is the observed motion: the larger of
 * {@code |delta yaw| / 15 deg}, {@code |delta pitch| / 12 deg} and {@code |1 - IOD/IOD0| / 0.12}, so pure translation or
 * a subject who barely moved gives no verdict. After the challenge ends the final verdict is kept until
 * {@link #reset()}. Attack class {@code PRINT} (planar instruments: prints and screens alike).
 */
public final class ParallaxSignal implements Signal {
    static final double RESIDUAL_THRESHOLD = 0.02;
    static final double RESIDUAL_SCALE = 0.006;

    private final PlattCalibration calibration;
    private boolean tracking;
    private double[] reference;
    private double refYaw, refPitch, refIod;
    private double maxResidual, maxMotion, lastHomographyResidual;
    private SignalScore verdict;

    public ParallaxSignal(PlattCalibration calibration) {
        this.calibration = calibration == null ? PlattCalibration.identity() : calibration;
    }

    @Override public String id() { return Signals.PARALLAX; }

    @Override
    public void onChallenge(Challenge challenge) {
        boolean depth = challenge != null && challenge.type().revealsDepth();
        if (depth) {
            tracking = true;
            reference = null;
            maxResidual = 0;
            maxMotion = 0;
        } else {
            tracking = false;
        }
    }

    @Override
    public SignalScore score(FaceContext ctx) {
        if (!tracking) return verdict != null ? verdict : Signal.neutral(id());
        double[] lm = ctx.face().landmarks();
        HeadPose pose = HeadPose.estimate(lm);
        if (reference == null) {
            reference = lm;
            refYaw = pose.yaw(); refPitch = pose.pitch(); refIod = Math.max(1e-6, ctx.interOcular());
            return verdict != null ? verdict : Signal.neutral(id());
        }
        Measurement m = measure(reference, lm, refIod);
        double motion = Math.max(Math.max(Math.abs(pose.yaw() - refYaw) / 15.0, Math.abs(pose.pitch() - refPitch) / 12.0),
                Math.abs(1 - ctx.interOcular() / refIod) / 0.12);
        maxMotion = Math.max(maxMotion, motion);
        // Only frames with meaningful motion contribute to the residual statistic.
        if (motion >= 0.3) maxResidual = Math.max(maxResidual, m.affineResidual);
        lastHomographyResidual = m.homographyResidual;
        double p = calibration.apply((RESIDUAL_THRESHOLD - maxResidual) / RESIDUAL_SCALE);
        double confidence = ImageOps.clamp01(maxMotion);
        verdict = new SignalScore(id(), p, confidence, false, p > 0.5 ? Signals.ATTACK_PRINT : null);
        return verdict;
    }

    /** Planar-fit residuals between two landmark sets, normalised by {@code iod}. */
    public static Measurement measure(double[] from, double[] to, double iod) {
        double[] affine = Homography.fitAffine(from, to);
        double affineRes = Homography.affineResidualRms(affine, from, to) / iod;
        double homRes;
        try {
            homRes = Homography.fit(from, to).residualRms(from, to) / iod;
        } catch (RuntimeException e) {
            homRes = Double.NaN; // degenerate configuration
        }
        return new Measurement(affineRes, homRes);
    }

    /** Residuals of one comparison. */
    public static final class Measurement {
        public final double affineResidual, homographyResidual;
        Measurement(double a, double h) { affineResidual = a; homographyResidual = h; }
    }

    public boolean isTracking() { return tracking; }
    public double maxResidual() { return maxResidual; }
    public double lastHomographyResidual() { return lastHomographyResidual; }

    @Override public void reset() {
        tracking = false; reference = null; verdict = null; maxResidual = 0; maxMotion = 0; lastHomographyResidual = 0;
    }
}
