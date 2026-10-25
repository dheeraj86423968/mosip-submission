package io.mosip.vivid.testkit.redteam;

import io.mosip.vivid.device.mock.render.ExpressionEvent;
import io.mosip.vivid.device.mock.render.ExpressionTimeline;
import io.mosip.vivid.device.mock.render.FaceState;
import io.mosip.vivid.device.mock.render.SyntheticFaceRenderer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

/**
 * Renders a {@link RedTeamVariant} into camera frames (640x480 JPEG) from device-mock's {@link SyntheticFaceRenderer}
 * only: no real faces and no media files are read.
 *
 * <p>Pipeline, a reduced Java port of {@code tools/proxy-eval/simulate_attacks.py}:
 * <ol>
 *   <li><b>Live session</b>: the drawn face at 2x (1280x960) on a gradient background, driven by an
 *       {@link ExpressionTimeline} (two blinks, a small head turn, optional slight smile).</li>
 *   <li><b>Instrument</b>: print species build a reflectance sheet from one still frame (CMY clustered-dot halftone or
 *       8x8 ordered dither, ink spread, paper white and mottle) and place it, hand-held, in front of a wall
 *       (optionally bent into a cylinder, or with eye holes that show the live eyes). Screen species show each live (or
 *       one still) frame on a simulated RGB-stripe panel (pixel grid attenuated by the camera optics so it aliases into
 *       moiré, display gamma, white point, black level, glare, refresh banding, bezel).</li>
 *   <li><b>Capture camera</b> (all species, bona fide included): Gaussian optics at 2x, 2x2 pixel aperture, exposure
 *       gain and room-light tint, read + shot noise, JPEG.</li>
 * </ol>
 * Everything is driven by the variant's parameters and seeded {@link Random}s (whose algorithm the JDK specifies), so
 * the same variant renders the same bytes. These are weak proxies of physical attacks; see
 * {@code docs/testing/redteam-trend.md}.
 */
public final class RedTeamRenderer {
    public static final int W = 640, H = 480;
    static final int W2 = W * 2, H2 = H * 2;
    private static final int NOISE_BITS = 20;

    private RedTeamRenderer() {}

    /** Rendered JPEG frames and their timestamps in milliseconds. */
    public record Frames(List<byte[]> jpegs, long[] timestampsMillis) {
        /** SHA-256 over all frame bytes and timestamps (the determinism fingerprint of a variant). */
        public String sha256() {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                for (int i = 0; i < jpegs.size(); i++) {
                    md.update(jpegs.get(i));
                    md.update(Long.toString(timestampsMillis[i]).getBytes(StandardCharsets.US_ASCII));
                }
                return HexFormat.of().formatHex(md.digest());
            } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        }
    }

    /** Writes {@code 00001.jpg ...} and {@code timestamps.txt} into {@code dir}; returns the frames' fingerprint. */
    public static String write(RedTeamVariant v, Path dir) throws IOException {
        Frames f = render(v);
        Files.createDirectories(dir);
        StringBuilder ts = new StringBuilder();
        for (int i = 0; i < f.jpegs().size(); i++) {
            String name = String.format("%05d.jpg", i + 1);
            Files.write(dir.resolve(name), f.jpegs().get(i));
            ts.append(name).append(' ').append(f.timestampsMillis()[i]).append('\n');
        }
        Files.writeString(dir.resolve("timestamps.txt"), ts.toString(), StandardCharsets.UTF_8);
        return f.sha256();
    }

    public static Frames render(RedTeamVariant v) {
        Session s = new Session(v);
        Camera cam = new Camera(v);
        int n = (int) (v.l("s.duration_ms") * v.l("s.fps") / 1000);
        double frameMs = 1000.0 / v.l("s.fps");
        List<Long> times = new ArrayList<>();
        List<Double> contentTimes = new ArrayList<>();
        if (v.species() == AttackSpecies.SIM_REPLAY_JITTER) {
            Random r = RedTeamVariant.rng("timing|" + v.l("a.jitter_seed"));
            long prev = -1;
            for (int i = 0; i < n; i++) {
                double jit = r.nextGaussian() * v.d("a.ts_jitter_ms");
                boolean drop = r.nextDouble() < v.d("a.drop_prob");
                if (drop && i > 0) continue;                         // the replay stalls: camera sees no new frame
                long t = Math.max(prev + 1, Math.round(i * frameMs + jit));
                // content: the recording played back at the wrong rate from an offset, quantised to its own frames
                double src = v.l("a.playback_offset_ms") + t * v.d("a.playback_rate");
                long k = (long) Math.floor(src / frameMs);
                times.add(t);
                contentTimes.add((k * frameMs) % v.l("s.duration_ms"));
                prev = t;
            }
        } else {
            for (int i = 0; i < n; i++) { times.add(Math.round(i * frameMs)); contentTimes.add((double) Math.round(i * frameMs)); }
        }
        List<byte[]> out = new ArrayList<>(times.size());
        Scene scene = switch (v.species()) {
            case BONA_FIDE -> (t, ct) -> s.frame(Math.round(ct), false);
            case SIM_PRINT, SIM_PRINT_WARPED, SIM_PRINT_CUTOUT -> new PrintScene(v, s);
            default -> new ScreenScene(v, s);
        };
        for (int i = 0; i < times.size(); i++) out.add(cam.capture(scene.at(times.get(i), contentTimes.get(i)), i));
        long[] ts = new long[times.size()];
        long t0 = times.get(0);
        for (int i = 0; i < ts.length; i++) ts[i] = times.get(i) - t0;
        return new Frames(out, ts);
    }

    private interface Scene { FImg at(long tMs, double contentMs); }

    // ------------------------------------------------------------------------------------------------ live session

    /** The drawn subject, at 2x resolution. */
    static final class Session {
        final RedTeamVariant v;
        final SyntheticFaceRenderer renderer = new SyntheticFaceRenderer(W2, H2);
        final ExpressionTimeline timeline;
        final double cx, cy, scale;
        final boolean mirror;

        Session(RedTeamVariant v) {
            this.v = v;
            ExpressionTimeline.Builder b = ExpressionTimeline.builder().blinkAt(v.l("s.blink1_ms")).blinkAt(v.l("s.blink2_ms"));
            long yf = v.l("s.yaw_from_ms");
            b.yaw(yf, yf + 1400, v.d("s.yaw_deg"));
            if (v.d("s.smile") > 0.05) b.event(ExpressionEvent.between(ExpressionEvent.Action.SMILE, 0, v.l("s.duration_ms"), v.d("s.smile")));
            timeline = b.build();
            cx = v.d("s.face_cx"); cy = v.d("s.face_cy"); scale = v.d("s.face_scale"); mirror = v.b("s.mirror");
        }

        FaceState state(long tMs, boolean eyesOpen) {
            FaceState st = timeline.stateAt(tMs);
            return eyesOpen ? new FaceState(st.yawDeg(), st.pitchDeg(), 1.0, st.mouthOpen(), st.smile(), st.browRaise()) : st;
        }

        private int[] background;

        FImg frame(long tMs, boolean eyesOpen) {
            if (background == null) {
                double br = v.d("s.bg_r"), bg = v.d("s.bg_g"), bb = v.d("s.bg_b"), gx = v.d("s.bg_gx"), gy = v.d("s.bg_gy");
                background = new int[W2 * H2];
                for (int y = 0; y < H2; y++) {
                    double fy = 1 + gy * (2.0 * y / H2 - 1);
                    for (int x = 0; x < W2; x++) {
                        double f = fy + gx * (2.0 * x / W2 - 1);
                        background[y * W2 + x] = (c8(br * f) << 16) | (c8(bg * f) << 8) | c8(bb * f);
                    }
                }
            }
            BufferedImage img = new BufferedImage(W2, H2, BufferedImage.TYPE_INT_RGB);
            System.arraycopy(background, 0, ((java.awt.image.DataBufferInt) img.getRaster().getDataBuffer()).getData(), 0, background.length);
            renderer.drawFace(img, state(tMs, eyesOpen), cx, cy, scale);
            FImg f = FImg.of(img);
            return mirror ? flip(f) : f;
        }

        /** Eye centres in 2x pixels, {x0,y0,x1,y1}, where {@link #frame} draws them. */
        double[] eyes(long tMs) {
            double[] l = renderer.landmarks(state(tMs, true));
            double[] e = new double[4];
            for (int k = 0; k < 2; k++) {
                double x = cx * W2 + (l[2 * k] - 0.5 * W2) * scale;
                double y = cy * H2 + (l[2 * k + 1] - 0.5 * H2) * scale;
                e[2 * k] = mirror ? W2 - 1 - x : x;
                e[2 * k + 1] = y;
            }
            return e;
        }

        private static FImg flip(FImg f) {
            FImg o = new FImg(f.w, f.h);
            for (int y = 0; y < f.h; y++) for (int x = 0; x < f.w; x++) {
                int a = (y * f.w + x) * 3, b = (y * f.w + (f.w - 1 - x)) * 3;
                o.p[b] = f.p[a]; o.p[b + 1] = f.p[a + 1]; o.p[b + 2] = f.p[a + 2];
            }
            return o;
        }
    }

    // ------------------------------------------------------------------------------------------------ shared geometry

    /** Smooth hand-held motion: three sinusoids per component, normalised to the drawn RMS. */
    static final class Jitter {
        final double[][] f = new double[4][3], ph = new double[4][3], a = new double[4][3];
        final double[] rms;

        Jitter(RedTeamVariant v) {
            Random r = RedTeamVariant.rng("jitter|" + v.l("a.jitter_seed"));
            rms = new double[] {v.d("a.jitter_px") * 2, v.d("a.jitter_px") * 2, Math.toRadians(v.d("a.jitter_rot_deg")), v.d("a.jitter_scale")};
            for (int c = 0; c < 4; c++) for (int k = 0; k < 3; k++) {
                f[c][k] = 0.4 + r.nextDouble() * 2.1; ph[c][k] = r.nextDouble() * 2 * Math.PI; a[c][k] = 0.5 + r.nextDouble() * 0.5;
            }
        }

        /** {dx, dy (2x px), drot (rad), dscale} at time t. */
        double[] at(double tSec) {
            double[] o = new double[4];
            for (int c = 0; c < 4; c++) {
                double s = 0, norm = 0;
                for (int k = 0; k < 3; k++) { s += a[c][k] * Math.sin(2 * Math.PI * f[c][k] * tSec + ph[c][k]); norm += a[c][k] * a[c][k] / 2; }
                o[c] = s / Math.sqrt(norm) * rms[c];
            }
            return o;
        }
    }

    /** Inverse of: instrument (iw x ih) centred, sheared, scaled by s, rotated by rot, moved to the scene centre + d. */
    static final class Placement {
        final double cosr, sinr, s, shear, cx, cy, iw2, ih2;

        Placement(double iw, double ih, double s, double rot, double shear, double dx, double dy) {
            this.cosr = Math.cos(rot); this.sinr = Math.sin(rot); this.s = s; this.shear = shear;
            this.cx = W2 / 2.0 + dx; this.cy = H2 / 2.0 + dy; this.iw2 = iw / 2; this.ih2 = ih / 2;
        }

        /** Scene (X, Y) to instrument coordinates in {@code out}. */
        void inverse(double X, double Y, double[] out) {
            double qx = X - cx, qy = Y - cy;
            double rx = (qx * cosr + qy * sinr) / s, ry = (-qx * sinr + qy * cosr) / s;
            out[0] = rx - shear * ry + iw2;
            out[1] = ry + ih2;
        }
    }

    static float[] wall(RedTeamVariant v) { return new float[] {(float) v.d("a.wall_r"), (float) v.d("a.wall_g"), (float) v.d("a.wall_b")}; }

    // ------------------------------------------------------------------------------------------------ print

    static final class PrintScene implements Scene {
        final RedTeamVariant v;
        final Session s;
        final FImg sheet;               // reflectance, W2 x H2 print pixels
        final Jitter jitter;
        final float[] wall;
        final double[] stillEyes;
        final boolean warped, cutout;

        PrintScene(RedTeamVariant v, Session s) {
            this.v = v; this.s = s;
            long still = v.l("a.still_ms");
            sheet = printSheet(v, s.frame(still, true));
            stillEyes = s.eyes(still);
            jitter = new Jitter(v);
            wall = wall(v);
            warped = v.species() == AttackSpecies.SIM_PRINT_WARPED;
            cutout = v.species() == AttackSpecies.SIM_PRINT_CUTOUT;
        }

        @Override public FImg at(long tMs, double contentMs) {
            double t = tMs / 1000.0;
            double[] j = jitter.at(t);
            Placement pl = new Placement(sheet.w, sheet.h, v.d("a.scale") * (1 + j[3]), Math.toRadians(v.d("a.rotation_deg")) + j[2], v.d("a.shear"), j[0], j[1]);
            double g = v.d("a.light_gradient"), ga = v.d("a.light_angle"), gc = Math.cos(ga), gs = Math.sin(ga);
            FImg live = null;
            double[] liveEyes = null;
            double holeW = 0, holeH = 0, eyeAng = 0, rim = 0;
            if (cutout) {
                live = s.frame(tMs, false);
                liveEyes = s.eyes(tMs);
                double iod = Math.hypot(stillEyes[2] - stillEyes[0], stillEyes[3] - stillEyes[1]);
                holeW = v.d("a.eye_hole_w") * iod / 2; holeH = v.d("a.eye_hole_h") * iod / 2;
                eyeAng = Math.atan2(stillEyes[3] - stillEyes[1], stillEyes[2] - stillEyes[0]);
                rim = v.d("a.hole_rim_shadow");
            }
            double bend = 0, R = 0, half = 0;
            if (warped) {
                bend = Math.toRadians(Math.max(1e-3, v.d("a.bend_deg") + v.d("a.bend_flex_deg") * Math.sin(2 * Math.PI * v.d("a.bend_flex_hz") * t)));
                R = sheet.w / bend;
                half = R * Math.sin(bend / 2);
            }
            FImg o = new FImg(W2, H2);
            double[] q = new double[2];
            float[] px = new float[3], lp = new float[3];
            double ca = Math.cos(eyeAng), sa = Math.sin(eyeAng);
            for (int Y = 0; Y < H2; Y++) for (int X = 0; X < W2; X++) {
                int i = (Y * W2 + X) * 3;
                pl.inverse(X + 0.5, Y + 0.5, q);
                double xp = q[0], yp = q[1], shade = 1;
                if (warped) {
                    double xo = xp - sheet.w / 2.0;
                    if (Math.abs(xo) > half) { put(o, i, wall); continue; }
                    double theta = Math.asin(Math.max(-1, Math.min(1, xo / R)));
                    double z = R * (1 - Math.cos(theta));
                    double persp = 1.0 / (1.0 + z / (3.0 * sheet.w));
                    xp = theta * R + sheet.w / 2.0;
                    yp = (yp - sheet.h / 2.0) / persp + sheet.h / 2.0;
                    shade = 0.78 + 0.22 * Math.cos(theta - 0.35);
                }
                if (xp < 0 || yp < 0 || xp >= sheet.w || yp >= sheet.h) { put(o, i, wall); continue; }
                double light = shade * (1 - g * ((xp / sheet.w - 0.5) * gc + (yp / sheet.h - 0.5) * gs));
                if (cutout) {
                    boolean done = false;
                    for (int e = 0; e < 2 && !done; e++) {
                        double dx = xp - stillEyes[2 * e], dy = yp - stillEyes[2 * e + 1];
                        double u = (dx * ca + dy * sa) / holeW, w = (-dx * sa + dy * ca) / holeH;
                        double r2 = u * u + w * w;
                        if (r2 <= 1) {                       // through the hole: the subject's own eye behind the print
                            live.sample(xp + liveEyes[2 * e] - stillEyes[2 * e] - 0.5, yp + liveEyes[2 * e + 1] - stillEyes[2 * e + 1] - 0.5, lp);
                            o.p[i] = lp[0]; o.p[i + 1] = lp[1]; o.p[i + 2] = lp[2];
                            done = true;
                        } else if (r2 <= Math.pow(1 + 3.0 / Math.min(holeW, holeH), 2)) {
                            light *= 1 - rim;                // paper-edge shadow around the hole
                        }
                    }
                    if (done) continue;
                }
                sheet.sample(xp - 0.5, yp - 0.5, px);
                o.p[i] = (float) (px[0] * light); o.p[i + 1] = (float) (px[1] * light); o.p[i + 2] = (float) (px[2] * light);
            }
            return o;
        }
    }

    /** The printed sheet as reflectance in print space (the still frame's 2x pixels). */
    static FImg printSheet(RedTeamVariant v, FImg src) {
        int w = src.w, h = src.h;
        FImg cover = new FImg(w, h);
        boolean halftone = v.s("a.print_method").equals("halftone");
        double P = v.d("a.halftone_period");
        double[] ang = {Math.toRadians(15), Math.toRadians(75), 0};
        int[][] bayer = {{0, 32, 8, 40, 2, 34, 10, 42}, {48, 16, 56, 24, 50, 18, 58, 26}, {12, 44, 4, 36, 14, 46, 6, 38},
                {60, 28, 52, 20, 62, 30, 54, 22}, {3, 35, 11, 43, 1, 33, 9, 41}, {51, 19, 59, 27, 49, 17, 57, 25},
                {15, 47, 7, 39, 13, 45, 5, 37}, {63, 31, 55, 23, 61, 29, 53, 21}};
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = (y * w + x) * 3;
            for (int c = 0; c < 3; c++) {
                double ink = 1 - src.p[i + c];          // C absorbs red, M green, Y blue
                double thr;
                if (halftone) {
                    double u = x * Math.cos(ang[c]) + y * Math.sin(ang[c]), vv = -x * Math.sin(ang[c]) + y * Math.cos(ang[c]);
                    thr = 0.5 - 0.25 * (Math.cos(2 * Math.PI * u / P) + Math.cos(2 * Math.PI * vv / P));
                } else {
                    thr = (bayer[(y + 2 * c) & 7][(x + 3 * c) & 7] + 0.5) / 64.0;
                }
                cover.p[i + c] = ink > thr ? 1f : 0f;
            }
        }
        cover = cover.gaussian(v.d("a.ink_spread"));
        Random r = RedTeamVariant.rng("paper|" + v.l("a.jitter_seed"));
        int gw = w / 16 + 2, gh = h / 16 + 2;
        float[] mottle = new float[gw * gh];
        for (int k = 0; k < mottle.length; k++) mottle[k] = (float) r.nextGaussian();
        double tex = v.d("a.paper_texture"), fib = v.d("a.paper_fibre"), dens = v.d("a.ink_density");
        double[] paper = {v.d("a.paper_r"), v.d("a.paper_g"), v.d("a.paper_b")};
        FImg refl = new FImg(w, h);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            double gx = x / 16.0, gy = y / 16.0;
            int x0 = (int) gx, y0 = (int) gy;
            double fx = gx - x0, fy = gy - y0;
            double m = (mottle[y0 * gw + x0] * (1 - fx) + mottle[y0 * gw + x0 + 1] * fx) * (1 - fy)
                    + (mottle[(y0 + 1) * gw + x0] * (1 - fx) + mottle[(y0 + 1) * gw + x0 + 1] * fx) * fy;
            double mod = 1 + m * tex + r.nextGaussian() * fib;
            int i = (y * w + x) * 3;
            for (int c = 0; c < 3; c++) refl.p[i + c] = (float) (paper[c] * (1 - cover.p[i + c] * dens) * mod);
        }
        return refl;
    }

    // ------------------------------------------------------------------------------------------------ screen

    static final class ScreenScene implements Scene {
        final RedTeamVariant v;
        final Session s;
        final Jitter jitter;
        final float[] wall;
        final int dw, dh, bz;
        final double attH, attV, dim;
        FImg stillDisplay;

        ScreenScene(RedTeamVariant v, Session s) {
            this.v = v; this.s = s;
            jitter = new Jitter(v);
            wall = wall(v);
            double freq = v.d("a.display_freq"), fill = v.d("a.display_fill");
            dw = (int) Math.round(W * fill * freq);
            dh = (int) Math.round(dw * (double) H / W);
            bz = (int) Math.round(v.d("a.bezel_frac") * dw);
            // The camera optics (sigma in camera px = sigma * freq display px) attenuate the panel's subpixel stripes
            // (3 cycles per display px) and black-matrix rows (1 cycle per display px) before sampling; what is left
            // aliases into moiré when the 2x scene is box-sampled to camera pixels.
            double sd = v.d("c.blur_sigma") * freq;
            attH = Math.exp(-2 * Math.PI * Math.PI * sd * sd * 9);
            attV = Math.exp(-2 * Math.PI * Math.PI * sd * sd);
            dim = v.d("a.subpixel_dim_row");
        }

        FImg display(double contentMs) {
            boolean still = v.species() == AttackSpecies.SIM_SCREEN_PHOTO;
            if (still && stillDisplay != null) return stillDisplay;
            FImg c = still ? s.frame(v.l("a.still_ms"), true) : s.frame(Math.round(contentMs), false);
            if (v.species() == AttackSpecies.SIM_LOWRES_REPLAY) {
                double ds = v.d("a.content_downscale");
                c = jpegRoundTrip(c.areaResize((int) Math.round(W / ds), (int) Math.round(H / ds)), (int) v.l("a.content_jpeg_quality"));
            }
            if (dw <= c.w / 2) c = c.half();
            FImg d = c.areaResize(dw, dh);
            double gamma = v.d("a.display_gamma"), black = v.d("a.display_black"), sat = v.d("a.display_saturation");
            double[] white = {v.d("a.display_white_r"), 1.0, v.d("a.display_white_b")};
            float[] lut = gammaLut(gamma);
            for (int i = 0; i < d.p.length; i += 3) {
                double gm = (d.p[i] + d.p[i + 1] + d.p[i + 2]) / 3.0;
                for (int k = 0; k < 3; k++) {
                    double x = Math.max(0, Math.min(1, gm + (d.p[i + k] - gm) * sat));
                    d.p[i + k] = (float) (black + (1 - black) * lut[(int) Math.round(x * 4095)] * white[k]);
                }
            }
            if (still) stillDisplay = d;
            return d;
        }

        @Override public FImg at(long tMs, double contentMs) {
            double t = tMs / 1000.0;
            double[] j = jitter.at(t);
            FImg d = display(contentMs);
            double fill = v.d("a.display_fill");
            double sc = W2 * fill / dw * (1 + j[3]);
            int sw = dw + 2 * bz, sh = dh + 2 * bz;
            Placement pl = new Placement(sw, sh, sc, Math.toRadians(v.d("a.rotation_deg")) + j[2], v.d("a.shear"), j[0], j[1]);
            double gAmp = v.d("a.glare_amp"), gSig = v.d("a.glare_sigma_px") * 2, gEl = v.d("a.glare_elong"), gAng = v.d("a.glare_angle");
            double gcx = v.d("a.glare_cx") * W2 + j[0], gcy = v.d("a.glare_cy") * H2 + j[1];
            double gca = Math.cos(gAng), gsa = Math.sin(gAng), gReach = gAmp > 0 ? 4.5 * gSig * gEl : -1;
            double bAmp = v.d("a.band_amp"), bPer = v.d("a.band_period_px") * 2, bSpd = v.d("a.band_speed_hz");
            double m = (1 + dim) / 2;
            FImg o = new FImg(W2, H2);
            double[] q = new double[2];
            float[] px = new float[3];
            for (int Y = 0; Y < H2; Y++) {
                double band = 1 - bAmp * 0.5 * (1 + Math.cos(2 * Math.PI * (Y / bPer - bSpd * t)));
                for (int X = 0; X < W2; X++) {
                    int i = (Y * W2 + X) * 3;
                    pl.inverse(X + 0.5, Y + 0.5, q);
                    double u = q[0], vv = q[1];
                    if (u < 0 || vv < 0 || u >= sw || vv >= sh) { put(o, i, wall); continue; }
                    double dxg = X - gcx, dyg = Y - gcy;
                    float glare = 0;
                    if (Math.abs(dxg) < gReach && Math.abs(dyg) < gReach) {
                        double gu = (dxg * gca + dyg * gsa) / (gSig * gEl), gv = (-dxg * gsa + dyg * gca) / gSig;
                        glare = (float) (gAmp * Math.exp(-0.5 * (gu * gu + gv * gv)));
                    }
                    double ud = u - bz, vd = vv - bz;
                    if (ud < 0 || vd < 0 || ud >= dw || vd >= dh) {   // bezel
                        o.p[i] = 0.015f + glare; o.p[i + 1] = 0.015f + glare; o.p[i + 2] = 0.015f + glare;
                        continue;
                    }
                    d.sample(ud - 0.5, vd - 0.5, px);
                    int sub = (int) ((ud - Math.floor(ud)) * 3);
                    double row = (vd - Math.floor(vd)) < 0.5 ? 1 : dim;
                    double rowW = (m + attV * (row - m)) * band;
                    for (int k = 0; k < 3; k++) {
                        double stripe = 1 + attH * ((k == sub ? 3 : 0) - 1);
                        o.p[i + k] = (float) (px[k] * stripe * rowW) + glare;
                    }
                }
            }
            return o;
        }
    }

    // ------------------------------------------------------------------------------------------------ camera

    /** Capture camera shared by every species. */
    static final class Camera {
        final double sigma, gain, tr, tb, read, shot;
        final int quality;
        final float[] noise = new float[1 << NOISE_BITS];

        Camera(RedTeamVariant v) {
            sigma = v.d("c.blur_sigma"); gain = v.d("c.gain"); tr = v.d("c.tint_r"); tb = v.d("c.tint_b");
            read = v.d("c.noise_read"); shot = v.d("c.noise_shot"); quality = (int) v.l("c.jpeg_quality");
            Random r = new Random(v.l("c.noise_seed"));
            for (int k = 0; k < noise.length; k++) noise[k] = (float) r.nextGaussian();
        }

        byte[] capture(FImg scene2x, int frameIndex) {
            // 2x2 pixel aperture, then the optics (applied after sampling to keep the cost down; the panel grid's
            // optical attenuation is modelled analytically in ScreenScene, before sampling)
            FImg f = scene2x.half().gaussian(sigma);
            BufferedImage img = new BufferedImage(f.w, f.h, BufferedImage.TYPE_INT_RGB);
            int[] px = ((java.awt.image.DataBufferInt) img.getRaster().getDataBuffer()).getData();
            int mask = noise.length - 1, off = (int) ((frameIndex * 104_729L) & mask);
            double[] mul = {gain * tr, gain, gain * tb};
            for (int y = 0; y < f.h; y++) {
                for (int x = 0; x < f.w; x++) {
                    int i = (y * f.w + x) * 3, rgb = 0;
                    for (int k = 0; k < 3; k++) {
                        double dn = Math.max(0, Math.min(255, f.p[i + k] * mul[k] * 255.0));
                        dn += noise[(off + i + k) & mask] * Math.sqrt(read * read + shot * shot * dn);
                        rgb = (rgb << 8) | c8(dn / 255.0);
                    }
                    px[y * f.w + x] = rgb;
                }
            }
            return jpeg(img, quality);
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static float[] gammaLut(double gamma) {
        float[] l = new float[4096];
        for (int k = 0; k < l.length; k++) l[k] = (float) Math.pow(k / 4095.0, gamma);
        return l;
    }

    private static void put(FImg o, int i, float[] c) { o.p[i] = c[0]; o.p[i + 1] = c[1]; o.p[i + 2] = c[2]; }

    static int c8(double v) { long r = Math.round(v * 255.0); return (int) (r < 0 ? 0 : (r > 255 ? 255 : r)); }

    static byte[] jpeg(BufferedImage img, int quality) {
        ImageWriter wr = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(); MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(bos)) {
            ImageWriteParam p = wr.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality / 100f);
            wr.setOutput(ios);
            wr.write(null, new IIOImage(img, null, null), p);
            ios.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally { wr.dispose(); }
    }

    private static FImg jpegRoundTrip(FImg f, int quality) {
        BufferedImage img = new BufferedImage(f.w, f.h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < f.h; y++) for (int x = 0; x < f.w; x++) {
            int i = (y * f.w + x) * 3;
            img.setRGB(x, y, (c8(f.p[i]) << 16) | (c8(f.p[i + 1]) << 8) | c8(f.p[i + 2]));
        }
        try { return FImg.of(ImageIO.read(new ByteArrayInputStream(jpeg(img, quality)))); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
