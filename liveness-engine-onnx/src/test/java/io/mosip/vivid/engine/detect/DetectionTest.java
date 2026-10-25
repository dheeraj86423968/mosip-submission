package io.mosip.vivid.engine.detect;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class DetectionTest {
    static FaceDetection box(double x, double y, double w, double h, double score) {
        return new FaceDetection(x, y, w, h, score, new double[10]);
    }

    @Test
    void iou() {
        assertThat(box(0, 0, 10, 10, 1).iou(box(0, 0, 10, 10, 1))).isEqualTo(1);
        assertThat(box(0, 0, 10, 10, 1).iou(box(5, 0, 10, 10, 1))).isCloseTo(50.0 / 150, offset(1e-12));
        assertThat(box(0, 0, 10, 10, 1).iou(box(20, 20, 5, 5, 1))).isZero();
    }

    @Test
    void nmsKeepsBestOfOverlappingAndAllDisjoint() {
        List<FaceDetection> kept = Nms.apply(List.of(
                box(0, 0, 10, 10, 0.8), box(1, 1, 10, 10, 0.95), box(100, 100, 10, 10, 0.7), box(2, 0, 10, 10, 0.9)), 0.3, 10);
        assertThat(kept).extracting(FaceDetection::score).containsExactly(0.95, 0.7);
        assertThat(Nms.apply(List.of(box(0, 0, 1, 1, 0.5), box(5, 5, 1, 1, 0.6)), 0.3, 1)).hasSize(1);
        assertThat(Nms.apply(List.of(), 0.3, 5)).isEmpty();
    }

    /** Builds empty YuNet heads for a 64 px input and plants one face in a stride-16 cell. */
    @Test
    void yunetDecodingFollowsOpenCvFormulas() {
        int size = 64;
        Map<String, float[]> out = new HashMap<>();
        for (int s : YuNetDecoder.STRIDES) {
            int cells = (size / s) * (size / s);
            out.put("cls_" + s, new float[cells]);
            out.put("obj_" + s, new float[cells]);
            out.put("bbox_" + s, new float[cells * 4]);
            out.put("kps_" + s, new float[cells * 10]);
        }
        int cols = size / 16, r = 2, c = 1, idx = r * cols + c;
        out.get("cls_16")[idx] = 0.81f;
        out.get("obj_16")[idx] = 1.2f; // clamped to 1
        float[] bbox = out.get("bbox_16");
        bbox[idx * 4] = 0.5f; bbox[idx * 4 + 1] = 0.25f; bbox[idx * 4 + 2] = (float) Math.log(2); bbox[idx * 4 + 3] = 0f;
        float[] kps = out.get("kps_16");
        for (int k = 0; k < 5; k++) { kps[idx * 10 + 2 * k] = 0.1f * k; kps[idx * 10 + 2 * k + 1] = -0.1f * k; }
        // a below-threshold candidate in the stride-8 head
        out.get("cls_8")[3] = 0.2f; out.get("obj_8")[3] = 0.2f;

        List<FaceDetection> d = YuNetDecoder.decode(out, size, 0.5);
        assertThat(d).hasSize(1);
        FaceDetection f = d.get(0);
        assertThat(f.score()).isCloseTo(0.9, offset(1e-6));
        double cx = (c + 0.5) * 16, cy = (r + 0.25) * 16, w = 2 * 16, h = 16;
        assertThat(f.x()).isCloseTo(cx - w / 2, offset(1e-4));
        assertThat(f.y()).isCloseTo(cy - h / 2, offset(1e-4));
        assertThat(f.w()).isCloseTo(w, offset(1e-4));
        assertThat(f.h()).isCloseTo(h, offset(1e-4));
        assertThat(f.landmarkX(3)).isCloseTo((0.3 + c) * 16, offset(1e-4));
        assertThat(f.landmarkY(3)).isCloseTo((-0.3 + r) * 16, offset(1e-4));
        assertThat(YuNetDecoder.decode(out, size, 0.1)).hasSize(2);

        out.remove("kps_32");
        assertThatThrownBy(() -> YuNetDecoder.decode(out, size, 0.5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scalingAndLandmarkHelpers() {
        double[] lm = {10, 20, 40, 20, 25, 35, 15, 45, 35, 45};
        FaceDetection f = new FaceDetection(0, 0, 50, 60, 0.9, lm).scaled(2);
        assertThat(f.interOcular()).isCloseTo(60, offset(1e-9));
        assertThat(f.eyeMidX()).isCloseTo(50, offset(1e-9));
        assertThat(f.w()).isEqualTo(100);
    }

    @Test
    void letterboxPutsBgrPlanesTopLeft() {
        byte[] d = {(byte) 10, (byte) 20, (byte) 30};
        float[] t = YuNetDetector.letterboxBgr(new io.mosip.vivid.engine.image.RgbImage(1, 1, d), 4);
        assertThat(t[0]).isEqualTo(30f);
        assertThat(t[16]).isEqualTo(20f);
        assertThat(t[32]).isEqualTo(10f);
        assertThat(t[1]).isZero();
    }
}
