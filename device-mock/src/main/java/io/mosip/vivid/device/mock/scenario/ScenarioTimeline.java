package io.mosip.vivid.device.mock.scenario;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Random-access view of a {@link Scenario} over continuous scenario time. Both the in-process frame source and
 * the mock SBI server use it: they advance a cursor in milliseconds and ask for the frame due at that instant
 * and the faults crossed since the previous instant.
 *
 * <p>Thread-safe; generators are created once per segment.
 */
public final class ScenarioTimeline {
    /** Where a scenario instant falls. */
    public record Position(int segmentIndex, Segment segment, long segmentTimeMs, int loop, long segmentStartMs) {
        /** 0-based frame index within the segment at this instant. */
        public long frameIndex() { return (long) Math.floor(segmentTimeMs / segment.frameIntervalMs()); }
    }

    /** A scheduled fault with its absolute scenario time. */
    public record FaultOccurrence(long atScenarioMs, Fault fault, int segmentIndex) {}

    private final Scenario scenario;
    private final long[] segmentStarts;
    private final long totalMs;
    private final FrameGenerator[] generators;
    private final SensorPlaneGenerator sensors;   // null for RGB-only scenarios

    public ScenarioTimeline(Scenario scenario) {
        this.scenario = scenario;
        List<Segment> segs = scenario.segments();
        segmentStarts = new long[segs.size()];
        generators = new FrameGenerator[segs.size()];
        long t = 0;
        for (int i = 0; i < segs.size(); i++) {
            segmentStarts[i] = t;
            t += segs.get(i).durationMs();
            generators[i] = FrameGenerators.forSegment(scenario, segs.get(i));
        }
        totalMs = t;
        sensors = scenario.sensors().isPresent() ? new SensorPlaneGenerator(scenario) : null;
    }

    public Scenario scenario() { return scenario; }

    /** Duration of one pass through all segments. */
    public long passDurationMs() { return totalMs; }

    /** True once {@code tMs} is past the end of a non-looping scenario. */
    public boolean isEnded(long tMs) { return !scenario.loop() && tMs >= totalMs; }

    /** Locate {@code tMs}; empty when ended. */
    public Optional<Position> positionAt(long tMs) {
        if (tMs < 0) tMs = 0;
        if (isEnded(tMs)) return Optional.empty();
        int loop = (int) (tMs / totalMs);
        long inPass = tMs % totalMs;
        int idx = segmentStarts.length - 1;
        for (int i = 0; i < segmentStarts.length; i++) {
            if (inPass < segmentStarts[i] + scenario.segments().get(i).durationMs()) { idx = i; break; }
        }
        Segment seg = scenario.segments().get(idx);
        return Optional.of(new Position(idx, seg, inPass - segmentStarts[idx], loop, loop * totalMs + segmentStarts[idx]));
    }

    /** Render the frame for a position. */
    public BufferedImage render(Position p) {
        return generators[p.segmentIndex()].render(p.segmentTimeMs(), p.frameIndex());
    }

    /** Depth / IR planes for a position (registered to {@code rgb}, the frame from {@link #render}); empty for RGB-only scenarios. */
    public Optional<io.mosip.vivid.device.mock.render.DepthIrRenderer.Planes> renderPlanes(Position p, BufferedImage rgb) {
        if (sensors == null) return Optional.empty();
        return Optional.of(sensors.render(p.segment(), p.segmentTimeMs(), p.frameIndex(), rgb));
    }

    /**
     * Scenario time of the next frame at or after {@code tMs}: frames are placed at fixed intervals from the
     * start of each segment. Returns -1 when the scenario has ended.
     */
    public long nextFrameTimeAtOrAfter(long tMs) {
        Optional<Position> op = positionAt(tMs);
        if (op.isEmpty()) return -1;
        Position p = op.get();
        double interval = p.segment().frameIntervalMs();
        long k = (long) Math.ceil(p.segmentTimeMs() / interval - 1e-9);
        long inSeg = Math.round(k * interval);
        if (inSeg >= p.segment().durationMs()) {
            long nextStart = p.segmentStartMs() + p.segment().durationMs();
            return isEnded(nextStart) ? -1 : nextStart;
        }
        return p.segmentStartMs() + inSeg;
    }

    /** Faults whose absolute time lies in {@code (fromExclusiveMs, toInclusiveMs]}, in order. */
    public List<FaultOccurrence> faultsBetween(long fromExclusiveMs, long toInclusiveMs) {
        List<FaultOccurrence> out = new ArrayList<>();
        if (toInclusiveMs <= fromExclusiveMs || totalMs == 0) return out;
        int firstLoop = (int) Math.max(0, fromExclusiveMs / totalMs);
        int lastLoop = (int) (toInclusiveMs / totalMs);
        if (!scenario.loop()) lastLoop = 0;
        for (int loop = firstLoop; loop <= lastLoop; loop++) {
            for (int i = 0; i < segmentStarts.length; i++) {
                for (Fault f : scenario.segments().get(i).faults()) {
                    long abs = loop * totalMs + segmentStarts[i] + f.atMs();
                    if (abs > fromExclusiveMs && abs <= toInclusiveMs) out.add(new FaultOccurrence(abs, f, i));
                }
            }
        }
        out.sort((a, b) -> Long.compare(a.atScenarioMs(), b.atScenarioMs()));
        return out;
    }
}
