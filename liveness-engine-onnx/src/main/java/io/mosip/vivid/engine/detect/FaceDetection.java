package io.mosip.vivid.engine.detect;

/**
 * One detected face in frame pixel coordinates. Landmarks follow the YuNet order:
 * 0 subject-right eye (image left), 1 subject-left eye (image right), 2 nose tip,
 * 3 subject-right mouth corner, 4 subject-left mouth corner; stored as {@code x0,y0,...,x4,y4}.
 */
public final class FaceDetection {
    private final double x, y, w, h;
    private final double score;
    private final double[] landmarks;

    public FaceDetection(double x, double y, double w, double h, double score, double[] landmarks) {
        if (landmarks == null || landmarks.length < 10) throw new IllegalArgumentException("need 5 landmarks");
        this.x = x; this.y = y; this.w = w; this.h = h; this.score = score; this.landmarks = landmarks.clone();
    }

    public double x() { return x; }
    public double y() { return y; }
    public double w() { return w; }
    public double h() { return h; }
    public double centerX() { return x + w / 2; }
    public double centerY() { return y + h / 2; }
    public double score() { return score; }
    public double[] landmarks() { return landmarks.clone(); }
    public double landmarkX(int i) { return landmarks[2 * i]; }
    public double landmarkY(int i) { return landmarks[2 * i + 1]; }
    /** Distance between the two eye centres in pixels. */
    public double interOcular() { return Math.hypot(landmarks[2] - landmarks[0], landmarks[3] - landmarks[1]); }
    public double eyeMidX() { return (landmarks[0] + landmarks[2]) / 2; }
    public double eyeMidY() { return (landmarks[1] + landmarks[3]) / 2; }

    /** Intersection-over-union with another box. */
    public double iou(FaceDetection o) {
        double ix = Math.max(0, Math.min(x + w, o.x + o.w) - Math.max(x, o.x));
        double iy = Math.max(0, Math.min(y + h, o.y + o.h) - Math.max(y, o.y));
        double inter = ix * iy;
        double union = w * h + o.w * o.h - inter;
        return union <= 0 ? 0 : inter / union;
    }

    /** Returns a copy with all coordinates multiplied by {@code s}. */
    public FaceDetection scaled(double s) {
        double[] l = new double[landmarks.length];
        for (int i = 0; i < l.length; i++) l[i] = landmarks[i] * s;
        return new FaceDetection(x * s, y * s, w * s, h * s, score, l);
    }

    @Override public String toString() {
        return String.format("Face[%.0f,%.0f %.0fx%.0f score=%.2f iod=%.1f]", x, y, w, h, score, interOcular());
    }
}
