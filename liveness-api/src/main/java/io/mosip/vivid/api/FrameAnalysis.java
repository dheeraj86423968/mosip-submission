package io.mosip.vivid.api;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Everything an engine extracts from a single frame. Produced by {@link FrameAnalyzer#analyze}. */
public final class FrameAnalysis {
    private final long frameSequence;
    private final long frameTimestampNanos;
    private final FaceQuality quality;
    private final FaceObservation face;            // null when quality.faceCount != 1
    private final List<SignalScore> signals;
    private final float[] embedding;               // nullable; identity embedding of primary face
    private final long processingNanos;

    public FrameAnalysis(long frameSequence, long frameTimestampNanos, FaceQuality quality, FaceObservation face,
                         List<SignalScore> signals, float[] embedding, long processingNanos) {
        this.frameSequence = frameSequence; this.frameTimestampNanos = frameTimestampNanos;
        this.quality = Objects.requireNonNull(quality); this.face = face;
        this.signals = signals == null ? List.of() : Collections.unmodifiableList(signals);
        this.embedding = embedding == null ? null : embedding.clone();
        this.processingNanos = processingNanos;
    }
    public long frameSequence() { return frameSequence; }
    public long frameTimestampNanos() { return frameTimestampNanos; }
    public FaceQuality quality() { return quality; }
    public Optional<FaceObservation> face() { return Optional.ofNullable(face); }
    public List<SignalScore> signals() { return signals; }
    public Optional<float[]> embedding() { return embedding == null ? Optional.empty() : Optional.of(embedding.clone()); }
    public long processingNanos() { return processingNanos; }
}
