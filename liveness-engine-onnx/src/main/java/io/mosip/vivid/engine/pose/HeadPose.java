package io.mosip.vivid.engine.pose;

/**
 * Geometric head-pose estimate from five landmarks (two eye centres, nose tip, two mouth corners), without solvePnP.
 *
 * <ul>
 *   <li><b>roll</b>: angle of the inter-ocular line.</li>
 *   <li><b>yaw</b>: after removing roll, the nose tip's horizontal offset from the eye midpoint, relative to the
 *       inter-ocular distance (IOD). Modelling the nose tip as protruding {@code NOSE_DEPTH_RATIO * IOD} in front of the
 *       eye plane gives {@code yaw = atan(offset / (NOSE_DEPTH_RATIO * IOD))}. Positive = subject turned to their left
 *       (nose moves towards the camera's right), as required by {@code FaceObservation}.</li>
 *   <li><b>pitch</b>: the nose tip's vertical position as a fraction t of the eye-to-mouth distance is
 *       {@code NOSE_FRACTION} for a frontal face; {@code t = t0 - (d / EM) tan(pitch)} where d is nose depth and EM the
 *       eye-mouth distance, so {@code pitch = atan((t0 - t) / NOSE_DEPTH_OVER_EYE_MOUTH)}. Positive = looking up.
 *       This absolute form is used until the subject's neutral is known; then
 *       {@link #estimateRelative(double[], double, double)} measures it against the subject's own neutral (see
 *       {@link PitchBaseline}).</li>
 * </ul>
 * Anthropometric constants are population averages. For yaw the bias is small, because faces are left-right
 * symmetric. For pitch it is not: with plausible variation in nose length and depth and in camera height (a laptop
 * camera below the eyes reads as "looking up"), a neutral head reads anywhere from about -25 to +20 degrees with the
 * population t0 (see {@code HeadPoseTest}). Pitch should therefore be measured against the subject's own neutral
 * pose, and head movements detected as changes relative to a baseline rather than as absolute angles.
 */
public final class HeadPose {
    /** Nose tip protrusion in front of the eye plane, as a fraction of IOD (about 32 mm / 63 mm). */
    public static final double NOSE_DEPTH_RATIO = 0.5;
    /** Nose tip position between eye line (0) and mouth line (1) for a frontal face. */
    public static final double NOSE_FRACTION = 0.6;
    /** Nose depth divided by eye-to-mouth distance (about 32 mm / 70 mm). */
    public static final double NOSE_DEPTH_OVER_EYE_MOUTH = 0.45;
    /** Mouth-referenced relative pitch may exceed the eye-referenced one by at most this factor ... */
    public static final double PITCH_SUPPORT_GAIN = 4;
    /** ... plus this many degrees (see {@link #estimateRelative(double[], double, double)}). */
    public static final double PITCH_SUPPORT_SLACK = 2;

    private final double yaw, pitch, roll;

    private HeadPose(double yaw, double pitch, double roll) { this.yaw = yaw; this.pitch = pitch; this.roll = roll; }

    public double yaw() { return yaw; }
    public double pitch() { return pitch; }
    public double roll() { return roll; }

    /**
     * @param lm five landmarks in pixels: subject-right eye, subject-left eye, nose tip, subject-right mouth corner,
     *           subject-left mouth corner (YuNet order), as {@code x0,y0,...,x4,y4}
     */
    public static HeadPose estimate(double[] lm) {
        if (lm.length < 10) throw new IllegalArgumentException("need 5 landmarks");
        double ex0 = lm[0], ey0 = lm[1], ex1 = lm[2], ey1 = lm[3];
        double roll = Math.toDegrees(Math.atan2(ey1 - ey0, ex1 - ex0));
        double iod = Math.hypot(ex1 - ex0, ey1 - ey0);
        if (iod < 1e-6) return new HeadPose(0, 0, 0);
        double cx = (ex0 + ex1) / 2, cy = (ey0 + ey1) / 2;
        // rotate all points by -roll about the eye midpoint
        double cos = Math.cos(Math.toRadians(-roll)), sin = Math.sin(Math.toRadians(-roll));
        double[] r = new double[10];
        for (int i = 0; i < 5; i++) {
            double dx = lm[2 * i] - cx, dy = lm[2 * i + 1] - cy;
            r[2 * i] = dx * cos - dy * sin;
            r[2 * i + 1] = dx * sin + dy * cos;
        }
        double noseX = r[4], noseY = r[5];
        double mouthY = (r[7] + r[9]) / 2;
        double yaw = Math.toDegrees(Math.atan2(noseX, NOSE_DEPTH_RATIO * iod));
        double eyeMouth = mouthY; // eye midpoint is at y = 0 after rotation
        double pitch = 0;
        if (eyeMouth > 1e-6) {
            double t = noseY / eyeMouth;
            pitch = Math.toDegrees(Math.atan((NOSE_FRACTION - t) / NOSE_DEPTH_OVER_EYE_MOUTH));
        }
        return new HeadPose(yaw, pitch, roll);
    }

    /**
     * As {@link #estimate(double[])}, but pitch is measured against the subject's own neutral pose, given as the
     * {@link #noseFraction(double[]) nose fraction} and {@link #noseDrop(double[]) nose drop} measured while they
     * faced the camera (see {@link PitchBaseline}). Yaw and roll are computed exactly as in {@link #estimate(double[])}.
     *
     * <p>Two estimates are combined:
     * <ul>
     *   <li>mouth-referenced: {@code pm = atan((t0 - t) / NOSE_DEPTH_OVER_EYE_MOUTH)} with the subject's own t0. The
     *       eye-mouth span foreshortens together with the nose offset, so this stays monotonic and well scaled over
     *       +/-30 degrees for every anatomy tested. But the mouth corners also move by themselves: a broad smile lifts
     *       them by 5-10 mm, which alone reads as 7-10 degrees "down".</li>
     *   <li>eye-referenced: the nose drop below the eye line in inter-ocular distances, {@code s = s0 cos p - k sin p},
     *       solved as {@code pe = acos(s / hypot(s0, k)) - atan2(k, s0)}. Expressions do not move it, but its
     *       sensitivity depends on the subject's unknown nose depth and it saturates for steep downward tilts.</li>
     * </ul>
     * The reported pitch is {@code pm}, limited in magnitude to {@code PITCH_SUPPORT_GAIN * |pe| +
     * PITCH_SUPPORT_SLACK} and set to {@code pe} when the two disagree in sign: a real pitch change must move the nose
     * relative to the eyes, not only the mouth relative to the nose. Non-finite or non-positive baselines fall back
     * to {@link #estimate(double[])}.
     */
    public static HeadPose estimateRelative(double[] lm, double neutralNoseFraction, double neutralNoseDrop) {
        HeadPose absolute = estimate(lm);
        double t = noseFraction(lm), s = noseDrop(lm);
        if (!(neutralNoseFraction > 0) || !(neutralNoseDrop > 0) || Double.isNaN(t) || Double.isNaN(s)) return absolute;
        double pm = Math.toDegrees(Math.atan((neutralNoseFraction - t) / NOSE_DEPTH_OVER_EYE_MOUTH));
        double k = NOSE_DEPTH_RATIO;
        double pe = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, s / Math.hypot(neutralNoseDrop, k)))) - Math.atan2(k, neutralNoseDrop));
        double pitch;
        if (pm * pe < 0) {
            pitch = pe;
        } else {
            double cap = PITCH_SUPPORT_GAIN * Math.abs(pe) + PITCH_SUPPORT_SLACK;
            pitch = Math.signum(pm) * Math.min(Math.abs(pm), cap);
        }
        return new HeadPose(absolute.yaw, pitch, absolute.roll);
    }

    /**
     * The nose tip's position between the eye line (0) and the mouth line (1), measured perpendicular to the eye line
     * (roll removed). NaN when the eye-mouth distance is degenerate.
     */
    public static double noseFraction(double[] lm) {
        if (lm.length < 10) throw new IllegalArgumentException("need 5 landmarks");
        double ex0 = lm[0], ey0 = lm[1], ex1 = lm[2], ey1 = lm[3];
        double iod = Math.hypot(ex1 - ex0, ey1 - ey0);
        if (iod < 1e-6) return Double.NaN;
        double ux = (ex1 - ex0) / iod, uy = (ey1 - ey0) / iod;
        double cx = (ex0 + ex1) / 2, cy = (ey0 + ey1) / 2;
        double noseY = -(lm[4] - cx) * uy + (lm[5] - cy) * ux;
        double mouthY = (-(lm[6] - cx) * uy + (lm[7] - cy) * ux - (lm[8] - cx) * uy + (lm[9] - cy) * ux) / 2;
        return mouthY > 1e-6 ? noseY / mouthY : Double.NaN;
    }

    /**
     * The nose tip's distance below the eye line (perpendicular to it, so roll is removed) divided by the
     * inter-ocular distance corrected for yaw ({@code IOD_image / cos(yaw)}). It changes with pitch but not with
     * scale, roll, yaw (to first order) or mouth expressions. NaN for degenerate landmarks.
     */
    public static double noseDrop(double[] lm) {
        if (lm.length < 10) throw new IllegalArgumentException("need 5 landmarks");
        double ex0 = lm[0], ey0 = lm[1], ex1 = lm[2], ey1 = lm[3];
        double iod = Math.hypot(ex1 - ex0, ey1 - ey0);
        if (iod < 1e-6) return Double.NaN;
        double ux = (ex1 - ex0) / iod, uy = (ey1 - ey0) / iod;    // eye-line direction
        double cx = (ex0 + ex1) / 2, cy = (ey0 + ey1) / 2;
        double along = (lm[4] - cx) * ux + (lm[5] - cy) * uy;      // horizontal nose offset (yaw)
        double drop = -(lm[4] - cx) * uy + (lm[5] - cy) * ux;      // vertical nose offset (pitch)
        double cosYaw = Math.cos(Math.atan2(along, NOSE_DEPTH_RATIO * iod));
        return drop / (iod / Math.max(0.3, cosYaw));
    }

    @Override public String toString() { return String.format("yaw=%.1f pitch=%.1f roll=%.1f", yaw, pitch, roll); }
}
