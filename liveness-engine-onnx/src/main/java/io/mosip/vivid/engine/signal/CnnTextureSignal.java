package io.mosip.vivid.engine.signal;

import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.engine.image.ImageOps;
import io.mosip.vivid.engine.image.RgbImage;
import io.mosip.vivid.engine.onnx.OrtModel;

import java.util.List;
import java.util.Objects;

/**
 * S1 - CNN texture anti-spoofing. Runs one or more MiniFASNet-family classifiers on square face crops
 * (e.g. 2.7x and 4.0x box scale for the original Silent-Face models, 1.5x at 128 px for the bundled
 * MiniFASNetV2-SE export), takes the softmax, and reports {@code 1 - P(real)} averaged over the models.
 *
 * <p>The raw score handed to Platt calibration is the attack logit {@code ln(P(attack) / P(real))}; the identity
 * calibration therefore reproduces the network's own probability. Confidence ramps with face size because the
 * crops are up-sampled below about 40 px inter-ocular distance. Attack class is {@code UNKNOWN}: the binary
 * models do not distinguish print from screen.
 */
public final class CnnTextureSignal implements Signal {
    /** One classifier and its preprocessing recipe. */
    public static final class Model {
        private final OrtModel model;
        private final int inputSize;
        private final double cropScale;
        private final boolean bgr;
        private final int realClassIndex;
        private final float inputScale;
        private final boolean flip;
        private final boolean fitCrop;

        /** Model fed pixel values divided by 255 on a square crop, without a flipped view. */
        public Model(OrtModel model, int inputSize, double cropScale, boolean bgr, int realClassIndex) {
            this(model, inputSize, cropScale, bgr, realClassIndex, 1f / 255f, false, false);
        }

        /**
         * @param inputSize      square input side (80 for Silent-Face models, 128 for the bundled export)
         * @param cropScale      face-box scale of the square crop (2.7 / 4.0 for Silent-Face, 1.5 for the bundled export)
         * @param bgr            feed BGR (Silent-Face, trained with OpenCV) instead of RGB
         * @param realClassIndex index of the "real" class in the output vector (1 for Silent-Face, 0 for the bundled export)
         * @param inputScale     multiplier for 0..255 pixel values (1/255 for the bundled export, 1 for Silent-Face)
         * @param flip           also score the horizontally mirrored crop and average the two probabilities
         * @param fitCrop        upstream Silent-Face crop ({@link FaceContext#faceCropFit}) instead of a square crop
         */
        public Model(OrtModel model, int inputSize, double cropScale, boolean bgr, int realClassIndex, float inputScale,
                     boolean flip, boolean fitCrop) {
            this.model = Objects.requireNonNull(model); this.inputSize = inputSize; this.cropScale = cropScale;
            this.bgr = bgr; this.realClassIndex = realClassIndex; this.inputScale = inputScale; this.flip = flip; this.fitCrop = fitCrop;
        }

        public OrtModel model() { return model; }
        public int inputSize() { return inputSize; }
        public double cropScale() { return cropScale; }
        public boolean bgr() { return bgr; }
        public int realClassIndex() { return realClassIndex; }
        public float inputScale() { return inputScale; }
        public boolean flip() { return flip; }
        public boolean fitCrop() { return fitCrop; }

        /** This model's input crop for the frame's face. */
        public RgbImage crop(FaceContext ctx) {
            return fitCrop ? ctx.faceCropFit(cropScale, inputSize) : ctx.faceCrop(cropScale, inputSize);
        }

        /** Probability of "real" for a face crop of {@link #inputSize()}; with {@link #flip()} the mean over the crop and its mirror. */
        public double probabilityReal(RgbImage crop) {
            double p = probabilityRealOnce(crop);
            return flip ? (p + probabilityRealOnce(ImageOps.flipHorizontal(crop))) / 2 : p;
        }

        private double probabilityRealOnce(RgbImage crop) {
            float[] tensor = ImageOps.toChw(crop, bgr, inputScale, null, null);
            float[] logits = model.runFirst(tensor, new long[] {1, 3, inputSize, inputSize});
            if (realClassIndex >= logits.length) {
                throw new IllegalStateException("model " + model.path() + " has " + logits.length + " outputs but realClassIndex=" + realClassIndex);
            }
            double[] p = softmax(logits);
            return p[realClassIndex];
        }
    }

    private static final double MIN_IOD = 16, FULL_IOD = 40;

    private final List<Model> models;
    private final PlattCalibration calibration;
    private double lastProbabilityReal = Double.NaN;

    public CnnTextureSignal(List<Model> models, PlattCalibration calibration) {
        if (models == null || models.isEmpty()) throw new IllegalArgumentException("at least one model required");
        this.models = List.copyOf(models);
        this.calibration = calibration == null ? PlattCalibration.identity() : calibration;
    }

    @Override public String id() { return Signals.CNN_TEXTURE; }

    @Override
    public SignalScore score(FaceContext ctx) {
        double sum = 0;
        for (Model m : models) {
            sum += m.probabilityReal(m.crop(ctx));
        }
        double pReal = sum / models.size();
        lastProbabilityReal = pReal;
        double eps = 1e-6;
        double rawAttackLogit = Math.log((1 - pReal + eps) / (pReal + eps));
        double p = calibration.apply(rawAttackLogit);
        double confidence = ImageOps.clamp01((ctx.interOcular() - MIN_IOD) / (FULL_IOD - MIN_IOD));
        boolean veto = p >= calibration.threshold() && calibration.threshold() > 0.5 && confidence >= 0.99 && p >= 0.995;
        return new SignalScore(id(), p, confidence, veto, p > 0.5 ? Signals.ATTACK_UNKNOWN : null);
    }

    /** Last uncalibrated P(real), for diagnostics. */
    public double lastProbabilityReal() { return lastProbabilityReal; }

    @Override public void reset() { lastProbabilityReal = Double.NaN; }

    static double[] softmax(float[] logits) {
        double max = Double.NEGATIVE_INFINITY;
        for (float l : logits) max = Math.max(max, l);
        double[] e = new double[logits.length];
        double s = 0;
        for (int i = 0; i < logits.length; i++) { e[i] = Math.exp(logits[i] - max); s += e[i]; }
        for (int i = 0; i < e.length; i++) e[i] /= s;
        return e;
    }
}
