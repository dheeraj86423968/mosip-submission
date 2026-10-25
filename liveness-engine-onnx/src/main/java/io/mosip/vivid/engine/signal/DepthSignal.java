package io.mosip.vivid.engine.signal;

import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.engine.image.ImageOps;
import io.mosip.vivid.engine.pose.LinearSolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * S8 (depth half) - 3-D relief of the face region in a registered depth plane ({@link FaceFrame#depth()}).
 *
 * <p>A print or a screen is a sheet: flat, tilted, or at most bent along one direction. A face is a doubly curved
 * surface with a nose that stands 2-4 cm proud of the cheeks. On the inner-face region ({@link PlaneRegion}) the signal:
 * <ol>
 *   <li>reads the valid samples (non-zero, inside {@code [minDepthMm, maxDepthMm]}, within 150 mm of the region's
 *       median so background and flying pixels at the silhouette drop out);</li>
 *   <li>estimates the sensor noise {@code sigma} from second differences {@code d(x-1) - 2 d(x) + d(x+1)}
 *       (robust MAD; a linear tilt cancels out);</li>
 *   <li>fits a plane and a full quadratic by least squares in <em>inverse</em> depth {@code 1/Z} over normalised image
 *       coordinates, because under a pinhole camera a tilted plane is exactly linear in {@code 1/Z} but not in
 *       {@code Z} (a 30 deg tilt leaves several millimetres of spurious curvature in a {@code Z}-plane fit);
 *       residuals are converted back to millimetres;</li>
 *   <li>noise-corrects both RMS residuals: {@code relief = sqrt(max(0, rms^2 - sigma^2))};</li>
 *   <li>measures nose protrusion above the fitted quadratic: {@code -P10(residual)} in a window of 0.3 IOD around the
 *       nose landmark, less the {@code 1.28 sigma} a pure-noise P10 would give.</li>
 * </ol>
 * The plane relief separates sheets from anything curved; the quadratic relief and the nose protrusion separate a face
 * from a sheet bent into a cylinder (a cylinder is almost exactly quadratic, a nose is not). Live evidence
 * {@code L = 0.5 min(quadRelief / Q, 2) + 0.5 min(nose / N, 2)} gives the raw attack logit {@code 4 (1 - L)} through
 * Platt calibration ({@code Q} = {@link DepthIrSettings#quadReliefMm()}, {@code N} =
 * {@link DepthIrSettings#noseProtrusionMm()}).
 *
 * <p>Confidence is the product of coverage ({@code (validFraction - 0.4) / 0.4}), sample count ({@code n / 300}) and
 * noise ({@code 1 - (sigma - 4 mm) / 8 mm}), each clipped to [0, 1]; a frame mostly made of holes says nothing.
 * When the plane relief is below {@link DepthIrSettings#vetoPlaneReliefMm()}, the nose protrusion below a third of
 * {@code N} and the confidence at least {@link DepthIrSettings#vetoMinConfidence()}, the surface is a sheet and the
 * score carries {@code hardVeto} (class {@code PRINT}, the planar-instrument class S5 uses too). Otherwise the attack
 * probability is capped at {@link DepthIrSettings#depthMaxFusedProbability()} so the model-based curved-sheet verdict
 * stays below the orchestrator's threshold veto and is only fused.
 *
 * <p>No depth plane: abstains (empty). Stateless across frames.
 */
public final class DepthSignal implements FramePlaneSignal {
    private static final Logger LOG = LoggerFactory.getLogger(DepthSignal.class);
    static final double GATE_MM = 150;
    static final int TARGET_SAMPLES = 4000;

    private final DepthIrSettings settings;
    private final PlattCalibration calibration;
    private Measurement last;
    private boolean warnedSize;

    public DepthSignal(DepthIrSettings settings, PlattCalibration calibration) {
        this.settings = settings == null ? DepthIrSettings.defaults() : settings;
        this.calibration = calibration == null ? PlattCalibration.identity() : calibration;
    }

    @Override public String id() { return Signals.DEPTH; }

    @Override
    public Optional<SignalScore> score(FaceContext ctx, FaceFrame frame) {
        Optional<ByteBuffer> plane = frame.depth();
        if (plane.isEmpty()) return Optional.empty();
        int w = frame.width(), h = frame.height();
        ByteBuffer buf = plane.get().order(settings.depthByteOrder());
        if (buf.remaining() < 2L * w * h) {
            if (!warnedSize) LOG.warn("depth plane has {} bytes, expected {} for {}x{} GRAY16; S8 depth abstains", buf.remaining(), 2L * w * h, w, h);
            warnedSize = true;
            return Optional.empty();
        }
        double sx = (double) w / ctx.frame().width(), sy = (double) h / ctx.frame().height();
        Measurement m = measure(buf, w, h, new PlaneRegion(ctx.face(), w, h, sx, sy, TARGET_SAMPLES));
        last = m;
        return Optional.of(toScore(m));
    }

    SignalScore toScore(Measurement m) {
        if (m.confidence <= 0) return Signal.neutral(id());
        double live = 0.5 * Math.min(m.quadReliefMm / settings.quadReliefMm(), 2) + 0.5 * Math.min(m.noseProtrusionMm / settings.noseProtrusionMm(), 2);
        double p = calibration.apply(4 * (1 - live));
        boolean planar = m.planeReliefMm < settings.vetoPlaneReliefMm() && m.noseProtrusionMm < settings.noseProtrusionMm() / 3;
        boolean veto = settings.depthHardVeto() && planar && m.confidence >= settings.vetoMinConfidence() && p > 0.5;
        if (!veto) p = Math.min(p, settings.depthMaxFusedProbability());
        return new SignalScore(id(), p, m.confidence, veto, p > 0.5 ? Signals.ATTACK_PRINT : null);
    }

    /** Measures the face region of a GRAY16 depth plane. {@code buf} must already carry the configured byte order. */
    Measurement measure(ByteBuffer buf, int w, int h, PlaneRegion r) {
        double scale = settings.depthScaleMm(), min = settings.minDepthMm(), max = settings.maxDepthMm();
        if (r.isEmpty()) return Measurement.none();
        int cap = ((r.x1 - r.x0) / r.stride + 1) * ((r.y1 - r.y0) / r.stride + 1);
        double[] d = new double[cap], us = new double[cap], vs = new double[cap];
        int total = 0, n = 0;
        for (int y = r.y0; y < r.y1; y += r.stride) {
            for (int x = r.x0; x < r.x1; x += r.stride) {
                if (!r.inside(x, y)) continue;
                total++;
                double z = depthAt(buf, w, x, y, scale);
                if (z < min || z > max) continue;
                d[n] = z; us[n] = r.u(x); vs[n] = r.v(y); n++;
            }
        }
        if (total == 0 || n < 30) return Measurement.sparse(total == 0 ? 0 : (double) n / total, n);
        double median = PlaneRegion.percentile(d, n, 0.5);
        // gate: keep the face surface, drop background and flying pixels
        int k = 0;
        double[] second = new double[n];
        int ns = 0;
        for (int i = 0; i < n; i++) {
            if (Math.abs(d[i] - median) > GATE_MM) continue;
            d[k] = d[i]; us[k] = us[i]; vs[k] = vs[i]; k++;
        }
        n = k;
        // noise from horizontal second differences at the sampled rows (stride-independent: neighbours are adjacent pixels)
        for (int y = r.y0; y < r.y1 && ns < second.length; y += r.stride) {
            for (int x = Math.max(1, r.x0); x < Math.min(w - 1, r.x1) && ns < second.length; x += r.stride) {
                if (!r.inside(x, y)) continue;
                double a = depthAt(buf, w, x - 1, y, scale), b = depthAt(buf, w, x, y, scale), c = depthAt(buf, w, x + 1, y, scale);
                if (!valid(a, min, max) || !valid(b, min, max) || !valid(c, min, max)) continue;
                if (Math.abs(b - median) > GATE_MM || Math.abs(a - b) > 30 || Math.abs(c - b) > 30) continue;
                second[ns++] = Math.abs(a - 2 * b + c);
            }
        }
        double sigma = ns >= 20 ? 1.4826 * PlaneRegion.percentile(second, ns, 0.5) / Math.sqrt(6) : 2.0;
        double validFraction = (double) n / total;
        if (n < 30) return Measurement.sparse(validFraction, n);

        double[] plane = fit(d, us, vs, n, false);
        double[] quad = fit(d, us, vs, n, true);
        if (plane == null || quad == null) return Measurement.sparse(validFraction, n);
        double planeRms = rms(d, us, vs, n, plane), quadRms = rms(d, us, vs, n, quad);

        // nose protrusion above the quadratic surface, dense window around the nose landmark
        int rad = Math.max(2, (int) Math.round(0.3 * r.iod));
        int nx = (int) Math.round(r.noseX), ny = (int) Math.round(r.noseY);
        double[] res = new double[(2 * rad + 1) * (2 * rad + 1)];
        int nr = 0;
        for (int y = Math.max(0, ny - rad); y <= Math.min(h - 1, ny + rad); y++) {
            for (int x = Math.max(0, nx - rad); x <= Math.min(w - 1, nx + rad); x++) {
                if ((x - nx) * (x - nx) + (y - ny) * (y - ny) > rad * rad) continue;
                double z = depthAt(buf, w, x, y, scale);
                if (!valid(z, min, max) || Math.abs(z - median) > GATE_MM) continue;
                res[nr++] = z - predict(quad, r.u(x), r.v(y));
            }
        }
        double nose = nr >= 5 ? Math.max(0, -PlaneRegion.percentile(res, nr, 0.10) - 1.28 * sigma) : 0;

        double coverage = ImageOps.clamp01((validFraction - 0.4) / 0.4);
        double size = Math.min(1, n / 300.0);
        double noise = ImageOps.clamp01(1 - (sigma - 4) / 8);
        double confidence = coverage * size * noise;
        return new Measurement(n, validFraction, median, sigma, planeRms, quadRms,
                relief(planeRms, sigma), relief(quadRms, sigma), nose, confidence);
    }

    private static double relief(double rms, double sigma) { return Math.sqrt(Math.max(0, rms * rms - sigma * sigma)); }

    private static boolean valid(double z, double min, double max) { return z >= min && z <= max; }

    private static double depthAt(ByteBuffer buf, int w, int x, int y, double scale) {
        return (buf.getShort(buf.position() + 2 * (y * w + x)) & 0xffff) * scale;
    }

    /** Least-squares fit of 1000/Z over (1, u, v[, u^2, uv, v^2]); null when degenerate. */
    private static double[] fit(double[] d, double[] u, double[] v, int n, boolean quadratic) {
        int cols = quadratic ? 6 : 3;
        if (n < cols * 4) return null;
        double[][] a = new double[n][];
        double[] b = new double[n];
        for (int i = 0; i < n; i++) {
            a[i] = basis(u[i], v[i], quadratic);
            b[i] = 1000.0 / d[i];
        }
        try {
            return LinearSolver.leastSquares(a, b);
        } catch (ArithmeticException e) {
            return null;
        }
    }

    private static double[] basis(double u, double v, boolean quadratic) {
        return quadratic ? new double[] {1, u, v, u * u, u * v, v * v} : new double[] {1, u, v};
    }

    /** Fitted depth in mm at (u, v). */
    private static double predict(double[] c, double u, double v) {
        double inv = c[0] + c[1] * u + c[2] * v;
        if (c.length == 6) inv += c[3] * u * u + c[4] * u * v + c[5] * v * v;
        return inv <= 1e-9 ? Double.MAX_VALUE : 1000.0 / inv;
    }

    private static double rms(double[] d, double[] u, double[] v, int n, double[] c) {
        double s = 0;
        for (int i = 0; i < n; i++) {
            double e = d[i] - predict(c, u[i], v[i]);
            s += e * e;
        }
        return Math.sqrt(s / n);
    }

    /** Diagnostics of the last frame that had a depth plane, or null. */
    public Measurement lastMeasurement() { return last; }

    @Override public void reset() { last = null; }

    /** Per-frame depth features (millimetres). */
    public static final class Measurement {
        public final int samples;
        public final double validFraction, medianDepthMm, noiseMm, planeRmsMm, quadRmsMm, planeReliefMm, quadReliefMm, noseProtrusionMm, confidence;

        Measurement(int samples, double validFraction, double medianDepthMm, double noiseMm, double planeRmsMm, double quadRmsMm,
                    double planeReliefMm, double quadReliefMm, double noseProtrusionMm, double confidence) {
            this.samples = samples; this.validFraction = validFraction; this.medianDepthMm = medianDepthMm; this.noiseMm = noiseMm;
            this.planeRmsMm = planeRmsMm; this.quadRmsMm = quadRmsMm; this.planeReliefMm = planeReliefMm;
            this.quadReliefMm = quadReliefMm; this.noseProtrusionMm = noseProtrusionMm; this.confidence = confidence;
        }

        static Measurement none() { return sparse(0, 0); }
        static Measurement sparse(double validFraction, int samples) {
            return new Measurement(samples, validFraction, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, 0, 0, 0);
        }

        @Override public String toString() {
            return String.format("Depth{n=%d valid=%.2f med=%.0fmm sigma=%.2f planeRms=%.2f quadRms=%.2f planeRelief=%.2f quadRelief=%.2f nose=%.2f conf=%.2f}",
                    samples, validFraction, medianDepthMm, noiseMm, planeRmsMm, quadRmsMm, planeReliefMm, quadReliefMm, noseProtrusionMm, confidence);
        }
    }
}
