package io.mosip.vivid.engine.onnx;

import io.mosip.vivid.api.Challenge;
import io.mosip.vivid.api.FaceObservation;
import io.mosip.vivid.api.FaceQuality;
import io.mosip.vivid.api.FrameAnalysis;
import io.mosip.vivid.api.FrameAnalyzer;
import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.engine.detect.FaceDetection;
import io.mosip.vivid.engine.detect.FaceDetector;
import io.mosip.vivid.engine.embed.FaceEmbedder;
import io.mosip.vivid.engine.image.FrameDecoder;
import io.mosip.vivid.engine.image.RgbImage;
import io.mosip.vivid.engine.pose.ObservationExtractor;
import io.mosip.vivid.engine.pose.PitchBaseline;
import io.mosip.vivid.engine.signal.FaceContext;
import io.mosip.vivid.engine.signal.FramePlaneSignal;
import io.mosip.vivid.engine.signal.QualitySignal;
import io.mosip.vivid.engine.signal.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Per-session analyzer. Pipeline per frame: decode to RGB, detect faces, pick the primary face, assess quality,
 * extract the {@link FaceObservation} (pitch relative to the subject's neutral, learned before the first challenge
 * by a {@link PitchBaseline}), run every enabled {@link Signal}, compute the identity embedding every
 * K face frames, and return a {@link FrameAnalysis} with the measured processing time.
 *
 * <p>When zero or several faces are found, {@code face} is null, signals are told the face was lost (windowed
 * signals reset their gap logic) and only face-independent signals (injection) report. A failing signal is logged and
 * replaced by a neutral score so one broken model never blocks the session. Frames that cannot be decoded throw
 * {@link io.mosip.vivid.engine.image.InvalidFrameException}. Not thread-safe; one feeding thread per analyzer.
 */
public final class OnnxFrameAnalyzer implements FrameAnalyzer {
    private static final Logger LOG = LoggerFactory.getLogger(OnnxFrameAnalyzer.class);

    private final FaceDetector detector;
    private final FaceEmbedder embedder;          // nullable
    private final int embedEvery;
    private final List<Signal> signals;
    private final QualitySignal quality;
    private final PitchBaseline pitchBaseline = new PitchBaseline();
    private Challenge challenge;
    private long faceFrames;
    private final java.util.Map<String, Long> stageNanos = new java.util.LinkedHashMap<>();

    OnnxFrameAnalyzer(FaceDetector detector, FaceEmbedder embedder, int embedEvery, List<Signal> signals, double detectorScoreThreshold) {
        this.quality = new QualitySignal(detectorScoreThreshold);
        this.detector = Objects.requireNonNull(detector);
        this.embedder = embedder;
        this.embedEvery = Math.max(1, embedEvery);
        this.signals = List.copyOf(signals);
    }

    @Override
    public FrameAnalysis analyze(FaceFrame frame) {
        long start = System.nanoTime();
        stageNanos.clear();
        RgbImage rgb = FrameDecoder.toRgb(frame);
        long t = mark("decode", start);
        List<FaceDetection> faces = detector.detect(rgb);
        t = mark("detect", t);
        if (faces.size() != 1) {
            for (Signal s : signals) s.onFaceLost(frame.timestampNanos());
            List<SignalScore> scores = new ArrayList<>();
            for (Signal s : signals) {
                try {
                    s.scoreWithoutFace(frame.timestampNanos()).ifPresent(scores::add);
                } catch (RuntimeException e) {
                    LOG.warn("signal {} failed without face: {}", s.id(), e.toString());
                }
            }
            FaceQuality q = FaceQuality.noFace();
            if (!faces.isEmpty()) {
                FaceContext largest = new FaceContext(rgb, primary(faces), frame.timestampNanos(), frame.sequence(), challenge);
                q = quality.assess(largest, faces.size());
            }
            return new FrameAnalysis(frame.sequence(), frame.timestampNanos(), q, null, scores, null, System.nanoTime() - start);
        }

        FaceDetection face = faces.get(0);
        FaceContext ctx = new FaceContext(rgb, face, frame.timestampNanos(), frame.sequence(), challenge);
        pitchBaseline.observe(face.landmarks());   // learns the subject's neutral pitch until the first challenge
        FaceObservation obs = ObservationExtractor.extract(ctx.gray(), face, rgb.width(), rgb.height(), pitchBaseline.estimate(face.landmarks()));
        ctx.setObservation(obs);
        t = mark("observation", t);
        FaceQuality q = quality.assess(ctx, 1);
        ctx.setQuality(q);
        t = mark("quality", t);

        List<SignalScore> scores = new ArrayList<>(signals.size());
        for (Signal s : signals) {
            try {
                if (s instanceof FramePlaneSignal) ((FramePlaneSignal) s).score(ctx, frame).ifPresent(scores::add); // S8: abstains without the plane
                else scores.add(s.score(ctx));
            } catch (RuntimeException e) {
                LOG.warn("signal {} failed on frame {}: {}", s.id(), frame.sequence(), e.toString());
                scores.add(Signal.neutral(s.id()));
            }
            t = mark(s.id(), t);
        }

        float[] embedding = null;
        if (embedder != null && faceFrames % embedEvery == 0) {
            try {
                embedding = embedder.embed(rgb, face);
            } catch (RuntimeException e) {
                LOG.warn("embedding failed on frame {}: {}", frame.sequence(), e.toString());
            }
        }
        mark("embedding", t);
        faceFrames++;
        return new FrameAnalysis(frame.sequence(), frame.timestampNanos(), q, obs, scores, embedding, System.nanoTime() - start);
    }

    private long mark(String stage, long since) {
        long now = System.nanoTime();
        stageNanos.put(stage, now - since);
        return now;
    }

    /** Wall time per pipeline stage of the last analysed frame (decode, detect, observation, quality, each signal id, embedding). */
    public java.util.Map<String, Long> lastStageNanos() { return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(stageNanos)); }

    private static FaceDetection primary(List<FaceDetection> faces) {
        FaceDetection best = faces.get(0);
        for (FaceDetection f : faces) if (f.w() * f.h() > best.w() * best.h()) best = f;
        return best;
    }

    @Override
    public void onChallenge(Challenge challenge) {
        this.challenge = challenge;
        if (challenge != null) pitchBaseline.freeze();
        for (Signal s : signals) s.onChallenge(challenge);
    }

    @Override
    public void reset() {
        challenge = null;
        faceFrames = 0;
        pitchBaseline.reset();
        quality.reset();
        for (Signal s : signals) s.reset();
    }

    /** The signal instances of this analyzer, for diagnostics. */
    public List<Signal> signals() { return signals; }

    /** Looks a signal up by id. */
    public <T extends Signal> Optional<T> signal(String id, Class<T> type) {
        for (Signal s : signals) if (s.id().equals(id) && type.isInstance(s)) return Optional.of(type.cast(s));
        return Optional.empty();
    }
}
