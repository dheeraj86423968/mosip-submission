package io.mosip.vivid.engine.signal;

import io.mosip.vivid.api.Challenge;
import io.mosip.vivid.api.FaceObservation;
import io.mosip.vivid.api.FaceQuality;
import io.mosip.vivid.engine.detect.FaceDetection;
import io.mosip.vivid.engine.image.FloatImage;
import io.mosip.vivid.engine.image.ImageOps;
import io.mosip.vivid.engine.image.RgbImage;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything signals may need about the current frame and its primary face, with lazily computed and cached
 * derived views (luma, face crops) so that several signals share one crop. One instance per analysed frame.
 */
public final class FaceContext {
    private final RgbImage frame;
    private final FaceDetection face;
    private final long timestampNanos;
    private final long sequence;
    private final Challenge challenge;
    private FloatImage gray;
    private FaceObservation observation;
    private FaceQuality quality;
    private final Map<String, RgbImage> cropCache = new HashMap<>();
    private final Map<Integer, FloatImage> grayFaceCache = new HashMap<>();

    public FaceContext(RgbImage frame, FaceDetection face, long timestampNanos, long sequence, Challenge challenge) {
        this.frame = Objects.requireNonNull(frame);
        this.face = Objects.requireNonNull(face);
        this.timestampNanos = timestampNanos;
        this.sequence = sequence;
        this.challenge = challenge;
    }

    public RgbImage frame() { return frame; }
    public FaceDetection face() { return face; }
    public long timestampNanos() { return timestampNanos; }
    public double timestampSeconds() { return timestampNanos / 1e9; }
    public long sequence() { return sequence; }
    public Optional<Challenge> challenge() { return Optional.ofNullable(challenge); }
    public double interOcular() { return face.interOcular(); }

    /** Full-frame luma, computed once. */
    public FloatImage gray() {
        if (gray == null) gray = ImageOps.toGray(frame);
        return gray;
    }

    /** Square face crop with the box's longer side scaled by {@code scale}, resized to {@code size}; reflect-padded. */
    public RgbImage faceCrop(double scale, int size) {
        String key = scale + "/" + size;
        RgbImage c = cropCache.get(key);
        if (c == null) {
            RgbImage raw = ImageOps.cropFace(frame, face.x(), face.y(), face.w(), face.h(), scale, ImageOps.Padding.REFLECT);
            c = ImageOps.resize(raw, size, size);
            cropCache.put(key, c);
        }
        return c;
    }

    /**
     * Silent-Face crop ({@code CropImage._get_new_box} upstream): the box scaled by {@code scale} in each dimension, the
     * scale reduced so the crop fits in the frame, the crop shifted inside the frame, resized to {@code size x size}
     * without keeping the aspect ratio.
     */
    public RgbImage faceCropFit(double scale, int size) {
        String key = "fit/" + scale + "/" + size;
        RgbImage c = cropCache.get(key);
        if (c == null) {
            c = ImageOps.resize(ImageOps.cropFaceFit(frame, face.x(), face.y(), face.w(), face.h(), scale), size, size);
            cropCache.put(key, c);
        }
        return c;
    }

    /** Luma of the tight (scale 1.0) face crop at the given size. */
    public FloatImage grayFace(int size) {
        return grayFaceCache.computeIfAbsent(size, s -> ImageOps.toGray(faceCrop(1.0, s)));
    }

    public Optional<FaceObservation> observation() { return Optional.ofNullable(observation); }
    public void setObservation(FaceObservation o) { this.observation = o; }
    public Optional<FaceQuality> quality() { return Optional.ofNullable(quality); }
    public void setQuality(FaceQuality q) { this.quality = q; }
}
