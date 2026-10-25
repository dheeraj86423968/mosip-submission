package io.mosip.vivid.core.action;

/**
 * Tunable thresholds of every {@link ActionDetector}, with defaults calibrated for 10-30 fps landmark streams.
 * Immutable; build variants with {@link #builder()} or {@link #toBuilder()}.
 *
 * <p>Angles are degrees, ratios dimensionless, durations milliseconds, positions normalised to the frame.</p>
 */
public final class ActionThresholds {

    private final double blinkCloseEar;
    private final double blinkOpenEar;
    private final long blinkMinClosedMillis;
    private final long blinkMaxClosedMillis;
    private final long blinkTwiceWindowMillis;
    private final double smileScore;
    private final double smileMouthNarrowingRatio;
    private final long smileHoldMillis;
    private final double mouthOpenAspect;
    private final long mouthOpenHoldMillis;
    private final double browRaiseScore;
    private final long browRaiseHoldMillis;
    private final double turnYawDegrees;
    private final double lookPitchDegrees;
    private final double nodPitchDegrees;
    private final double gazeThreshold;
    private final double baselineMaxDegrees;
    private final double returnDegrees;
    private final long holdStillMillis;
    private final double holdStillCentreTolerance;
    private final double holdStillPoseToleranceDegrees;
    private final boolean requireNeutralStart;
    private final boolean rejectUnpromptedActions;

    private ActionThresholds(Builder b) {
        blinkCloseEar = b.blinkCloseEar;
        blinkOpenEar = b.blinkOpenEar;
        blinkMinClosedMillis = b.blinkMinClosedMillis;
        blinkMaxClosedMillis = b.blinkMaxClosedMillis;
        blinkTwiceWindowMillis = b.blinkTwiceWindowMillis;
        smileScore = b.smileScore;
        smileMouthNarrowingRatio = b.smileMouthNarrowingRatio;
        smileHoldMillis = b.smileHoldMillis;
        mouthOpenAspect = b.mouthOpenAspect;
        mouthOpenHoldMillis = b.mouthOpenHoldMillis;
        browRaiseScore = b.browRaiseScore;
        browRaiseHoldMillis = b.browRaiseHoldMillis;
        turnYawDegrees = b.turnYawDegrees;
        lookPitchDegrees = b.lookPitchDegrees;
        nodPitchDegrees = b.nodPitchDegrees;
        gazeThreshold = b.gazeThreshold;
        baselineMaxDegrees = b.baselineMaxDegrees;
        returnDegrees = b.returnDegrees;
        holdStillMillis = b.holdStillMillis;
        holdStillCentreTolerance = b.holdStillCentreTolerance;
        holdStillPoseToleranceDegrees = b.holdStillPoseToleranceDegrees;
        requireNeutralStart = b.requireNeutralStart;
        rejectUnpromptedActions = b.rejectUnpromptedActions;
        if (blinkCloseEar >= blinkOpenEar) {
            throw new IllegalArgumentException("blinkCloseEar must be below blinkOpenEar (hysteresis)");
        }
        if (blinkMinClosedMillis < 0 || blinkMaxClosedMillis <= blinkMinClosedMillis) {
            throw new IllegalArgumentException("blink closed-duration window is invalid");
        }
        if (returnDegrees >= Math.min(turnYawDegrees, Math.min(lookPitchDegrees, nodPitchDegrees))) {
            throw new IllegalArgumentException("returnDegrees must be below every motion threshold");
        }
        if (holdStillMillis <= 0 || smileHoldMillis <= 0 || mouthOpenHoldMillis <= 0 || browRaiseHoldMillis <= 0) {
            throw new IllegalArgumentException("hold durations must be positive");
        }
    }

    /** The defaults. */
    public static ActionThresholds defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A builder pre-populated with this instance's values. */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.blinkCloseEar = blinkCloseEar; b.blinkOpenEar = blinkOpenEar; b.blinkMinClosedMillis = blinkMinClosedMillis;
        b.blinkMaxClosedMillis = blinkMaxClosedMillis; b.blinkTwiceWindowMillis = blinkTwiceWindowMillis;
        b.smileScore = smileScore; b.smileMouthNarrowingRatio = smileMouthNarrowingRatio; b.smileHoldMillis = smileHoldMillis;
        b.mouthOpenAspect = mouthOpenAspect; b.mouthOpenHoldMillis = mouthOpenHoldMillis;
        b.browRaiseScore = browRaiseScore; b.browRaiseHoldMillis = browRaiseHoldMillis;
        b.turnYawDegrees = turnYawDegrees; b.lookPitchDegrees = lookPitchDegrees; b.nodPitchDegrees = nodPitchDegrees;
        b.gazeThreshold = gazeThreshold; b.baselineMaxDegrees = baselineMaxDegrees; b.returnDegrees = returnDegrees;
        b.holdStillMillis = holdStillMillis; b.holdStillCentreTolerance = holdStillCentreTolerance;
        b.holdStillPoseToleranceDegrees = holdStillPoseToleranceDegrees; b.requireNeutralStart = requireNeutralStart;
        b.rejectUnpromptedActions = rejectUnpromptedActions;
        return b;
    }

    /** Eye aspect ratio below which the eyes count as closed. */
    public double blinkCloseEar() { return blinkCloseEar; }
    /** Eye aspect ratio above which the eyes count as open (hysteresis). */
    public double blinkOpenEar() { return blinkOpenEar; }
    /**
     * Shortest closure accepted as a blink. Below the ~100 ms physiological minimum on purpose: closure duration is
     * quantised by the frame interval (67 ms at 15 fps), so a real blink seen on a single frame measures one interval.
     */
    public long blinkMinClosedMillis() { return blinkMinClosedMillis; }
    /** Longest closure accepted as a blink; longer is "eyes shut", not a blink. */
    public long blinkMaxClosedMillis() { return blinkMaxClosedMillis; }
    /** Maximum time between the two blinks of BLINK_TWICE. */
    public long blinkTwiceWindowMillis() { return blinkTwiceWindowMillis; }
    /** Minimum {@code smileScore} (or smile blendshape) for a smiling frame. */
    public double smileScore() { return smileScore; }
    /** Fallback when no smile score is produced: mouth aspect must shrink to this fraction of the neutral baseline. */
    public double smileMouthNarrowingRatio() { return smileMouthNarrowingRatio; }
    public long smileHoldMillis() { return smileHoldMillis; }
    public double mouthOpenAspect() { return mouthOpenAspect; }
    public long mouthOpenHoldMillis() { return mouthOpenHoldMillis; }
    public double browRaiseScore() { return browRaiseScore; }
    public long browRaiseHoldMillis() { return browRaiseHoldMillis; }
    /** Yaw excursion from baseline for TURN_LEFT / TURN_RIGHT. */
    public double turnYawDegrees() { return turnYawDegrees; }
    /** Pitch excursion from baseline for LOOK_UP / LOOK_DOWN. */
    public double lookPitchDegrees() { return lookPitchDegrees; }
    /** Pitch excursion from baseline for NOD. */
    public double nodPitchDegrees() { return nodPitchDegrees; }
    /** Alternative gaze magnitude accepted for LOOK_UP / LOOK_DOWN. */
    public double gazeThreshold() { return gazeThreshold; }
    /** Maximum |angle| for a frame to establish the neutral baseline. */
    public double baselineMaxDegrees() { return baselineMaxDegrees; }
    /** Distance from baseline that counts as "returned". */
    public double returnDegrees() { return returnDegrees; }
    public long holdStillMillis() { return holdStillMillis; }
    /** Max face-centre displacement (normalised) while holding still. */
    public double holdStillCentreTolerance() { return holdStillCentreTolerance; }
    /** Max yaw/pitch change while holding still. */
    public double holdStillPoseToleranceDegrees() { return holdStillPoseToleranceDegrees; }
    /** Require a neutral observation after the prompt before an action can start (anti-replay). */
    public boolean requireNeutralStart() { return requireNeutralStart; }
    /**
     * Fail a challenge when the subject completes a different, distinctive pool action before the requested one.
     * A recording that happens to contain the requested gesture usually also contains others; a person following
     * the prompt does not. Blinks are exempt because people blink spontaneously.
     */
    public boolean rejectUnpromptedActions() { return rejectUnpromptedActions; }

    /** Mutable builder. */
    public static final class Builder {
        private double blinkCloseEar = 0.20;
        private double blinkOpenEar = 0.25;
        private long blinkMinClosedMillis = 60;
        private long blinkMaxClosedMillis = 500;
        private long blinkTwiceWindowMillis = 2500;
        private double smileScore = 0.6;
        private double smileMouthNarrowingRatio = 0.75;
        private long smileHoldMillis = 300;
        private double mouthOpenAspect = 0.5;
        private long mouthOpenHoldMillis = 300;
        private double browRaiseScore = 0.5;
        private long browRaiseHoldMillis = 300;
        private double turnYawDegrees = 20;
        private double lookPitchDegrees = 15;
        private double nodPitchDegrees = 10;
        private double gazeThreshold = 0.5;
        private double baselineMaxDegrees = 10;
        private double returnDegrees = 8;
        private long holdStillMillis = 1500;
        private double holdStillCentreTolerance = 0.03;
        private double holdStillPoseToleranceDegrees = 5;
        private boolean requireNeutralStart = true;
        private boolean rejectUnpromptedActions = true;

        private Builder() {}

        public Builder blinkCloseEar(double v) { blinkCloseEar = v; return this; }
        public Builder blinkOpenEar(double v) { blinkOpenEar = v; return this; }
        public Builder blinkMinClosedMillis(long v) { blinkMinClosedMillis = v; return this; }
        public Builder blinkMaxClosedMillis(long v) { blinkMaxClosedMillis = v; return this; }
        public Builder blinkTwiceWindowMillis(long v) { blinkTwiceWindowMillis = v; return this; }
        public Builder smileScore(double v) { smileScore = v; return this; }
        public Builder smileMouthNarrowingRatio(double v) { smileMouthNarrowingRatio = v; return this; }
        public Builder smileHoldMillis(long v) { smileHoldMillis = v; return this; }
        public Builder mouthOpenAspect(double v) { mouthOpenAspect = v; return this; }
        public Builder mouthOpenHoldMillis(long v) { mouthOpenHoldMillis = v; return this; }
        public Builder browRaiseScore(double v) { browRaiseScore = v; return this; }
        public Builder browRaiseHoldMillis(long v) { browRaiseHoldMillis = v; return this; }
        public Builder turnYawDegrees(double v) { turnYawDegrees = v; return this; }
        public Builder lookPitchDegrees(double v) { lookPitchDegrees = v; return this; }
        public Builder nodPitchDegrees(double v) { nodPitchDegrees = v; return this; }
        public Builder gazeThreshold(double v) { gazeThreshold = v; return this; }
        public Builder baselineMaxDegrees(double v) { baselineMaxDegrees = v; return this; }
        public Builder returnDegrees(double v) { returnDegrees = v; return this; }
        public Builder holdStillMillis(long v) { holdStillMillis = v; return this; }
        public Builder holdStillCentreTolerance(double v) { holdStillCentreTolerance = v; return this; }
        public Builder holdStillPoseToleranceDegrees(double v) { holdStillPoseToleranceDegrees = v; return this; }
        public Builder requireNeutralStart(boolean v) { requireNeutralStart = v; return this; }
        public Builder rejectUnpromptedActions(boolean v) { rejectUnpromptedActions = v; return this; }
        public ActionThresholds build() { return new ActionThresholds(this); }
    }
}
