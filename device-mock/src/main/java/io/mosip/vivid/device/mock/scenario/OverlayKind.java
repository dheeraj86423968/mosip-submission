package io.mosip.vivid.device.mock.scenario;

/** Post-processing applied to synthetic frames to imitate the texture of a presentation attack instrument. */
public enum OverlayKind {
    /** No overlay. */
    NONE,
    /** Paper-like high-frequency noise, slight desaturation and a halftone dot pattern, as seen on printed photos. */
    PRINT_TEXTURE,
    /** A fine, slowly drifting grid that produces moiré-like interference, as seen when filming a screen. */
    MOIRE_GRID
}
