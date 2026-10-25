package io.mosip.vivid.engine.embed;

import io.mosip.vivid.engine.detect.FaceDetection;
import io.mosip.vivid.engine.image.RgbImage;

/** Produces an L2-normalised identity vector for a detected face. Thread-safe. */
public interface FaceEmbedder {
    /** Embedding dimension (128 for SFace, 512 for ArcFace-style models). */
    int dimension();

    float[] embed(RgbImage frame, FaceDetection face);

    /** Cosine similarity of two normalised embeddings. */
    static double cosine(float[] a, float[] b) {
        if (a.length != b.length) throw new IllegalArgumentException("dimension mismatch");
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }
}
