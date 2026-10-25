package io.mosip.vivid.engine.signal;

import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Thresholds and plane-format conventions for S8 ({@link DepthSignal}, {@link IrSignal}). Immutable. The defaults are
 * hand-set from the physics of a face at 30-120 cm and were checked only against synthetic planes (see
 * {@code docs/design/03-liveness-and-pad.md}, S8); a real depth or IR camera needs a calibration run before these are
 * trusted.
 *
 * <p>Depth plane: GRAY16, one unsigned sample per pixel, {@link #depthByteOrder()} (default little-endian, as RealSense
 * Z16 and most UVC depth cameras deliver it), {@link #depthScaleMm()} millimetres per count (default 1.0), 0 = no
 * measurement. IR plane: GRAY8 (one byte per pixel, full scale 255) or GRAY16 (two bytes per pixel, same byte order,
 * full scale {@link #irFullScale16()}); the format is inferred from the buffer length.
 */
public final class DepthIrSettings {
    // ---- depth plane format and range
    private final ByteOrder depthByteOrder;
    private final double depthScaleMm;
    private final double minDepthMm, maxDepthMm;
    // ---- depth decision
    private final double quadReliefMm;        // noise-corrected RMS residual of the quadratic fit that counts as "face-like"
    private final double noseProtrusionMm;    // nose protrusion above the quadratic surface that counts as "face-like"
    private final boolean depthHardVeto;
    private final double vetoPlaneReliefMm;   // noise-corrected plane-fit RMS below which the surface is planar
    private final double vetoMinConfidence;
    private final double depthMaxFusedProbability; // cap when the hard-veto condition is not met
    // ---- IR
    private final double irFullScale16;
    private final double irDarkFraction;      // median face IR below this fraction of full scale: screen-like
    private final double irFlatCv;            // coefficient of variation below this: flat (print-like)
    private final double irMaxFusedProbability;

    private DepthIrSettings(Builder b) {
        depthByteOrder = Objects.requireNonNull(b.depthByteOrder);
        depthScaleMm = b.depthScaleMm; minDepthMm = b.minDepthMm; maxDepthMm = b.maxDepthMm;
        quadReliefMm = b.quadReliefMm; noseProtrusionMm = b.noseProtrusionMm;
        depthHardVeto = b.depthHardVeto; vetoPlaneReliefMm = b.vetoPlaneReliefMm; vetoMinConfidence = b.vetoMinConfidence;
        depthMaxFusedProbability = b.depthMaxFusedProbability;
        irFullScale16 = b.irFullScale16; irDarkFraction = b.irDarkFraction; irFlatCv = b.irFlatCv;
        irMaxFusedProbability = b.irMaxFusedProbability;
        if (depthScaleMm <= 0) throw new IllegalArgumentException("depth scale must be > 0");
        if (minDepthMm < 0 || maxDepthMm <= minDepthMm) throw new IllegalArgumentException("invalid depth range");
        if (quadReliefMm <= 0 || noseProtrusionMm <= 0) throw new IllegalArgumentException("relief thresholds must be > 0");
        if (irFullScale16 <= 0) throw new IllegalArgumentException("IR full scale must be > 0");
    }

    public static DepthIrSettings defaults() { return builder().build(); }
    public static Builder builder() { return new Builder(); }

    public ByteOrder depthByteOrder() { return depthByteOrder; }
    public double depthScaleMm() { return depthScaleMm; }
    /** Samples nearer than this are treated as invalid (sensor minimum range). Default 150 mm. */
    public double minDepthMm() { return minDepthMm; }
    /** Samples farther than this are invalid; a face beyond it gives no verdict. Default 2000 mm. */
    public double maxDepthMm() { return maxDepthMm; }
    /** Noise-corrected RMS of the inverse-depth quadratic-fit residual at which depth evidence is neutral. Default 2.5 mm. */
    public double quadReliefMm() { return quadReliefMm; }
    /** Nose protrusion above the fitted quadratic surface at which depth evidence is neutral. Default 6 mm. */
    public double noseProtrusionMm() { return noseProtrusionMm; }
    /** Whether a planar face region may raise {@code hardVeto}. Default true. */
    public boolean depthHardVeto() { return depthHardVeto; }
    /** Noise-corrected plane-fit RMS below which the face region is planar (veto candidate). Default 2.0 mm. */
    public double vetoPlaneReliefMm() { return vetoPlaneReliefMm; }
    /** Minimum depth confidence for the planar veto. Default 0.8. */
    public double vetoMinConfidence() { return vetoMinConfidence; }
    /** Upper bound of the depth attack probability when the planar-veto condition is not met. Default 0.85. */
    public double depthMaxFusedProbability() { return depthMaxFusedProbability; }
    /** Full scale of a GRAY16 IR plane. Default 65535. */
    public double irFullScale16() { return irFullScale16; }
    /** Median face IR level (fraction of full scale) below which the face is IR-dark (screen-like). Default 0.10. */
    public double irDarkFraction() { return irDarkFraction; }
    /** Coefficient of variation of face IR below which the face is IR-flat (print-like). Default 0.06. */
    public double irFlatCv() { return irFlatCv; }
    /** Upper bound of the IR attack probability (IR never vetoes alone). Default 0.85. */
    public double irMaxFusedProbability() { return irMaxFusedProbability; }

    @Override public String toString() {
        return "DepthIrSettings{order=" + depthByteOrder + ", scaleMm=" + depthScaleMm + ", range=[" + minDepthMm + "," + maxDepthMm
                + "], quadRelief=" + quadReliefMm + ", nose=" + noseProtrusionMm + ", veto=" + depthHardVeto + "@" + vetoPlaneReliefMm
                + "mm/c" + vetoMinConfidence + ", depthCap=" + depthMaxFusedProbability + ", irDark=" + irDarkFraction
                + ", irFlatCv=" + irFlatCv + ", irCap=" + irMaxFusedProbability + "}";
    }

    /** Builder with the documented defaults. */
    public static final class Builder {
        private ByteOrder depthByteOrder = ByteOrder.LITTLE_ENDIAN;
        private double depthScaleMm = 1.0, minDepthMm = 150, maxDepthMm = 2000;
        private double quadReliefMm = 2.5, noseProtrusionMm = 6.0;
        private boolean depthHardVeto = true;
        private double vetoPlaneReliefMm = 2.0, vetoMinConfidence = 0.8, depthMaxFusedProbability = 0.85;
        private double irFullScale16 = 65535, irDarkFraction = 0.10, irFlatCv = 0.06, irMaxFusedProbability = 0.85;

        public Builder depthByteOrder(ByteOrder v) { depthByteOrder = v; return this; }
        public Builder depthScaleMm(double v) { depthScaleMm = v; return this; }
        public Builder depthRangeMm(double min, double max) { minDepthMm = min; maxDepthMm = max; return this; }
        public Builder quadReliefMm(double v) { quadReliefMm = v; return this; }
        public Builder noseProtrusionMm(double v) { noseProtrusionMm = v; return this; }
        public Builder depthHardVeto(boolean v) { depthHardVeto = v; return this; }
        public Builder vetoPlaneReliefMm(double v) { vetoPlaneReliefMm = v; return this; }
        public Builder vetoMinConfidence(double v) { vetoMinConfidence = v; return this; }
        public Builder depthMaxFusedProbability(double v) { depthMaxFusedProbability = v; return this; }
        public Builder irFullScale16(double v) { irFullScale16 = v; return this; }
        public Builder irDarkFraction(double v) { irDarkFraction = v; return this; }
        public Builder irFlatCv(double v) { irFlatCv = v; return this; }
        public Builder irMaxFusedProbability(double v) { irMaxFusedProbability = v; return this; }
        public DepthIrSettings build() { return new DepthIrSettings(this); }
    }
}
