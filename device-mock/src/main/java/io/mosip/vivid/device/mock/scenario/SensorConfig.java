package io.mosip.vivid.device.mock.scenario;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;
import java.util.Objects;

/**
 * Optional L1-style sensor planes for a {@link Scenario}: a registered depth map (GRAY16, millimetres, little-endian,
 * 0 = no measurement) and an active-IR image (GRAY8), both at the RGB resolution. Absent by default, so existing
 * scenarios produce RGB-only frames exactly as before. JSON:
 * <pre>{@code
 * "sensors": {
 *   "depth": true, "ir": true,
 *   "surface": "AUTO" | "HEAD" | "FLAT_PRINT" | "CURVED_PRINT" | "SCREEN",
 *   "tiltDeg": 15, "pitchDeg": 5,          // sheet orientation (prints and screens)
 *   "bendRadiusMm": 120,                    // CURVED_PRINT
 *   "noiseMm": 1.0, "holeFraction": 0.01    // Gaussian depth noise and random drop-outs
 * }
 * }</pre>
 */
public record SensorConfig(boolean depth, boolean ir, SurfaceKind surface, double tiltDeg, double pitchDeg,
                           double bendRadiusMm, double noiseMm, double holeFraction) {
    public SensorConfig {
        Objects.requireNonNull(surface, "surface");
        if (bendRadiusMm <= 0) throw new IllegalArgumentException("bendRadiusMm must be > 0");
        if (noiseMm < 0) throw new IllegalArgumentException("noiseMm must be >= 0");
        if (holeFraction < 0 || holeFraction >= 1) throw new IllegalArgumentException("holeFraction must be in [0,1)");
    }

    /** Depth and IR, surface from the segment labels, default sheet tilt and noise. */
    public static SensorConfig depthAndIr() { return new SensorConfig(true, true, SurfaceKind.AUTO, 15, 5, 120, 1.0, 0.01); }

    /** Same configuration with another surface. */
    public SensorConfig withSurface(SurfaceKind s) { return new SensorConfig(depth, ir, s, tiltDeg, pitchDeg, bendRadiusMm, noiseMm, holeFraction); }

    /** True when at least one plane is produced. */
    public boolean any() { return depth || ir; }

    /** Parses the {@code sensors} object; missing keys take the {@link #depthAndIr()} defaults. */
    public static SensorConfig parse(JsonNode n) {
        SensorConfig d = depthAndIr();
        if (n == null || !n.isObject()) throw new IllegalArgumentException("'sensors' must be an object");
        SurfaceKind surface = d.surface();
        if (n.hasNonNull("surface")) {
            String text = n.get("surface").asText().trim().toUpperCase(Locale.ROOT).replace('-', '_');
            try { surface = SurfaceKind.valueOf(text); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("'" + n.get("surface").asText() + "' is not one of " + java.util.List.of(SurfaceKind.values())); }
        }
        return new SensorConfig(n.path("depth").asBoolean(d.depth()), n.path("ir").asBoolean(d.ir()), surface,
                n.path("tiltDeg").asDouble(d.tiltDeg()), n.path("pitchDeg").asDouble(d.pitchDeg()),
                n.path("bendRadiusMm").asDouble(d.bendRadiusMm()), n.path("noiseMm").asDouble(d.noiseMm()),
                n.path("holeFraction").asDouble(d.holeFraction()));
    }
}
