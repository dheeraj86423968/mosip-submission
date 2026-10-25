package io.mosip.vivid.device.mock.scenario;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A scripted device session: an ordered list of {@link Segment}s played back to back, optionally looping.
 * Scenarios are normally loaded from JSON via {@link Scenarios} but can be built programmatically for tests.
 *
 * <p>Scenario time starts at 0 at the first frame of the first segment and runs continuously across segments
 * (and loops), so a fault "at 1500 ms" in the second segment fires 1500 ms after that segment begins.
 */
public final class Scenario {
    private final String name;
    private final String description;
    private final int width;
    private final int height;
    private final boolean loop;
    private final List<Segment> segments;
    private final Path baseDir;
    private final SensorConfig sensors;   // nullable: RGB-only device

    private Scenario(Builder b) {
        name = Objects.requireNonNull(b.name, "name");
        description = Objects.requireNonNullElse(b.description, "");
        if (b.width <= 0 || b.height <= 0) throw new IllegalArgumentException("width/height must be > 0");
        if (b.segments.isEmpty()) throw new IllegalArgumentException("scenario needs at least one segment");
        width = b.width; height = b.height; loop = b.loop;
        segments = Collections.unmodifiableList(List.copyOf(b.segments));
        baseDir = b.baseDir;
        sensors = b.sensors != null && b.sensors.any() ? b.sensors : null;
    }

    public static Builder builder(String name) { return new Builder().name(name); }

    public String name() { return name; }
    public String description() { return description; }
    public int width() { return width; }
    public int height() { return height; }
    /** True if playback restarts from the first segment after the last one ends. */
    public boolean loop() { return loop; }
    public List<Segment> segments() { return segments; }
    /** Directory against which relative frame-directory sources are resolved; null when loaded from the classpath. */
    public Path baseDir() { return baseDir; }
    /** Optional L1-style depth / IR planes; empty (the default) for an RGB-only device. */
    public java.util.Optional<SensorConfig> sensors() { return java.util.Optional.ofNullable(sensors); }

    /** Sum of all segment durations (one pass, ignoring {@link #loop()}). */
    public long totalDurationMs() {
        long t = 0;
        for (Segment s : segments) t += s.durationMs();
        return t;
    }

    /** Highest fps of any segment; used to size capability reports. */
    public double maxFps() {
        double f = 0;
        for (Segment s : segments) f = Math.max(f, s.fps());
        return f;
    }

    /** Label of the first segment, a convenient summary for single-label scenarios. */
    public PresentationLabel primaryLabel() { return segments.get(0).label(); }

    /** Builder for {@link Scenario}. */
    public static final class Builder {
        private String name; private String description;
        private int width = 640, height = 480;
        private boolean loop;
        private final List<Segment> segments = new java.util.ArrayList<>();
        private Path baseDir;
        private SensorConfig sensors;
        public Builder name(String v) { name = v; return this; }
        public Builder description(String v) { description = v; return this; }
        public Builder size(int w, int h) { width = w; height = h; return this; }
        public Builder loop(boolean v) { loop = v; return this; }
        public Builder segment(Segment s) { segments.add(Objects.requireNonNull(s)); return this; }
        public Builder baseDir(Path p) { baseDir = p; return this; }
        /** Adds depth / IR planes to every frame (null = RGB only). */
        public Builder sensors(SensorConfig v) { sensors = v; return this; }
        public Scenario build() { return new Scenario(this); }
    }
}
