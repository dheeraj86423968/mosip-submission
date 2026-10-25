package io.mosip.vivid.engine.detect;

import io.mosip.vivid.engine.image.ImageOps;
import io.mosip.vivid.engine.image.RgbImage;
import io.mosip.vivid.engine.onnx.OrtModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * YuNet face detector (OpenCV Zoo, {@code face_detection_yunet_2023mar.onnx}). The export has a fixed
 * {@code 1x3x640x640} input, so each frame is scaled to fit and placed top-left on a black square canvas
 * (letterboxing); detections are mapped back by the inverse scale. Input is BGR, raw 0..255, as in OpenCV.
 */
public final class YuNetDetector implements FaceDetector {
    public static final int DEFAULT_INPUT_SIZE = 640;

    private final OrtModel model;
    private final int inputSize;
    private final double scoreThreshold;
    private final double nmsThreshold;
    private final int topK;

    public YuNetDetector(OrtModel model, double scoreThreshold, double nmsThreshold, int topK) {
        this.model = model;
        long[] shape = model.inputShape();
        this.inputSize = shape.length == 4 && shape[2] > 0 ? (int) shape[2] : DEFAULT_INPUT_SIZE;
        this.scoreThreshold = scoreThreshold;
        this.nmsThreshold = nmsThreshold;
        this.topK = topK;
    }

    public int inputSize() { return inputSize; }

    @Override
    public List<FaceDetection> detect(RgbImage frame) {
        double scale = Math.min((double) inputSize / frame.width(), (double) inputSize / frame.height());
        int w = Math.max(1, (int) Math.round(frame.width() * scale)), h = Math.max(1, (int) Math.round(frame.height() * scale));
        RgbImage resized = ImageOps.resize(frame, w, h);
        float[] tensor = letterboxBgr(resized, inputSize);
        Map<String, float[]> outputs = model.run(tensor, new long[] {1, 3, inputSize, inputSize});
        List<FaceDetection> raw = YuNetDecoder.decode(outputs, inputSize, scoreThreshold);
        List<FaceDetection> kept = Nms.apply(raw, nmsThreshold, topK);
        List<FaceDetection> out = new ArrayList<>(kept.size());
        for (FaceDetection d : kept) out.add(d.scaled(1.0 / scale));
        return out;
    }

    /** Planar BGR tensor of a square canvas with the image at the top-left and zeros elsewhere. */
    static float[] letterboxBgr(RgbImage img, int size) {
        float[] t = new float[3 * size * size];
        int plane = size * size;
        byte[] d = img.data();
        int w = img.width(), h = img.height();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 3, o = y * size + x;
                t[o] = d[i + 2] & 0xFF;             // B
                t[plane + o] = d[i + 1] & 0xFF;     // G
                t[2 * plane + o] = d[i] & 0xFF;     // R
            }
        }
        return t;
    }
}
