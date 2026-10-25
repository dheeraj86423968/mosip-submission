package io.mosip.vivid.engine.detect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Greedy non-maximum suppression on {@link FaceDetection} boxes. */
public final class Nms {
    private Nms() {}

    /**
     * @param candidates   any order
     * @param iouThreshold boxes overlapping a kept box by more than this are dropped
     * @param topK         maximum number of results
     * @return kept detections, highest score first
     */
    public static List<FaceDetection> apply(List<FaceDetection> candidates, double iouThreshold, int topK) {
        List<FaceDetection> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(FaceDetection::score).reversed());
        List<FaceDetection> kept = new ArrayList<>();
        for (FaceDetection c : sorted) {
            if (kept.size() >= topK) break;
            boolean suppressed = false;
            for (FaceDetection k : kept) {
                if (k.iou(c) > iouThreshold) { suppressed = true; break; }
            }
            if (!suppressed) kept.add(c);
        }
        return kept;
    }
}
