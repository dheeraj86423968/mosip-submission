package io.mosip.vivid.device.mock.scenario;

import io.mosip.vivid.device.mock.render.ExpressionTimeline;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * One contiguous stretch of a {@link Scenario}: a frame source played at a fixed fps for a duration, with a
 * ground-truth label, optional faults and (for the synthetic face) a scripted expression timeline.
 *
 * <p>{@code source} is either a generator name ({@value #SOURCE_SYNTHETIC_FACE}, {@value #SOURCE_BLANK},
 * {@value #SOURCE_NOISE}) or a directory of JPEG/PNG frames played in filename order.
 */
public final class Segment {
    public static final String SOURCE_SYNTHETIC_FACE = "synthetic-face";
    public static final String SOURCE_BLANK = "blank";
    public static final String SOURCE_NOISE = "noise";

    private final String source;
    private final long durationMs;
    private final double fps;
    private final PresentationLabel label;
    private final List<Fault> faults;
    private final int faceCount;
    private final OverlayKind overlay;
    private final ExpressionTimeline timeline;
    private final boolean timelineLoops;
    private final long seed;

    private Segment(Builder b) {
        source = Objects.requireNonNull(b.source, "source");
        if (b.durationMs <= 0) throw new IllegalArgumentException("durationMs must be > 0");
        if (b.fps <= 0) throw new IllegalArgumentException("fps must be > 0");
        if (b.faceCount < 0) throw new IllegalArgumentException("faceCount must be >= 0");
        durationMs = b.durationMs; fps = b.fps;
        label = Objects.requireNonNullElse(b.label, PresentationLabel.LIVE);
        List<Fault> sorted = new java.util.ArrayList<>(b.faults);
        sorted.sort(Comparator.comparingLong(Fault::atMs));
        faults = Collections.unmodifiableList(sorted);
        faceCount = b.faceCount;
        overlay = Objects.requireNonNullElse(b.overlay, OverlayKind.NONE);
        timeline = Objects.requireNonNullElse(b.timeline, ExpressionTimeline.empty());
        timelineLoops = b.timelineLoops;
        seed = b.seed;
    }

    public static Builder builder() { return new Builder(); }

    public String source() { return source; }
    public long durationMs() { return durationMs; }
    public double fps() { return fps; }
    public PresentationLabel label() { return label; }
    /** Faults sorted by {@link Fault#atMs()}. */
    public List<Fault> faults() { return faults; }
    /** Number of faces rendered by the synthetic generator (0 = empty scene, 2 = two subjects). */
    public int faceCount() { return faceCount; }
    public OverlayKind overlay() { return overlay; }
    public ExpressionTimeline timeline() { return timeline; }
    /** If true the timeline repeats every {@link ExpressionTimeline#durationMs()} (used for video-replay attacks). */
    public boolean timelineLoops() { return timelineLoops; }
    /** Seed for deterministic noise/texture generation. */
    public long seed() { return seed; }
    /** Interval between frames in milliseconds (fractional). */
    public double frameIntervalMs() { return 1000.0 / fps; }
    /** True if {@link #source()} names a built-in generator rather than a directory. */
    public boolean isGenerator() {
        return SOURCE_SYNTHETIC_FACE.equals(source) || SOURCE_BLANK.equals(source) || SOURCE_NOISE.equals(source);
    }

    /** Builder for {@link Segment}. */
    public static final class Builder {
        private String source = SOURCE_SYNTHETIC_FACE;
        private long durationMs = 3000;
        private double fps = 15;
        private PresentationLabel label = PresentationLabel.LIVE;
        private final List<Fault> faults = new java.util.ArrayList<>();
        private int faceCount = 1;
        private OverlayKind overlay = OverlayKind.NONE;
        private ExpressionTimeline timeline;
        private boolean timelineLoops;
        private long seed = 42;

        public Builder source(String v) { source = v; return this; }
        public Builder durationMs(long v) { durationMs = v; return this; }
        public Builder fps(double v) { fps = v; return this; }
        public Builder label(PresentationLabel v) { label = v; return this; }
        public Builder fault(Fault f) { faults.add(Objects.requireNonNull(f)); return this; }
        public Builder faults(List<Fault> v) { faults.addAll(v); return this; }
        public Builder faceCount(int v) { faceCount = v; return this; }
        public Builder overlay(OverlayKind v) { overlay = v; return this; }
        public Builder timeline(ExpressionTimeline v) { timeline = v; return this; }
        public Builder timelineLoops(boolean v) { timelineLoops = v; return this; }
        public Builder seed(long v) { seed = v; return this; }
        public Segment build() { return new Segment(this); }
    }
}
