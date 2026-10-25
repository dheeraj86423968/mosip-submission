package io.mosip.vivid.testkit.redteam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.TreeMap;

/**
 * One red-team presentation: a species plus every render parameter, drawn once from a seeded generator and then
 * stored explicitly. {@link RedTeamRenderer} reads only {@link #params()}, so a variant can be re-rendered
 * bit-for-bit from its JSON alone (that is what a regression fixture is).
 *
 * <p>The {@linkplain #id() variant id} is {@code <species>-<10 hex>}: the first 40 bits of SHA-256 over the
 * generator version, the species and the canonical (key-sorted) parameter JSON. It is stable for as long as the
 * generator and its parameter ranges are unchanged; changing either yields new ids, which the gate treats as new
 * variants rather than as regressions.
 */
public record RedTeamVariant(AttackSpecies species, int subject, int take, Map<String, Object> params) {
    /** Bump when the renderer or a parameter range changes in a way that alters pixels. */
    public static final String GENERATOR = "redteam-gen-1";

    static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public RedTeamVariant {
        Objects.requireNonNull(species);
        params = Collections.unmodifiableMap(new TreeMap<>(params));
    }

    /** {@code P07-SIM_PRINT-L0-D0-T1}: the docs/corpus-ethics.md §10.1 item name used as the corpus folder. */
    public String itemId() { return String.format("P%02d-%s-L0-D0-T%d", subject, species.label(), take); }

    public String participant() { return String.format("P%02d", subject); }

    public String id() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((GENERATOR + "|" + species.name() + "|").getBytes(StandardCharsets.UTF_8));
            md.update(JSON.writeValueAsBytes(params));
            return species.label() + "-" + HexFormat.of().formatHex(md.digest()).substring(0, 10);
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------------------------ JSON

    public ObjectNode toJson() {
        ObjectNode n = JSON.createObjectNode();
        n.put("variantId", id());
        n.put("item", itemId());
        n.put("species", species.label());
        n.put("paiClass", species.paiClass());
        n.put("subject", subject);
        n.put("take", take);
        n.put("generator", GENERATOR);
        n.set("params", JSON.valueToTree(params));
        return n;
    }

    /** Parses {@link #toJson()} output (extra fields such as score or decision are ignored). */
    public static RedTeamVariant fromJson(JsonNode n) {
        String gen = n.path("generator").asText(GENERATOR);
        if (!GENERATOR.equals(gen))
            throw new IllegalArgumentException("variant " + n.path("variantId").asText() + " was made by " + gen + ", this renderer is " + GENERATOR);
        Map<String, Object> params = new TreeMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = n.get("params").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            Object o = v.isBoolean() ? (Object) v.asBoolean() : v.isIntegralNumber() ? (Object) v.asLong() : v.isNumber() ? (Object) v.asDouble() : v.asText();
            params.put(e.getKey(), o);
        }
        RedTeamVariant r = new RedTeamVariant(AttackSpecies.parse(n.get("species").asText()), n.path("subject").asInt(), n.path("take").asInt(1), params);
        if (n.hasNonNull("variantId") && !n.get("variantId").asText().equals(r.id()))
            throw new IllegalArgumentException("variant id mismatch: recorded " + n.get("variantId").asText() + ", recomputed " + r.id());
        return r;
    }

    // ------------------------------------------------------------------------------------------------ params

    public double d(String k) { return ((Number) req(k)).doubleValue(); }
    public long l(String k) { return ((Number) req(k)).longValue(); }
    public int i(String k) { return (int) l(k); }
    public boolean b(String k) { return (Boolean) req(k); }
    public String s(String k) { return (String) req(k); }
    public boolean has(String k) { return params.containsKey(k); }

    private Object req(String k) {
        Object v = params.get(k);
        if (v == null) throw new IllegalArgumentException(id() + ": missing parameter " + k);
        return v;
    }

    // ------------------------------------------------------------------------------------------------ drawing

    /**
     * Draws every parameter of a variant. The live session ({@code s.*}) is drawn from (seed, subject, take), so all
     * attack species of a subject are made from the same live session; the attack ({@code a.*}) and capture camera
     * ({@code c.*}) parameters from (seed, subject, take, species).
     */
    public static RedTeamVariant draw(AttackSpecies sp, int subject, int take, String seed) {
        Map<String, Object> p = new TreeMap<>();
        Draw s = new Draw(rng(seed + "|P" + subject + "|T" + take + "|session"), p);
        s.u("s.face_scale", 0.78, 1.02);
        s.u("s.face_cx", 0.44, 0.56);
        s.u("s.face_cy", 0.46, 0.54);
        s.u("s.bg_r", 0.15, 0.60); s.u("s.bg_g", 0.15, 0.60); s.u("s.bg_b", 0.15, 0.60);
        s.u("s.bg_gx", -0.15, 0.15); s.u("s.bg_gy", -0.15, 0.15);
        s.l("s.blink1_ms", 300, 1500);
        s.l("s.blink2_ms", 2000, 3600);
        s.l("s.yaw_from_ms", 500, 2500);
        s.u("s.yaw_deg", 2.0, 9.0);
        s.u("s.smile", 0.0, 0.35);
        s.bool("s.mirror", 0.5);
        s.l("s.duration_ms", 4000, 4000);
        s.l("s.fps", 15, 15);

        Draw a = new Draw(rng(seed + "|P" + subject + "|T" + take + "|" + sp.name()), p);
        // capture camera, every species: optics, room light / exposure, sensor noise, JPEG
        a.u("c.blur_sigma", 0.30, 0.60);
        a.u("c.gain", 0.88, 1.12);
        a.u("c.tint_r", 0.94, 1.06); a.u("c.tint_b", 0.94, 1.06);
        a.u("c.noise_read", 1.0, 2.5);
        a.u("c.noise_shot", 0.15, 0.35);
        a.l("c.jpeg_quality", 85, 95);
        a.p.put("c.noise_seed", a.r.nextLong() & 0x7fffffffffffL);
        switch (sp) {
            case BONA_FIDE -> { }
            case SIM_PRINT, SIM_PRINT_WARPED, SIM_PRINT_CUTOUT -> {
                a.l("a.still_ms", 600, 3400);
                a.pick("a.print_method", "halftone", "dither");
                a.u("a.halftone_period", 2.4, 4.2);         // print px per cell (2 print px = 1 camera px)
                a.u("a.ink_spread", 0.5, 1.1);              // print px
                a.u("a.ink_density", 0.80, 0.95);
                a.u("a.paper_r", 0.93, 0.97); a.u("a.paper_g", 0.92, 0.96); a.u("a.paper_b", 0.86, 0.93);
                a.u("a.paper_texture", 0.015, 0.04);
                a.u("a.paper_fibre", 0.005, 0.015);
                a.u("a.scale", 0.85, 1.10);
                a.u("a.rotation_deg", -4.0, 4.0);
                a.u("a.shear", -0.04, 0.04);
                a.u("a.light_gradient", 0.0, 0.25);
                a.u("a.light_angle", 0.0, 2 * Math.PI);
                jitter(a, 1.0);
                wall(a);
                if (sp == AttackSpecies.SIM_PRINT_WARPED) {
                    a.u("a.bend_deg", 35.0, 80.0);
                    a.u("a.bend_flex_deg", 0.0, 10.0);
                    a.u("a.bend_flex_hz", 0.2, 0.7);
                }
                if (sp == AttackSpecies.SIM_PRINT_CUTOUT) {
                    a.u("a.eye_hole_w", 0.42, 0.52);         // inter-ocular distances
                    a.u("a.eye_hole_h", 0.20, 0.28);
                    a.u("a.hole_rim_shadow", 0.25, 0.5);
                }
            }
            case SIM_SCREEN_REPLAY, SIM_SCREEN_PHOTO, SIM_REPLAY_JITTER, SIM_LOWRES_REPLAY -> {
                boolean fine = sp == AttackSpecies.SIM_REPLAY_JITTER || sp == AttackSpecies.SIM_LOWRES_REPLAY;
                if (sp == AttackSpecies.SIM_SCREEN_PHOTO) a.l("a.still_ms", 600, 3400);
                if (fine) { a.u("a.display_freq", 1.8, 2.6); a.u("a.display_fill", 1.02, 1.15); }
                else { a.u("a.display_freq", 0.6, 1.4); a.u("a.display_fill", 0.80, 1.12); }
                a.u("a.bezel_frac", 0.03, 0.08);
                a.u("a.subpixel_dim_row", 0.45, 0.70);
                a.u("a.display_gamma", 0.85, 1.15);
                a.u("a.display_white_r", 0.90, 0.98); a.u("a.display_white_b", 1.02, 1.10);
                a.u("a.display_black", 0.02, 0.05);
                a.u("a.display_saturation", 0.90, 1.20);
                a.u("a.glare_amp", fine ? 0.0 : 0.08, fine ? 0.10 : 0.30);
                a.u("a.glare_sigma_px", 35.0, 90.0);
                a.u("a.glare_elong", 1.0, 2.5);
                a.u("a.glare_angle", 0.0, Math.PI);
                a.u("a.glare_cx", 0.2, 0.8); a.u("a.glare_cy", 0.15, 0.85);
                a.u("a.band_amp", 0.015, 0.06);
                a.u("a.band_period_px", 120.0, 480.0);
                a.u("a.band_speed_hz", 0.2, 1.5);
                a.u("a.rotation_deg", -4.0, 4.0);
                a.u("a.shear", -0.025, 0.025);
                boolean held = a.bool("a.held", 0.5);
                jitter(a, held ? 1.0 : 0.15);
                wall(a);
                if (sp == AttackSpecies.SIM_REPLAY_JITTER) {
                    a.u("a.playback_rate", 0.85, 1.15);
                    a.l("a.playback_offset_ms", 0, 400);
                    a.u("a.ts_jitter_ms", 4.0, 25.0);
                    a.u("a.drop_prob", 0.02, 0.15);
                }
                if (sp == AttackSpecies.SIM_LOWRES_REPLAY) {
                    a.u("a.content_downscale", 2.5, 5.0);    // camera px per recorded px
                    a.l("a.content_jpeg_quality", 25, 55);
                    a.p.put("c.blur_sigma", round(0.8 + a.r.nextDouble() * 0.8));
                    a.l("c.jpeg_quality", 50, 75);
                }
            }
        }
        return new RedTeamVariant(sp, subject, take, p);
    }

    private static void jitter(Draw a, double scale) {
        a.p.put("a.jitter_px", round(a.r.nextDouble() * 3.5 * scale + 1.5 * scale));
        a.p.put("a.jitter_rot_deg", round((0.2 + a.r.nextDouble() * 0.6) * scale));
        a.p.put("a.jitter_scale", round((0.003 + a.r.nextDouble() * 0.009) * scale));
        a.p.put("a.jitter_seed", a.r.nextLong() & 0x7fffffffffffL);
    }

    private static void wall(Draw a) {
        a.u("a.wall_r", 0.25, 0.75); a.u("a.wall_g", 0.25, 0.75); a.u("a.wall_b", 0.25, 0.75);
    }

    static Random rng(String key) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            long v = 0;
            for (int k = 0; k < 8; k++) v = (v << 8) | (h[k] & 0xff);
            return new Random(v);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static double round(double v) { return Math.round(v * 1e4) / 1e4; }

    private record Draw(Random r, Map<String, Object> p) {
        double u(String k, double lo, double hi) { double v = round(lo + r.nextDouble() * (hi - lo)); p.put(k, v); return v; }
        long l(String k, long lo, long hi) { long v = lo + (hi > lo ? (long) Math.floor(r.nextDouble() * (hi - lo + 1)) : 0); p.put(k, v); return v; }
        boolean bool(String k, double prob) { boolean v = r.nextDouble() < prob; p.put(k, v); return v; }
        void pick(String k, String... options) { p.put(k, options[r.nextInt(options.length)]); }
    }
}
