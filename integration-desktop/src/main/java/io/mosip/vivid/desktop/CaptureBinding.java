package io.mosip.vivid.desktop;

import io.mosip.vivid.api.FrameAnalysis;
import io.mosip.vivid.api.FrameAnalyzer;
import io.mosip.vivid.api.LivenessEngine;
import io.mosip.vivid.api.LivenessResult;
import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.PixelFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Binds the signed {@code rcapture} image to the liveness session that preceded it (identity continuity across the
 * stream/capture boundary). The stream frames the engine scored are unsigned; the ISO image returned by
 * {@code rcapture} is signed by the device but was never scored. Without this step a replay could pass liveness on
 * the stream while the device captures a different face.
 * <p>
 * Steps: decode {@code bioValue} (ISO 19794-5 or raw JPEG) to a {@link FaceFrame}, run one {@link FrameAnalyzer}
 * pass to get an embedding, and compare with {@link LivenessResult#anchorEmbedding()} using the injected distance
 * function (liveness-core's {@code IdentityContinuity.compare(float[],float[])} in production), so this module has
 * no compile-time dependency on liveness-core.
 */
public final class CaptureBinding {
    private static final Logger LOG = LoggerFactory.getLogger(CaptureBinding.class);

    public enum Status { BOUND, IDENTITY_DRIFT, NO_ANCHOR, NO_FACE, MULTIPLE_FACES, UNSUPPORTED_IMAGE, PAD_VETO, ENGINE_ERROR, NOT_REQUIRED, SESSION_NOT_LIVE }

    public static final class Outcome {
        private final Status status; private final double distance; private final String detail;
        Outcome(Status status, double distance, String detail) { this.status = status; this.distance = distance; this.detail = detail; }
        public Status status() { return status; }
        /** True only when the capture may be stored / matched. */
        public boolean accepted() { return status == Status.BOUND || status == Status.NOT_REQUIRED; }
        /** Cosine (or injected) distance between anchor and capture embedding; NaN when not computed. */
        public double distance() { return distance; }
        public String detail() { return detail; }
        /** Audit event for this outcome, if any. */
        public Optional<DesktopAuditEvent> auditEvent() {
            switch (status) {
                case BOUND: return Optional.of(DesktopAuditEvent.CAPTURE_BOUND);
                case IDENTITY_DRIFT: return Optional.of(DesktopAuditEvent.IDENTITY_DRIFT);
                case PAD_VETO: return Optional.of(DesktopAuditEvent.PAD_DETECTED);
                default: return Optional.empty();
            }
        }
        @Override public String toString() { return status + (Double.isNaN(distance) ? "" : String.format(Locale.ROOT, "(d=%.3f)", distance)) + (detail == null ? "" : " " + detail); }
    }

    private final LivenessEngine engine;
    private final BiFunction<float[], float[], Double> distance;
    private final double driftThreshold;
    private final boolean required;
    private final Function<byte[], byte[]> isoToImage;

    /**
     * @param engine         the same engine the session used (embeddings must come from the same model)
     * @param distance       e.g. {@code IdentityContinuity::compare}; lower = more similar
     * @param driftThreshold distance above which the identities differ ({@code LivenessPolicy.identityDriftThreshold()})
     * @param required       {@code LivenessPolicy.requireIdentityContinuity()}; when false a missing anchor/embedding is tolerated
     */
    public CaptureBinding(LivenessEngine engine, BiFunction<float[], float[], Double> distance, double driftThreshold, boolean required) {
        this(engine, distance, driftThreshold, required, null);
    }

    private CaptureBinding(LivenessEngine engine, BiFunction<float[], float[], Double> distance, double driftThreshold, boolean required, Function<byte[], byte[]> isoToImage) {
        this.engine = Objects.requireNonNull(engine); this.distance = Objects.requireNonNull(distance);
        this.driftThreshold = driftThreshold; this.required = required; this.isoToImage = isoToImage;
    }

    /**
     * Supply an external ISO-to-image decoder (the Registration Client passes
     * {@code iso -> FaceDecoder.convertFaceISOToImageBytes(new ConvertRequestDto("ISO19794_5_2011", iso))}, which also
     * transcodes JPEG 2000). The built-in parser is still used as a fallback.
     */
    public CaptureBinding withIsoDecoder(Function<byte[], byte[]> decoder) {
        return new CaptureBinding(engine, distance, driftThreshold, required, decoder);
    }

    public Outcome bind(LivenessResult liveness, byte[] bioValue) {
        Objects.requireNonNull(liveness, "liveness");
        if (!liveness.accepted()) return new Outcome(Status.SESSION_NOT_LIVE, Double.NaN, "session decision " + liveness.decision());
        if (liveness.failure().filter(f -> f == io.mosip.vivid.api.FailureReason.POLICY_DISABLED).isPresent()) {
            return new Outcome(Status.NOT_REQUIRED, Double.NaN, "liveness disabled by policy");
        }
        Optional<float[]> anchor = liveness.anchorEmbedding();
        if (anchor.isEmpty()) {
            return required ? new Outcome(Status.NO_ANCHOR, Double.NaN, "session produced no anchor embedding")
                            : new Outcome(Status.NOT_REQUIRED, Double.NaN, "no anchor, continuity not required");
        }
        Optional<FaceFrame> frame = toFrame(bioValue);
        if (frame.isEmpty()) return new Outcome(Status.UNSUPPORTED_IMAGE, Double.NaN, "bioValue is not a decodable ISO 19794-5 / JPEG image");

        FrameAnalysis analysis;
        try (FrameAnalyzer analyzer = engine.newAnalyzer()) {
            analysis = analyzer.analyze(frame.get());
        } catch (RuntimeException e) {
            LOG.error("capture analysis failed", e);
            return new Outcome(Status.ENGINE_ERROR, Double.NaN, e.toString());
        }
        int faces = analysis.quality().faceCount();
        if (faces == 0) return new Outcome(Status.NO_FACE, Double.NaN, "no face in captured image");
        if (faces > 1) return new Outcome(Status.MULTIPLE_FACES, Double.NaN, faces + " faces in captured image");
        for (SignalScore s : analysis.signals()) {
            if (s.hardVeto()) return new Outcome(Status.PAD_VETO, Double.NaN, "veto by " + s.signalId() + " class " + s.attackClass());
        }
        Optional<float[]> emb = analysis.embedding();
        if (emb.isEmpty()) {
            return required ? new Outcome(Status.NO_ANCHOR, Double.NaN, "engine produced no embedding for the capture")
                            : new Outcome(Status.NOT_REQUIRED, Double.NaN, "no capture embedding, continuity not required");
        }
        double d;
        try { d = distance.apply(anchor.get(), emb.get()); }
        catch (RuntimeException e) { return new Outcome(Status.ENGINE_ERROR, Double.NaN, "distance function failed: " + e); }
        if (Double.isNaN(d)) return new Outcome(Status.ENGINE_ERROR, d, "distance is NaN");
        return d <= driftThreshold ? new Outcome(Status.BOUND, d, "distance " + d + " <= " + driftThreshold)
                                   : new Outcome(Status.IDENTITY_DRIFT, d, "distance " + d + " > " + driftThreshold);
    }

    /** Decode the capture to a frame: RGB888 when the JDK can decode it (JPEG/PNG), else MJPEG for the engine to decode. */
    Optional<FaceFrame> toFrame(byte[] bioValue) {
        if (bioValue == null || bioValue.length == 0) return Optional.empty();
        byte[] image = null;
        if (isoToImage != null) {
            try { image = isoToImage.apply(bioValue); } catch (RuntimeException e) { LOG.warn("external ISO decoder failed, using built-in parser: {}", e.toString()); }
        }
        Iso19794Face.ImageRecord rec = null;
        if (image == null || image.length == 0) {
            Optional<Iso19794Face.ImageRecord> r = Iso19794Face.extract(bioValue);
            if (r.isEmpty()) return Optional.empty();
            rec = r.get(); image = rec.data;
        }
        try {
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(image));
            if (bi != null) {
                int w = bi.getWidth(), h = bi.getHeight();
                byte[] rgb = new byte[w * h * 3];
                int i = 0;
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    int p = bi.getRGB(x, y);
                    rgb[i++] = (byte) (p >> 16); rgb[i++] = (byte) (p >> 8); rgb[i++] = (byte) p;
                }
                return Optional.of(FaceFrame.builder().sequence(-1).timestampNanos(System.nanoTime()).width(w).height(h).format(PixelFormat.RGB888).pixels(rgb).build());
            }
        } catch (IOException | RuntimeException e) {
            LOG.debug("ImageIO could not decode capture ({}), passing compressed bytes to the engine", e.toString());
        }
        if (io.mosip.vivid.desktop.stream.Jpeg.isJpeg(image)) {
            int[] d = io.mosip.vivid.desktop.stream.Jpeg.dimensions(image);
            if (d.length == 2) return Optional.of(FaceFrame.builder().sequence(-1).timestampNanos(System.nanoTime()).width(d[0]).height(d[1]).format(PixelFormat.MJPEG).pixels(image).build());
        }
        if (rec != null && rec.type == Iso19794Face.ImageType.JPEG2000) {
            LOG.warn("capture image is JPEG 2000 and no external decoder is configured; cannot bind");
        }
        return Optional.empty();
    }
}
