package io.mosip.vivid.device.mock.scenario;

/**
 * What the simulated depth / IR sensor sees in front of the camera (see {@link SensorConfig}). {@link #AUTO} picks
 * the surface from each segment's {@link PresentationLabel}.
 */
public enum SurfaceKind {
    /** Derive from the segment label: LIVE and MASK give HEAD, PRINT gives FLAT_PRINT, SCREEN_REPLAY and VIDEO_REPLAY give SCREEN. */
    AUTO,
    /** A 3-D head: ellipsoid with nose, eye sockets and chin following the rendered pose; skin-like IR with corneal glints. */
    HEAD,
    /** A flat print held at a tilt: planar depth with exact pinhole perspective; bright, nearly uniform IR (NIR-transparent inks). */
    FLAT_PRINT,
    /** A print bent around a vertical axis into a cylinder of {@link SensorConfig#bendRadiusMm()}; print-like IR. */
    CURVED_PRINT,
    /** A phone / tablet / monitor: planar depth with extra drop-outs on the glass; IR-dark panel (no NIR emission) in a lit bezel. */
    SCREEN;

    /** Resolves {@link #AUTO} for a segment label. */
    public SurfaceKind resolve(PresentationLabel label) {
        if (this != AUTO) return this;
        return switch (label) {
            case PRINT -> FLAT_PRINT;
            case SCREEN_REPLAY, VIDEO_REPLAY -> SCREEN;
            case LIVE, MASK, NONE -> HEAD;
        };
    }
}
