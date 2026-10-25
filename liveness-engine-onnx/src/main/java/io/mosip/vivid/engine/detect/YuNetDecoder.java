package io.mosip.vivid.engine.detect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Decodes the raw YuNet (2023mar) output heads into detections, mirroring OpenCV's {@code FaceDetectorYN}:
 * for each stride s in {8, 16, 32} the model emits per-cell {@code cls} and {@code obj} scores, a box
 * {@code (dx, dy, log w, log h)} and five keypoints as offsets from the cell. The score is {@code sqrt(cls * obj)},
 * {@code cx = (col + dx) * s}, {@code w = exp(log w) * s} and keypoints {@code (col + kx) * s}.
 */
public final class YuNetDecoder {
    public static final int[] STRIDES = {8, 16, 32};

    private YuNetDecoder() {}

    /**
     * @param outputs    map from output name (cls_8, obj_8, bbox_8, kps_8, ...) to the flattened tensor
     * @param inputSize  side of the square model input (640 for the fixed-shape export)
     * @param threshold  minimum score to keep
     * @return raw candidates in model-input pixel coordinates, before NMS
     */
    public static List<FaceDetection> decode(Map<String, float[]> outputs, int inputSize, double threshold) {
        List<FaceDetection> out = new ArrayList<>();
        for (int s : STRIDES) {
            float[] cls = outputs.get("cls_" + s), obj = outputs.get("obj_" + s), bbox = outputs.get("bbox_" + s), kps = outputs.get("kps_" + s);
            if (cls == null || obj == null || bbox == null || kps == null) {
                throw new IllegalArgumentException("missing YuNet outputs for stride " + s);
            }
            int cols = inputSize / s, rows = inputSize / s;
            int cells = cols * rows;
            if (cls.length < cells || bbox.length < cells * 4 || kps.length < cells * 10) {
                throw new IllegalArgumentException("YuNet output size mismatch for stride " + s + ": expected " + cells + " cells");
            }
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    int idx = r * cols + c;
                    double clsScore = clamp01(cls[idx]), objScore = clamp01(obj[idx]);
                    double score = Math.sqrt(clsScore * objScore);
                    if (score < threshold) continue;
                    double cx = (c + bbox[idx * 4]) * s, cy = (r + bbox[idx * 4 + 1]) * s;
                    double w = Math.exp(bbox[idx * 4 + 2]) * s, h = Math.exp(bbox[idx * 4 + 3]) * s;
                    double[] lm = new double[10];
                    for (int k = 0; k < 5; k++) {
                        lm[2 * k] = (kps[idx * 10 + 2 * k] + c) * s;
                        lm[2 * k + 1] = (kps[idx * 10 + 2 * k + 1] + r) * s;
                    }
                    out.add(new FaceDetection(cx - w / 2, cy - h / 2, w, h, score, lm));
                }
            }
        }
        return out;
    }

    private static double clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
}
