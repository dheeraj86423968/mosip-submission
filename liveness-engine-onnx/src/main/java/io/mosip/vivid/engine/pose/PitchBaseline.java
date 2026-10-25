package io.mosip.vivid.engine.pose;

import java.util.Arrays;

/**
 * Per-session neutral pitch calibration.
 *
 * <p>Five landmarks fix pitch only up to the subject's own nose position between the eye line and the mouth line
 * and depth (see {@link HeadPose#noseDrop(double[])}). The nose's drop below the eye line varies from person to person
 * and with camera height by more than a nod, so an absolute pitch from population constants can read a neutral head
 * as tilted by 20 degrees. This class learns the subject's neutral nose drop while they face the camera before the
 * first challenge (the passive phase), and from then on pitch is reported relative to it by
 * {@link HeadPose#estimateRelative(double[], double, double)}.
 *
 * <p>Life cycle: {@link #observe(double[])} every single-face frame; {@link #freeze()} when the first challenge is
 * issued; {@link #reset()} when the session restarts. Only frames with a near-frontal yaw and roll are used. At
 * freeze time the median of the most recent {@value #WINDOW} usable frames becomes the baseline, provided there are
 * at least {@value #MIN_FRAMES}, they agree (median absolute deviation at most {@value #MAX_SPREAD}) and the result
 * is anatomically plausible; otherwise the session keeps the population constant. Freezing at the challenge
 * boundary means the reported pitch never shifts in the middle of a challenge. The baseline only moves the zero of
 * pitch: challenges are still detected as movements away from, and back to, the pose held when they are issued.
 *
 * <p>Not thread-safe; owned by one analyzer.
 */
public final class PitchBaseline {
    /** Frames kept for the median. */
    public static final int WINDOW = 30;
    /** Frames needed before a baseline is accepted. */
    public static final int MIN_FRAMES = 5;
    /** Largest median absolute deviation of the nose drop accepted (about 6 degrees of pitch). */
    public static final double MAX_SPREAD = 0.05;
    /** Accepted range of neutral nose drops, in inter-ocular distances (population about 0.7). */
    public static final double MIN_DROP = 0.35, MAX_DROP = 1.1;
    /** Frames with larger |yaw| or |roll| (degrees) are not used. */
    public static final double MAX_YAW = 15, MAX_ROLL = 15;

    private final double[] window = new double[WINDOW];       // nose drops
    private final double[] fractions = new double[WINDOW];    // nose fractions
    private int count;      // total usable frames seen (ring index = count % WINDOW)
    private boolean frozen;
    private double neutral = Double.NaN, neutralFraction = Double.NaN;

    /** Offers one frame's five landmarks (pixels, YuNet order). Ignored after {@link #freeze()}. */
    public void observe(double[] landmarks) {
        if (frozen) return;
        HeadPose pose = HeadPose.estimate(landmarks);
        if (Math.abs(pose.yaw()) > MAX_YAW || Math.abs(pose.roll()) > MAX_ROLL) return;
        double t = HeadPose.noseDrop(landmarks), f = HeadPose.noseFraction(landmarks);
        if (Double.isNaN(t) || Double.isNaN(f)) return;
        fractions[count % WINDOW] = f;
        window[count % WINDOW] = t;
        count++;
    }

    /** Ends calibration: fixes the baseline from the frames seen so far, or decides to keep the population constant. */
    public void freeze() {
        if (frozen) return;
        frozen = true;
        int n = Math.min(count, WINDOW);
        if (n < MIN_FRAMES) return;
        double[] v = Arrays.copyOf(window, n);
        double median = median(v);
        double[] dev = new double[n];
        for (int i = 0; i < n; i++) dev[i] = Math.abs(v[i] - median);
        if (median(dev) > MAX_SPREAD || median < MIN_DROP || median > MAX_DROP) return;
        neutral = median;
        neutralFraction = median(Arrays.copyOf(fractions, n));
    }

    /** True once {@link #freeze()} has run. */
    public boolean isFrozen() { return frozen; }

    /** The subject's neutral nose drop, or NaN while calibrating or when calibration was rejected. */
    public double neutralNoseDrop() { return neutral; }

    /** The subject's neutral nose fraction, or NaN while calibrating or when calibration was rejected. */
    public double neutralNoseFraction() { return neutralFraction; }

    /** Pose of a frame, with pitch relative to the subject's neutral when a baseline is fixed. */
    public HeadPose estimate(double[] landmarks) {
        return Double.isNaN(neutral) ? HeadPose.estimate(landmarks) : HeadPose.estimateRelative(landmarks, neutralFraction, neutral);
    }

    /** Forgets everything; calibration starts again. */
    public void reset() {
        count = 0;
        frozen = false;
        neutral = Double.NaN;
        neutralFraction = Double.NaN;
    }

    private static double median(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        int n = s.length;
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
    }
}
