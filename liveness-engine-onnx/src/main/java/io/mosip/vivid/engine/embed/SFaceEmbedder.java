package io.mosip.vivid.engine.embed;

import io.mosip.vivid.engine.detect.FaceDetection;
import io.mosip.vivid.engine.image.ImageOps;
import io.mosip.vivid.engine.image.RgbImage;
import io.mosip.vivid.engine.onnx.OrtModel;
import io.mosip.vivid.engine.pose.SimilarityTransform;

/**
 * SFace / ArcFace-style embedder: aligns the face with a similarity transform that maps the five landmarks onto
 * the canonical 112x112 template used by InsightFace and OpenCV's {@code FaceRecognizerSF}, feeds the aligned
 * crop as RGB in 0..255 (OpenCV uses {@code blobFromImage(..., swapRB=true)} with scale 1 and no mean) and
 * L2-normalises the output. Any 112x112 model with the same preprocessing (e.g. MobileFaceNet exports that take
 * raw 0..255 input) can be dropped in; models trained with (x-127.5)/128 scaling need {@link #SFaceEmbedder(OrtModel, boolean, float, float)}.
 */
public final class SFaceEmbedder implements FaceEmbedder {
    /** Canonical landmark positions (px) in the 112x112 template, same order as {@link FaceDetection}. */
    public static final double[] TEMPLATE_112 = {
            38.2946, 51.6963, 73.5318, 51.5014, 56.0252, 71.7366, 41.5493, 92.3655, 70.7299, 92.2041};
    public static final int SIZE = 112;

    private final OrtModel model;
    private final boolean bgr;
    private final float mean;
    private final float std;
    private final int dimension;

    /** OpenCV SFace convention: RGB, no normalisation. */
    public SFaceEmbedder(OrtModel model) { this(model, false, 0f, 1f); }

    /**
     * @param bgr  feed channels as BGR
     * @param mean subtracted from every channel value (0..255 scale)
     * @param std  divisor applied after the subtraction
     */
    public SFaceEmbedder(OrtModel model, boolean bgr, float mean, float std) {
        this.model = model; this.bgr = bgr; this.mean = mean; this.std = std;
        long[] out = model.outputShape(0);
        this.dimension = (int) out[out.length - 1];
    }

    @Override public int dimension() { return dimension; }

    @Override
    public float[] embed(RgbImage frame, FaceDetection face) {
        RgbImage aligned = align(frame, face);
        float[] tensor = ImageOps.toChw(aligned, bgr, 1f, new float[] {mean, mean, mean}, new float[] {std, std, std});
        float[] raw = model.runFirst(tensor, new long[] {1, 3, SIZE, SIZE});
        double n = 0;
        for (float v : raw) n += (double) v * v;
        float inv = n > 0 ? (float) (1 / Math.sqrt(n)) : 0f;
        float[] out = new float[raw.length];
        for (int i = 0; i < raw.length; i++) out[i] = raw[i] * inv;
        return out;
    }

    /** Warps the face into the 112x112 template; exposed for tests and the diagnostic view. */
    public static RgbImage align(RgbImage frame, FaceDetection face) {
        double[] m = SimilarityTransform.fit(face.landmarks(), TEMPLATE_112);
        return ImageOps.warpAffine(frame, m, SIZE, SIZE);
    }
}
