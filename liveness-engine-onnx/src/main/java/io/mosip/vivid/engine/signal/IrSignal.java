package io.mosip.vivid.engine.signal;

import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.engine.image.ImageOps;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * S8 (IR half) - near-infrared reflectance of the face region in a registered, actively illuminated IR plane
 * ({@link FaceFrame#ir()}, GRAY8 or GRAY16).
 *
 * <p>Under the camera's own NIR emitter (850-940 nm) skin is a good diffuse reflector, so a face at capture distance is
 * one of the brightest things in the image and shows soft 3-D shading plus a corneal glint (the emitter's reflection
 * on the eye). An LCD/OLED screen emits essentially no NIR and its glass reflects the emitter specularly away, so a
 * replayed face is IR-dark. Many colour print inks are transparent in NIR, so a print reads as nearly uniform paper:
 * bright but flat, without glints. Features on the inner-face region ({@link PlaneRegion}):
 * <ul>
 *   <li>{@code level}: median face IR as a fraction of full scale (255 for GRAY8, configurable for GRAY16);</li>
 *   <li>{@code cv}: coefficient of variation of face IR after removing a least-squares linear trend in image
 *       coordinates (residual std / mean), so the smooth brightness gradient of a tilted, IR-lit print under
 *       inverse-square fall-off does not read as texture; a face's shading (nose, sockets, cheek roll-off) is
 *       not linear and survives;</li>
 *   <li>{@code glints}: eyes (0-2) whose 0.2 IOD window has a near-saturated pixel at least 1.5x the window median.</li>
 * </ul>
 * Raw attack logit {@code max(dark, flat) - glints}, where {@code dark = (D - level) / 0.3D} and
 * {@code flat = (F - cv) / 0.3F} ({@code D} = {@link DepthIrSettings#irDarkFraction()}, {@code F} =
 * {@link DepthIrSettings#irFlatCv()}), clipped to [-8, 8], then Platt calibration. The class is
 * {@code SCREEN_REPLAY} when the dark term dominates, else {@code PRINT}.
 *
 * <p>These are heuristics, not a trained IR model, so IR never vetoes: the probability is capped at
 * {@link DepthIrSettings#irMaxFusedProbability()}. Confidence falls with saturation (more than 30 % clipped pixels
 * gives 0), with few samples, and to 30 % when the whole frame is dark (emitter off or blocked looks like a screen).
 * No IR plane: abstains (empty). Stateless across frames.
 */
public final class IrSignal implements FramePlaneSignal {
    static final int TARGET_SAMPLES = 4000;

    private final DepthIrSettings settings;
    private final PlattCalibration calibration;
    private Measurement last;

    public IrSignal(DepthIrSettings settings, PlattCalibration calibration) {
        this.settings = settings == null ? DepthIrSettings.defaults() : settings;
        this.calibration = calibration == null ? PlattCalibration.identity() : calibration;
    }

    @Override public String id() { return Signals.IR; }

    @Override
    public Optional<SignalScore> score(FaceContext ctx, FaceFrame frame) {
        Optional<ByteBuffer> plane = frame.ir();
        if (plane.isEmpty()) return Optional.empty();
        int w = frame.width(), h = frame.height();
        ByteBuffer buf = plane.get().order(settings.depthByteOrder());
        long px = (long) w * h;
        Plane p;
        if (buf.remaining() >= 2 * px) p = new Plane(buf, w, true, settings.irFullScale16());
        else if (buf.remaining() >= px) p = new Plane(buf, w, false, 255);
        else return Optional.empty();
        double sx = (double) w / ctx.frame().width(), sy = (double) h / ctx.frame().height();
        Measurement m = measure(p, w, h, new PlaneRegion(ctx.face(), w, h, sx, sy, TARGET_SAMPLES));
        last = m;
        return Optional.of(toScore(m));
    }

    SignalScore toScore(Measurement m) {
        if (m.confidence <= 0) return Signal.neutral(id());
        double d = settings.irDarkFraction(), f = settings.irFlatCv();
        double dark = (d - m.level) / (0.3 * d);
        double flat = (f - m.cv) / (0.3 * f);
        double raw = Math.max(-8, Math.min(8, Math.max(dark, flat) - m.glints));
        double p = Math.min(calibration.apply(raw), settings.irMaxFusedProbability());
        String cls = p > 0.5 ? (dark >= flat ? Signals.ATTACK_SCREEN : Signals.ATTACK_PRINT) : null;
        return new SignalScore(id(), p, m.confidence, false, cls);
    }

    Measurement measure(Plane p, int w, int h, PlaneRegion r) {
        if (r.isEmpty()) return new Measurement(0, 0, 0, 0, 0, 0);
        int cap = ((r.x1 - r.x0) / r.stride + 1) * ((r.y1 - r.y0) / r.stride + 1);
        double[] v = new double[cap];
        double[][] basis = new double[cap][];
        int n = 0, saturated = 0;
        double sum = 0;
        for (int y = r.y0; y < r.y1; y += r.stride) {
            for (int x = r.x0; x < r.x1; x += r.stride) {
                if (!r.inside(x, y)) continue;
                double s = p.at(x, y);
                basis[n] = new double[] {1, r.u(x), r.v(y)};
                v[n++] = s; sum += s;
                if (s >= 0.98) saturated++;
            }
        }
        if (n < 30) return new Measurement(n, 0, 0, 0, 0, 0);
        double mean = sum / n;
        double sd = detrendedStd(basis, v, n, mean);
        double level = PlaneRegion.percentile(v, n, 0.5);
        double cv = mean > 1e-6 ? sd / mean : 0;
        int glints = glint(p, w, h, r.eyeRX, r.eyeRY, r.iod) + glint(p, w, h, r.eyeLX, r.eyeLY, r.iod);
        // frame-level illumination check: is anything in the scene lit by the emitter?
        int step = Math.max(1, (int) Math.sqrt((double) w * h / 4000));
        double[] all = new double[(w / step + 1) * (h / step + 1)];
        int na = 0;
        for (int y = 0; y < h; y += step) for (int x = 0; x < w; x += step) all[na++] = p.at(x, y);
        double sceneP95 = PlaneRegion.percentile(all, na, 0.95);
        double satFactor = ImageOps.clamp01(1 - ((double) saturated / n) / 0.3);
        double size = Math.min(1, n / 300.0);
        double lit = sceneP95 >= settings.irDarkFraction() ? 1.0 : 0.3;
        return new Measurement(n, level, cv, glints, sceneP95, satFactor * size * lit);
    }

    /** Std of the residual after a least-squares fit of {@code a + b u + c v}; plain std if the fit is degenerate. */
    private static double detrendedStd(double[][] basis, double[] v, int n, double mean) {
        double[] coef;
        try {
            coef = io.mosip.vivid.engine.pose.LinearSolver.leastSquares(java.util.Arrays.copyOf(basis, n), java.util.Arrays.copyOf(v, n));
        } catch (ArithmeticException e) {
            coef = new double[] {mean, 0, 0};
        }
        double s2 = 0;
        for (int i = 0; i < n; i++) {
            double e = v[i] - (coef[0] + coef[1] * basis[i][1] + coef[2] * basis[i][2]);
            s2 += e * e;
        }
        return Math.sqrt(s2 / n);
    }

    private static int glint(Plane p, int w, int h, double ex, double ey, double iod) {
        int rad = Math.max(2, (int) Math.round(0.2 * iod));
        int cx = (int) Math.round(ex), cy = (int) Math.round(ey);
        double[] v = new double[(2 * rad + 1) * (2 * rad + 1)];
        int n = 0;
        double max = 0;
        for (int y = Math.max(0, cy - rad); y <= Math.min(h - 1, cy + rad); y++) {
            for (int x = Math.max(0, cx - rad); x <= Math.min(w - 1, cx + rad); x++) {
                double s = p.at(x, y);
                v[n++] = s;
                max = Math.max(max, s);
            }
        }
        if (n < 5) return 0;
        double med = PlaneRegion.percentile(v, n, 0.5);
        return max >= 0.9 && max >= 1.5 * med ? 1 : 0;
    }

    /** Diagnostics of the last frame that had an IR plane, or null. */
    public Measurement lastMeasurement() { return last; }

    @Override public void reset() { last = null; }

    /** Normalised read access to a GRAY8 or GRAY16 plane. */
    static final class Plane {
        private final ByteBuffer buf;
        private final int w, base;
        private final boolean sixteen;
        private final double fullScale;

        Plane(ByteBuffer buf, int w, boolean sixteen, double fullScale) {
            this.buf = buf; this.w = w; this.sixteen = sixteen; this.fullScale = fullScale; this.base = buf.position();
        }

        double at(int x, int y) {
            int i = y * w + x;
            double raw = sixteen ? (buf.getShort(base + 2 * i) & 0xffff) : (buf.get(base + i) & 0xff);
            return Math.min(1.0, raw / fullScale);
        }
    }

    /** Per-frame IR features; levels are fractions of full scale. */
    public static final class Measurement {
        public final int samples;
        public final double level, cv, sceneP95, confidence;
        public final int glints;

        Measurement(int samples, double level, double cv, int glints, double sceneP95, double confidence) {
            this.samples = samples; this.level = level; this.cv = cv; this.glints = glints; this.sceneP95 = sceneP95; this.confidence = confidence;
        }

        @Override public String toString() {
            return String.format("Ir{n=%d level=%.3f cv=%.3f glints=%d sceneP95=%.3f conf=%.2f}", samples, level, cv, glints, sceneP95, confidence);
        }
    }
}
