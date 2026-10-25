package io.mosip.vivid.testkit.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders {@link Iso30107Metrics} as JSON and as a self-contained HTML page (inline CSS, inline SVG DET
 * curve, no external assets) suitable for CI artefacts and submission packs.
 */
public final class Iso30107Report {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Iso30107Metrics m;
    private final GroupSlices slices;   // nullable
    private final String title;
    private final Map<String, String> context;

    /** @param context free-form key/values shown in the header (engine, model bundle, corpus, date...) */
    public Iso30107Report(Iso30107Metrics metrics, String title, Map<String, String> context) {
        this(metrics, null, title, context);
    }

    /** @param slices per-group slices, or null to omit the section */
    public Iso30107Report(Iso30107Metrics metrics, GroupSlices slices, String title, Map<String, String> context) {
        this.m = metrics; this.slices = slices; this.title = title == null ? "ISO/IEC 30107-3 PAD evaluation" : title;
        this.context = context == null ? Map.of() : context;
    }

    public ObjectNode toJsonNode() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("title", title);
        ObjectNode ctx = root.putObject("context");
        context.forEach(ctx::put);
        root.put("threshold", m.threshold());
        root.put("bonaFideCount", m.bonaFideCount());
        root.put("attackCount", m.attackCount());
        root.put("confidenceInterval", "Wilson score interval, 95 % (z = 1.96)");
        ObjectNode per = root.putObject("apcerBySpecies");
        m.apcerRateBySpecies().forEach((k, r) -> {
            ObjectNode o = per.putObject(k).put("apcer", r.value()).put("count", r.n()).put("errors", r.k());
            putCi(o, r.wilson95());
        });
        root.put("maxApcer", m.maxApcer());
        root.put("worstSpecies", m.worstSpecies());
        putCi(root, "maxApcerCi95", m.maxApcerCi95());
        root.put("bpcer", m.bpcer());
        root.put("bpcerErrors", m.bpcerRate().k());
        putCi(root, "bpcerCi95", m.bpcerRate().wilson95());
        root.put("acer", m.acer());
        putNullable(root, "bpcerAtApcer5", m.bpcerAtApcer5());
        putNullable(root, "bpcerAtApcer5Threshold", m.bpcerAtApcer5Threshold());
        putNullable(root, "bpcerAtApcer10", m.bpcerAtApcer10());
        putNullable(root, "bpcerAtApcer10Threshold", m.bpcerAtApcer10Threshold());
        putNullable(root, "apnrr", m.apnrr().value());
        putNullable(root, "bpnrr", m.bpnrr().value());
        ObjectNode nr = root.putObject("nonResponse");
        nr.put("definition", "Presentations with no PAD decision: decision ERROR or failure in " + new java.util.TreeSet<>(NonResponse.FAILURES)
                + ". Excluded from APCER/BPCER. CANCELLED sessions are excluded from every rate.");
        putRate(nr.putObject("attack"), m.apnrr());
        putRate(nr.putObject("bonaFide"), m.bpnrr());
        ObjectNode nrs = nr.putObject("bySpecies");
        m.apnrrBySpecies().forEach((k, r) -> putRate(nrs.putObject(k), r));
        if (slices != null) root.set("slices", slicesJson());
        root.put("eer", m.eer());
        root.put("eerThreshold", m.eerThreshold());
        ArrayNode det = root.putArray("detCurve");
        for (DetPoint p : m.detCurve()) det.addObject().put("threshold", p.threshold()).put("apcer", p.apcer()).put("bpcer", p.bpcer());
        return root;
    }

    private static void putNullable(ObjectNode n, String k, double v) { if (Double.isNaN(v)) n.putNull(k); else n.put(k, v); }

    private static void putCi(ObjectNode n, Rate.Interval ci) { putCi(n, "ci95", ci); }

    private static void putCi(ObjectNode n, String key, Rate.Interval ci) {
        if (!ci.defined()) { n.putNull(key); return; }
        n.putArray(key).add(ci.lower()).add(ci.upper());
    }

    /** {"rate", "count" (k), "of" (n), "ci95"} */
    private static void putRate(ObjectNode n, Rate r) {
        putNullable(n, "rate", r.value());
        n.put("count", r.k()).put("of", r.n());
        putCi(n, r.wilson95());
    }

    private ObjectNode slicesJson() {
        ObjectNode sl = MAPPER.createObjectNode();
        sl.put("minSubjects", slices.minSubjects());
        sl.put("note", "Slices with fewer than minSubjects distinct participants are suppressed and expose no counts.");
        ObjectNode groups = sl.putObject("groups");
        slices.byKey().forEach((key, list) -> {
            ArrayNode arr = groups.putArray(key);
            for (GroupSlices.Slice s : list) {
                ObjectNode o = arr.addObject().put("value", s.value()).put("suppressed", s.suppressed());
                if (s.suppressed()) continue;
                o.put("subjects", s.subjects());
                putRate(o.putObject("bpcer"), s.bpcer());
                putRate(o.putObject("bpnrr"), s.bpnrr());
                if (s.bpcerRatioToLowest() == null) o.putNull("bpcerRatioToLowest"); else putNullable(o, "bpcerRatioToLowest", s.bpcerRatioToLowest());
                o.put("moreThanTwiceLowest", s.moreThanTwiceLowest());
                ObjectNode ap = o.putObject("apcerBySpecies");
                s.apcerBySpecies().forEach((k, r) -> putRate(ap.putObject(k), r));
                ObjectNode an = o.putObject("apnrrBySpecies");
                s.apnrrBySpecies().forEach((k, r) -> putRate(an.putObject(k), r));
            }
        });
        return sl;
    }

    public String toJson() {
        try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toJsonNode()); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }

    public String toHtml() {
        StringBuilder h = new StringBuilder(8192);
        h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>").append(esc(title)).append("</title>");
        h.append("<style>").append(CSS).append("</style></head><body><main>");
        h.append("<h1>").append(esc(title)).append("</h1>");
        if (!context.isEmpty()) {
            h.append("<dl class=\"ctx\">");
            context.forEach((k, v) -> h.append("<dt>").append(esc(k)).append("</dt><dd>").append(esc(v)).append("</dd>"));
            h.append("</dl>");
        }
        h.append("<section class=\"tiles\">");
        tile(h, "APCER (max)", pct(m.maxApcer()), "worst species: " + esc(m.worstSpecies()) + "<br>95% CI " + ci(m.maxApcerCi95()));
        tile(h, "BPCER", pct(m.bpcer()), m.bpcerRate().k() + " / " + m.bonaFideCount() + " bona fide<br>95% CI " + ci(m.bpcerRate().wilson95()));
        tile(h, "ACER", pct(m.acer()), "threshold " + fmt(m.threshold()));
        tile(h, "BPCER @ APCER ≤ 5%", pctOrNa(m.bpcerAtApcer5()), Double.isNaN(m.bpcerAtApcer5()) ? "not reached" : "at threshold " + fmt(m.bpcerAtApcer5Threshold()));
        tile(h, "BPCER @ APCER ≤ 10%", pctOrNa(m.bpcerAtApcer10()), Double.isNaN(m.bpcerAtApcer10()) ? "not reached" : "at threshold " + fmt(m.bpcerAtApcer10Threshold()));
        tile(h, "APNRR", pctOrNa(m.apnrr().value()), m.apnrr().k() + " / " + m.apnrr().n() + " attacks without a decision");
        tile(h, "BPNRR", pctOrNa(m.bpnrr().value()), m.bpnrr().k() + " / " + m.bpnrr().n() + " bona fide without a decision");
        tile(h, "EER", pct(m.eer()), "threshold " + fmt(m.eerThreshold()));
        h.append("</section>");
        h.append("<h2>APCER per PAI species (threshold ").append(fmt(m.threshold())).append(")</h2><div class=\"scroll\"><table><thead><tr><th>Species</th><th>Attacks decided</th><th>Accepted</th><th>APCER</th><th>95% CI</th><th>No decision</th><th>APNRR</th><th></th></tr></thead><tbody>");
        m.apnrrBySpecies().forEach((k, nr) -> {
            Rate r = m.apcerRateBySpecies().get(k);
            h.append("<tr><td>").append(esc(k)).append("</td><td>").append(r == null ? 0 : r.n()).append("</td><td>").append(r == null ? "–" : String.valueOf(r.k()))
                    .append("</td><td>").append(r == null ? "n/a" : pct(r.value())).append("</td><td>").append(r == null ? "n/a" : ci(r.wilson95()))
                    .append("</td><td>").append(nr.k()).append("</td><td>").append(pctOrNa(nr.value()))
                    .append("</td><td><div class=\"bar\"><span style=\"width:").append(fmt(r == null ? 0 : r.value() * 100)).append("%\"></span></div></td></tr>");
        });
        h.append("</tbody></table></div>");
        h.append("<p class=\"note\">Intervals are Wilson score 95 % intervals. Presentations with no PAD decision (failure to acquire, session timeout, device or engine fault) are counted as non-responses (APNRR / BPNRR) and excluded from APCER and BPCER.</p>");
        if (slices != null && !slices.isEmpty()) slicesHtml(h);
        h.append("<h2>DET curve</h2>").append(detSvg());
        h.append("<details><summary>Operating points (").append(m.detCurve().size()).append(")</summary><table><thead><tr><th>Threshold</th><th>max APCER</th><th>BPCER</th></tr></thead><tbody>");
        for (DetPoint p : m.detCurve()) h.append("<tr><td>").append(fmt(p.threshold())).append("</td><td>").append(pct(p.apcer())).append("</td><td>").append(pct(p.bpcer())).append("</td></tr>");
        h.append("</tbody></table></details>");
        h.append("<footer>Metrics as defined in ISO/IEC 30107-3. APCER is reported per presentation attack instrument species; the summary APCER is the maximum over species. A presentation is accepted when its liveness score is at or above the threshold. Self-evaluation figures; no conformance claim.</footer>");
        h.append("</main></body></html>");
        return h.toString();
    }

    private void slicesHtml(StringBuilder h) {
        h.append("<h2>Per-group slices (threshold ").append(fmt(m.threshold())).append(")</h2>");
        h.append("<p class=\"note\">Groups with fewer than ").append(slices.minSubjects()).append(" participants are suppressed. Ratio is the slice BPCER over the lowest reported BPCER for the same group; ⚑ marks a slice more than twice the lowest. At this sample size overlapping intervals are not evidence of parity.</p>");
        slices.byKey().forEach((key, list) -> {
            h.append("<h3>").append(esc(key)).append("</h3><div class=\"scroll\"><table><thead><tr><th>Value</th><th>Participants</th><th>Bona fide</th><th>BPCER</th><th>95% CI</th><th>Ratio</th><th>BPNRR</th><th>Attacks</th><th>max APCER</th></tr></thead><tbody>");
            for (GroupSlices.Slice s : list) {
                h.append("<tr><td>").append(esc(s.value())).append("</td>");
                if (s.suppressed()) { h.append("<td colspan=\"8\">&lt; ").append(slices.minSubjects()).append(" participants, suppressed</td></tr>"); continue; }
                int attacks = s.apnrrBySpecies().values().stream().mapToInt(Rate::n).sum();
                h.append("<td>").append(s.subjects()).append("</td><td>").append(s.bpcer().n()).append("</td><td>").append(pctOrNa(s.bpcer().value()))
                        .append("</td><td>").append(ci(s.bpcer().wilson95())).append("</td><td>")
                        .append(s.bpcerRatioToLowest() == null ? (s.bpcer().n() == 0 ? "n/a" : "∞") : String.format(Locale.ROOT, "%.2f", s.bpcerRatioToLowest()))
                        .append(s.moreThanTwiceLowest() ? " ⚑" : "").append("</td><td>").append(pctOrNa(s.bpnrr().value()))
                        .append("</td><td>").append(attacks).append("</td><td>").append(pctOrNa(s.maxApcer())).append("</td></tr>");
            }
            h.append("</tbody></table></div>");
        });
    }

    static String ci(Rate.Interval i) { return i.defined() ? "[" + pct(i.lower()) + ", " + pct(i.upper()) + "]" : "n/a"; }

    private String detSvg() {
        int w = 480, hgt = 360, pad = 44;
        StringBuilder s = new StringBuilder();
        s.append("<svg class=\"det\" viewBox=\"0 0 ").append(w).append(' ').append(hgt).append("\" role=\"img\" aria-label=\"DET curve: BPCER against APCER\">");
        s.append("<rect x=\"").append(pad).append("\" y=\"").append(pad / 2).append("\" width=\"").append(w - pad - pad / 2).append("\" height=\"").append(hgt - pad - pad / 2).append("\" class=\"plot\"/>");
        for (int i = 0; i <= 4; i++) {
            double f = i / 4.0;
            double x = pad + f * (w - pad - pad / 2), y = hgt - pad - f * (hgt - pad - pad / 2);
            s.append("<line x1=\"").append(x).append("\" y1=\"").append(pad / 2).append("\" x2=\"").append(x).append("\" y2=\"").append(hgt - pad).append("\" class=\"grid\"/>");
            s.append("<line x1=\"").append(pad).append("\" y1=\"").append(y).append("\" x2=\"").append(w - pad / 2).append("\" y2=\"").append(y).append("\" class=\"grid\"/>");
            s.append("<text x=\"").append(x).append("\" y=\"").append(hgt - pad + 16).append("\" class=\"tick\" text-anchor=\"middle\">").append((int) (f * 100)).append("%</text>");
            s.append("<text x=\"").append(pad - 6).append("\" y=\"").append(y + 4).append("\" class=\"tick\" text-anchor=\"end\">").append((int) (f * 100)).append("%</text>");
        }
        s.append("<text x=\"").append(w / 2).append("\" y=\"").append(hgt - 6).append("\" class=\"axis\" text-anchor=\"middle\">APCER (max over species)</text>");
        s.append("<text x=\"12\" y=\"").append(hgt / 2).append("\" class=\"axis\" text-anchor=\"middle\" transform=\"rotate(-90 12 ").append(hgt / 2).append(")\">BPCER</text>");
        StringBuilder path = new StringBuilder();
        for (DetPoint p : m.detCurve()) {
            double x = pad + p.apcer() * (w - pad - pad / 2), y = hgt - pad - p.bpcer() * (hgt - pad - pad / 2);
            path.append(path.length() == 0 ? "M" : "L").append(fmt(x)).append(' ').append(fmt(y));
        }
        s.append("<path d=\"").append(path).append("\" class=\"curve\"/>");
        double ox = pad + m.maxApcer() * (w - pad - pad / 2), oy = hgt - pad - m.bpcer() * (hgt - pad - pad / 2);
        s.append("<circle cx=\"").append(fmt(ox)).append("\" cy=\"").append(fmt(oy)).append("\" r=\"5\" class=\"op\"><title>operating point, threshold ").append(fmt(m.threshold())).append("</title></circle>");
        s.append("</svg>");
        return s.toString();
    }

    /** Write {@code <baseName>.json} and {@code <baseName>.html} into {@code dir}. */
    public void write(Path dir, String baseName) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(baseName + ".json"), toJson(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(baseName + ".html"), toHtml(), StandardCharsets.UTF_8);
    }

    private static void tile(StringBuilder h, String label, String value, String note) {
        h.append("<div class=\"tile\"><div class=\"label\">").append(label).append("</div><div class=\"value\">").append(value).append("</div><div class=\"note\">").append(note).append("</div></div>");
    }

    static String pct(double v) { return String.format(Locale.ROOT, "%.2f%%", v * 100); }
    static String pctOrNa(double v) { return Double.isNaN(v) ? "n/a" : pct(v); }
    static String fmt(double v) { return String.format(Locale.ROOT, "%.3f", v); }
    /** HTML-escape text for element content and attribute values. Shared by the testkit's HTML reports. */
    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Inline stylesheet (light/dark) shared by every testkit HTML report so they look like one family. */
    public static final String CSS = """
        :root{color-scheme:light dark;--bg:#fff;--fg:#1c1f24;--muted:#5f6670;--line:#d9dde3;--accent:#2f6fde;--tile:#f4f6f9}
        @media (prefers-color-scheme:dark){:root{--bg:#121417;--fg:#e8eaed;--muted:#a0a7b0;--line:#30353c;--accent:#7aa2ff;--tile:#1b1f25}}
        body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
        main{max-width:960px;margin:0 auto;padding:24px 16px}h1{font-size:1.6rem;margin:0 0 8px}h2{font-size:1.15rem;margin:28px 0 8px}
        .ctx{display:grid;grid-template-columns:max-content 1fr;gap:2px 16px;margin:0 0 16px;color:var(--muted)}.ctx dt{font-weight:600}.ctx dd{margin:0}
        .tiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px}
        .tile{background:var(--tile);border:1px solid var(--line);border-radius:10px;padding:12px 14px}.tile .label{font-size:.8rem;color:var(--muted)}
        .tile .value{font-size:1.5rem;font-weight:600}.tile .note{font-size:.78rem;color:var(--muted)}
        table{border-collapse:collapse;width:100%}th,td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--line)}th{color:var(--muted);font-weight:600;font-size:.85rem}
        .bar{height:8px;background:var(--line);border-radius:4px;min-width:120px}.bar span{display:block;height:100%;background:var(--accent);border-radius:4px}
        svg.det{width:100%;max-width:520px;height:auto;display:block}svg .plot{fill:var(--tile);stroke:var(--line)}svg .grid{stroke:var(--line);stroke-width:1}
        svg .tick{font-size:11px;fill:var(--muted)}svg .axis{font-size:12px;fill:var(--fg)}svg .curve{fill:none;stroke:var(--accent);stroke-width:2}svg .op{fill:var(--fg)}
        h3{font-size:1rem;margin:18px 0 6px}.scroll{overflow-x:auto}.note{font-size:.85rem;color:var(--muted)}
        details{margin-top:12px}summary{cursor:pointer;color:var(--muted)}footer{margin-top:32px;font-size:.8rem;color:var(--muted)}
        """;
}
