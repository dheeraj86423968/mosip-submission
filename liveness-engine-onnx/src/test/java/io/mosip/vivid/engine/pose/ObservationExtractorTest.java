package io.mosip.vivid.engine.pose;

import io.mosip.vivid.api.FaceObservation;
import io.mosip.vivid.engine.detect.FaceDetection;
import io.mosip.vivid.engine.image.FloatImage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationExtractorTest {
    /** Grey frame with dark ellipses at the eyes (height eyeH) and a dark mouth gap (height mouthH). */
    static FloatImage drawn(double eyeH, double mouthH) {
        int w = 320, h = 240;
        float[] d = new float[w * h];
        java.util.Arrays.fill(d, 170f);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            for (double ex : new double[] {140, 180}) {
                double nx = (x - ex) / 8.0, ny = (y - 110) / Math.max(0.5, eyeH / 2);
                if (nx * nx + ny * ny <= 1) d[y * w + x] = 30f;
            }
            double mx = (x - 160) / 14.0, my = (y - 155) / Math.max(0.5, mouthH / 2);
            if (mx * mx + my * my <= 1) d[y * w + x] = 25f;
            if (Math.abs(y - 92) <= 1 && (Math.abs(x - 140) < 9 || Math.abs(x - 180) < 9)) d[y * w + x] = 60f; // brows
        }
        return new FloatImage(w, h, d);
    }

    static final FaceDetection FACE = new FaceDetection(110, 70, 100, 110, 0.95,
            new double[] {140, 110, 180, 110, 160, 135, 146, 155, 174, 155});

    @Test
    void eyeAspectDropsWhenEyesClose() {
        FaceObservation open = ObservationExtractor.extract(drawn(8, 2), FACE, 320, 240);
        FaceObservation closed = ObservationExtractor.extract(drawn(1, 2), FACE, 320, 240);
        assertThat(open.eyeAspect()).isGreaterThan(0.25);
        assertThat(closed.eyeAspect()).isLessThan(0.15);
        assertThat(open.eyeAspect()).isGreaterThan(2 * closed.eyeAspect());
    }

    @Test
    void mouthAspectRisesWhenMouthOpens() {
        FaceObservation shut = ObservationExtractor.extract(drawn(8, 2), FACE, 320, 240);
        FaceObservation open = ObservationExtractor.extract(drawn(8, 16), FACE, 320, 240);
        assertThat(open.mouthAspect()).isGreaterThan(shut.mouthAspect() + 0.25);
    }

    @Test
    void geometryAndNormalisation() {
        FaceObservation o = ObservationExtractor.extract(drawn(8, 2), FACE, 320, 240);
        assertThat(o.boxX()).isEqualTo(110 / 320.0);
        assertThat(o.landmarks()).hasSize(10);
        assertThat(o.landmarks()[0]).isEqualTo(140 / 320.0);
        assertThat(Math.abs(o.yaw())).isLessThan(1);
        assertThat(Math.abs(o.roll())).isLessThan(1);
        assertThat(o.smileScore()).isBetween(0.0, 0.3);
        assertThat(o.gazeX()).isBetween(-0.5, 0.5);
        // a flat patch reports open eyes rather than a hallucinated blink
        FaceObservation flat = ObservationExtractor.extract(new FloatImage(320, 240), FACE, 320, 240);
        assertThat(flat.eyeAspect()).isEqualTo(0.3);
    }

    @Test
    void wideMouthRaisesSmileScore() {
        FaceDetection smiling = new FaceDetection(110, 70, 100, 110, 0.95, new double[] {140, 110, 180, 110, 160, 135, 139, 152, 181, 152});
        assertThat(ObservationExtractor.extract(drawn(8, 2), smiling, 320, 240).smileScore()).isGreaterThan(0.7);
    }
}
