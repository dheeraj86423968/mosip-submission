package io.mosip.vivid.device.mock.scenario;

import io.mosip.vivid.device.mock.render.DepthIrRenderer;
import io.mosip.vivid.device.mock.render.FaceState;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Produces the optional depth / IR planes of a {@link Scenario} with {@link Scenario#sensors()} set, for the same
 * segment instant the RGB {@link FrameGenerator} renders. Head placement and pose follow
 * {@link FrameGenerators}' synthetic-face layout (one centred face, or {@code n} faces spread across the frame).
 * Frame-directory, blank and noise segments get a head-free scene (sheet or wall) because their content is unknown.
 * Thread-safe.
 */
public final class SensorPlaneGenerator {
    private final Scenario scenario;
    private final SensorConfig config;
    private final DepthIrRenderer renderer;

    public SensorPlaneGenerator(Scenario scenario) {
        this.scenario = Objects.requireNonNull(scenario);
        this.config = scenario.sensors().orElseThrow(() -> new IllegalArgumentException("scenario has no sensors"));
        this.renderer = new DepthIrRenderer(scenario.width(), scenario.height());
    }

    public SensorConfig config() { return config; }

    /** Planes for {@code segment} at {@code segmentTimeMs}; {@code rgb} is the matching colour frame (IR albedo), may be null. */
    public DepthIrRenderer.Planes render(Segment segment, long segmentTimeMs, long frameIndex, BufferedImage rgb) {
        long seed = segment.seed() * 1_000_003L + frameIndex;
        List<DepthIrRenderer.Head> heads = heads(segment, segmentTimeMs);
        SurfaceKind kind = config.surface().resolve(segment.label());
        if (segment.label() == PresentationLabel.NONE && config.surface() == SurfaceKind.AUTO) heads = List.of();
        DepthIrRenderer.Head first = heads.isEmpty() ? new DepthIrRenderer.Head(FaceState.neutral(), 0.5, 0.5, 1.0) : heads.get(0);
        return switch (kind) {
            case HEAD, AUTO -> renderer.heads(heads, rgb, config.depth(), config.ir(), config.noiseMm(), config.holeFraction(), seed);
            case FLAT_PRINT -> sheet(DepthIrRenderer.Sheet.FLAT, first, rgb, seed);
            case CURVED_PRINT -> sheet(DepthIrRenderer.Sheet.CYLINDER, first, rgb, seed);
            case SCREEN -> sheet(DepthIrRenderer.Sheet.SCREEN, first, rgb, seed);
        };
    }

    private DepthIrRenderer.Planes sheet(DepthIrRenderer.Sheet kind, DepthIrRenderer.Head at, BufferedImage rgb, long seed) {
        return renderer.sheet(kind, at, rgb, config.depth(), config.ir(), config.tiltDeg(), config.pitchDeg(),
                config.bendRadiusMm(), config.noiseMm(), config.holeFraction(), seed);
    }

    /** Same layout as {@code FrameGenerators.SyntheticFace}. */
    static List<DepthIrRenderer.Head> heads(Segment segment, long t) {
        if (!Segment.SOURCE_SYNTHETIC_FACE.equals(segment.source())) return List.of();
        long tl = segment.timeline().durationMs();
        long tt = (segment.timelineLoops() && tl > 0) ? t % tl : t;
        FaceState state = segment.timeline().stateAt(tt);
        int n = segment.faceCount();
        List<DepthIrRenderer.Head> out = new ArrayList<>();
        if (n == 1) {
            out.add(new DepthIrRenderer.Head(state, 0.5, 0.5, 1.0));
        } else {
            for (int i = 0; i < n; i++) {
                double cx = (i + 1.0) / (n + 1.0), scale = i == 0 ? 0.75 : 0.65;
                FaceState s = i == 0 ? state : new FaceState(-state.yawDeg(), state.pitchDeg(), state.eyeOpen(), 0, state.smile() * 0.5, 0);
                out.add(new DepthIrRenderer.Head(s, cx, 0.5, scale));
            }
        }
        return out;
    }
}
