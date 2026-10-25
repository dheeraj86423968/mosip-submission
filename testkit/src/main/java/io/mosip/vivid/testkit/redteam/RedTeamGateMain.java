package io.mosip.vivid.testkit.redteam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosip.vivid.testkit.metrics.IsoReportMain;
import io.mosip.vivid.testkit.metrics.PadSample;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Red-team regression gate and trend writer.
 * <pre>
 * redteam-gate --variants &lt;corpus&gt;/variants.jsonl --scores passive=&lt;scores.jsonl&gt; [--scores challenge=&lt;file&gt;]
 *              [--threshold 0.80] [--baseline baseline.json] [--fixtures-out dir] [--trend trend.jsonl] [--trend-md trend.md]
 *              [--summary-md summary.md] [--summary-json summary.json] [--commit sha] [--date yyyy-mm-dd] [--source ci|local]
 *              [--runtime-seconds n] [--write-baseline baseline.json] [--export-accepted dir] [--detector-threshold 0.35]
 * </pre>
 * Each item of every scores file is classified with the {@link IsoReportMain} rules (accepted when the effective score
 * is at or above {@code --threshold}; vetoed or challenge-failed sessions score 0; non-responses excluded). Per mode
 * it computes APCER per species, BPCER and ACER = (max APCER + BPCER) / 2.
 * <p>
 * <b>Gate.</b> A <i>regression</i> is an attack variant (same variant id, so same species, render parameters and
 * generator) that the baseline records as rejected or as a non-response in a mode, and that is now accepted in that
 * mode. Each regression is written to {@code --fixtures-out} as {@code <variantId>-<mode>.json}: the full variant
 * (enough to re-render it bit for bit), the mode, threshold, score, decision and the baseline outcome; no pixels.
 * Accepted attack variants that the baseline does not know (new generator or species) are reported as new bypasses
 * but do not fail the gate. Bona fide flips are reported as warnings.
 * <p>
 * <b>Trend.</b> One JSON line per run is appended to {@code --trend}; {@code --trend-md} is re-rendered from the whole
 * file. Exit codes: 0 no regression, 1 at least one regression, 2 usage or input error.
 */
public final class RedTeamGateMain {
    public enum Outcome { ACCEPTED, REJECTED, NO_RESPONSE }

    /** One item's outcome in one mode. */
    public record Result(RedTeamVariant variant, String mode, Outcome outcome, double score, String decision, String failure, JsonNode line) {}

    /** Rates of one mode. {@code apcer} maps species to {accepted, responded}. */
    public record ModeMetrics(Map<String, int[]> apcer, int[] bpcer, int[] apnrr, int[] bpnrr) {
        public double apcerOf(String sp) { int[] c = apcer.get(sp); return c == null || c[1] == 0 ? Double.NaN : c[0] / (double) c[1]; }
        public double maxApcer() { double m = Double.NaN; for (String s : apcer.keySet()) { double a = apcerOf(s); if (!Double.isNaN(a) && (Double.isNaN(m) || a > m)) m = a; } return m; }
        public double bpcerValue() { return bpcer[1] == 0 ? Double.NaN : bpcer[0] / (double) bpcer[1]; }
        public double acer() { return (maxApcer() + bpcerValue()) / 2; }
    }

    private RedTeamGateMain() {}

    public static void main(String[] args) { System.exit(run(args)); }

    public static int run(String[] args) {
        Path variantsFile = null, baseline = null, fixturesOut = null, exportAccepted = null, trend = null, trendMd = null, summaryMd = null, summaryJson = null, writeBaseline = null;
        Map<String, Path> scores = new LinkedHashMap<>();
        double threshold = 0.80, detector = 0.35;
        String commit = "unknown", date = LocalDate.now(ZoneOffset.UTC).toString(), source = "local";
        double runtime = Double.NaN;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--variants" -> variantsFile = Path.of(args[++i]);
                    case "--scores" -> {
                        String kv = args[++i];
                        int eq = kv.indexOf('=');
                        if (eq <= 0) throw new IllegalArgumentException("--scores expects mode=file, got '" + kv + "'");
                        scores.put(kv.substring(0, eq).toLowerCase(Locale.ROOT), Path.of(kv.substring(eq + 1)));
                    }
                    case "--threshold" -> threshold = Double.parseDouble(args[++i]);
                    case "--detector-threshold" -> detector = Double.parseDouble(args[++i]);
                    case "--baseline" -> baseline = Path.of(args[++i]);
                    case "--fixtures-out" -> fixturesOut = Path.of(args[++i]);
                    case "--export-accepted" -> exportAccepted = Path.of(args[++i]);
                    case "--trend" -> trend = Path.of(args[++i]);
                    case "--trend-md" -> trendMd = Path.of(args[++i]);
                    case "--summary-md" -> summaryMd = Path.of(args[++i]);
                    case "--summary-json" -> summaryJson = Path.of(args[++i]);
                    case "--write-baseline" -> writeBaseline = Path.of(args[++i]);
                    case "--commit" -> commit = args[++i];
                    case "--date" -> date = args[++i];
                    case "--source" -> source = args[++i];
                    case "--runtime-seconds" -> runtime = Double.parseDouble(args[++i]);
                    case "--help", "-h" -> { usage(); return 0; }
                    default -> throw new IllegalArgumentException("unknown argument '" + args[i] + "'");
                }
            }
            if (variantsFile == null || scores.isEmpty()) throw new IllegalArgumentException("--variants and at least one --scores are required");
            Map<String, RedTeamVariant> byItem = readVariants(variantsFile);
            Map<String, String> shas = readShas(variantsFile);
            Map<String, List<Result>> results = new LinkedHashMap<>();
            for (Map.Entry<String, Path> e : scores.entrySet()) results.put(e.getKey(), classify(e.getKey(), e.getValue(), byItem, threshold));

            JsonNode base = baseline != null && Files.isRegularFile(baseline) ? RedTeamVariant.JSON.readTree(baseline.toFile()) : null;
            Comparison cmp = compare(results, base, shas);

            ObjectNode row = trendRow(results, threshold, commit, date, source, runtime, byItem, cmp);
            if (fixturesOut != null && !cmp.regressions().isEmpty()) {
                Files.createDirectories(fixturesOut);
                for (Regression r : cmp.regressions()) {
                    Path f = fixturesOut.resolve(r.current().variant().id() + "-" + r.current().mode() + ".json");
                    ObjectNode fx = fixture(r, threshold, detector, commit, date);
                    String sha = shas.get(r.current().variant().itemId());
                    if (sha != null) fx.put("framesSha256", sha);
                    Files.writeString(f, pretty(fx), StandardCharsets.UTF_8);
                }
            }
            if (exportAccepted != null) {           // every currently accepted attack, as known-bypass fixtures
                Files.createDirectories(exportAccepted);
                for (List<Result> rs : results.values()) for (Result r : rs) {
                    if (!r.variant().species().isAttack() || r.outcome() != Outcome.ACCEPTED) continue;
                    ObjectNode fx = fixture(new Regression(r, "n/a", Double.NaN, false), threshold, detector, commit, date);
                    fx.put("knownBypass", true);
                    fx.put("note", "Known bypass: accepted in " + r.mode() + " mode when the red-team baseline was recorded (" + commit + ", " + date
                            + "). Not fixed. RedTeamFixtureReplayTest re-renders it and reports it as skipped while it is still accepted; when the engine "
                            + "rejects it, set knownBypass to false so it guards against the bypass returning.");
                    String sha = shas.get(r.variant().itemId());
                    if (sha != null) fx.put("framesSha256", sha);
                    Files.writeString(exportAccepted.resolve(r.variant().id() + "-" + r.mode() + ".json"), pretty(fx), StandardCharsets.UTF_8);
                }
            }
            if (trend != null) {
                if (trend.toAbsolutePath().getParent() != null) Files.createDirectories(trend.toAbsolutePath().getParent());
                Files.writeString(trend, RedTeamVariant.JSON.writeValueAsString(row) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if (trendMd != null) Files.writeString(trendMd, renderTrend(readTrend(trend)), StandardCharsets.UTF_8);
            }
            String md = summary(row, cmp);
            if (summaryMd != null) Files.writeString(summaryMd, md, StandardCharsets.UTF_8);
            if (summaryJson != null) {
                ObjectNode s = row.deepCopy();
                ArrayNode regs = s.putArray("regressionList");
                for (Regression r : cmp.regressions()) regs.addObject().put("variantId", r.current().variant().id()).put("mode", r.current().mode())
                        .put("item", r.current().variant().itemId()).put("score", r.current().score()).put("baseline", r.baselineOutcome());
                ArrayNode nb = s.putArray("newBypassList");
                for (Result r : cmp.newBypasses()) nb.addObject().put("variantId", r.variant().id()).put("mode", r.mode()).put("item", r.variant().itemId()).put("score", r.score());
                Files.writeString(summaryJson, pretty(s), StandardCharsets.UTF_8);
            }
            if (writeBaseline != null) Files.writeString(writeBaseline, pretty(baselineJson(results, threshold, commit, date, byItem, shas)), StandardCharsets.UTF_8);
            System.out.print(md);
            return cmp.regressions().isEmpty() ? 0 : 1;
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            System.err.println("redteam-gate: " + (e instanceof ArrayIndexOutOfBoundsException ? "missing value for last option" : e.getMessage()));
            usage();
            return 2;
        } catch (IOException e) {
            System.err.println("redteam-gate: " + e.getMessage());
            return 2;
        }
    }

    // ------------------------------------------------------------------------------------------------ inputs

    static Map<String, RedTeamVariant> readVariants(Path f) throws IOException {
        Map<String, RedTeamVariant> out = new LinkedHashMap<>();
        for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (l.isBlank() || l.startsWith("#")) continue;
            RedTeamVariant v = RedTeamVariant.fromJson(RedTeamVariant.JSON.readTree(l));
            out.put(v.itemId(), v);
        }
        return out;
    }

    static Map<String, String> readShas(Path f) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (l.isBlank() || l.startsWith("#")) continue;
            JsonNode j = RedTeamVariant.JSON.readTree(l);
            if (j.hasNonNull("framesSha256")) out.put(j.path("item").asText(), j.get("framesSha256").asText());
        }
        return out;
    }

    static List<Result> classify(String mode, Path scores, Map<String, RedTeamVariant> byItem, double threshold) throws IOException {
        Map<String, JsonNode> lines = new LinkedHashMap<>();
        for (String l : Files.readAllLines(scores, StandardCharsets.UTF_8)) {
            if (l.isBlank() || l.startsWith("#")) continue;
            JsonNode j = RedTeamVariant.JSON.readTree(l);
            lines.put(j.path("id").asText(), j);
        }
        List<Result> out = new ArrayList<>();
        for (PadSample s : IsoReportMain.readSamples(scores)) {
            RedTeamVariant v = byItem.get(s.id());
            if (v == null) throw new IllegalArgumentException(scores + ": item " + s.id() + " is not in the variants file");
            JsonNode j = lines.get(s.id());
            Outcome o = !s.responded() ? Outcome.NO_RESPONSE : s.acceptedAt(threshold) ? Outcome.ACCEPTED : Outcome.REJECTED;
            out.add(new Result(v, mode, o, j.path("score").asDouble(Double.NaN), j.path("decision").asText(null),
                    j.hasNonNull("failure") ? j.get("failure").asText() : null, j));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ metrics

    public static ModeMetrics metrics(List<Result> results) {
        Map<String, int[]> apcer = new TreeMap<>(), apnrrBy = new TreeMap<>();
        int[] bpcer = new int[2], apnrr = new int[2], bpnrr = new int[2];
        for (Result r : results) {
            boolean bona = !r.variant().species().isAttack();
            if (bona) {
                bpnrr[1]++;
                if (r.outcome() == Outcome.NO_RESPONSE) { bpnrr[0]++; continue; }
                bpcer[1]++;
                if (r.outcome() == Outcome.REJECTED) bpcer[0]++;
            } else {
                apnrr[1]++;
                int[] c = apcer.computeIfAbsent(r.variant().species().label(), k -> new int[2]);
                if (r.outcome() == Outcome.NO_RESPONSE) { apnrr[0]++; continue; }
                c[1]++;
                if (r.outcome() == Outcome.ACCEPTED) c[0]++;
            }
        }
        return new ModeMetrics(apcer, bpcer, apnrr, bpnrr);
    }

    // ------------------------------------------------------------------------------------------------ gate

    /** {@code renderDrift}: the frames differ from the baseline render (other JDK or platform), so the engine saw different pixels. */
    public record Regression(Result current, String baselineOutcome, double baselineScore, boolean renderDrift) {}

    public record Comparison(List<Regression> regressions, List<Result> newBypasses, List<Result> fixed, List<String> bonaFideFlips, int baselineMissing, int known, int renderDrift) {}

    static Comparison compare(Map<String, List<Result>> results, JsonNode base, Map<String, String> shas) {
        List<Regression> reg = new ArrayList<>();
        List<Result> fresh = new ArrayList<>(), fixed = new ArrayList<>();
        List<String> flips = new ArrayList<>();
        int missing = 0, known = 0, drift = 0;
        for (Map.Entry<String, List<Result>> e : results.entrySet()) {
            JsonNode modeBase = base == null ? null : base.path("modes").get(e.getKey());
            TreeSet<String> seen = new TreeSet<>();
            for (Result r : e.getValue()) {
                String id = r.variant().id();
                seen.add(id);
                JsonNode b = modeBase == null ? null : modeBase.get(id);
                if (b == null) {
                    if (r.variant().species().isAttack() && r.outcome() == Outcome.ACCEPTED) fresh.add(r);
                    continue;
                }
                known++;
                String sha = shas.get(r.variant().itemId());
                boolean drifted = sha != null && b.hasNonNull("framesSha256") && !b.get("framesSha256").asText().equals(sha);
                if (drifted) drift++;
                String was = b.path("outcome").asText();
                if (r.variant().species().isAttack()) {
                    if (r.outcome() == Outcome.ACCEPTED && !was.equals(Outcome.ACCEPTED.name())) reg.add(new Regression(r, was, b.path("score").asDouble(Double.NaN), drifted));
                    else if (r.outcome() != Outcome.ACCEPTED && was.equals(Outcome.ACCEPTED.name())) fixed.add(r);
                } else if (!was.equals(r.outcome().name())) {
                    flips.add(e.getKey() + " " + r.variant().itemId() + " (" + id + "): " + was + " -> " + r.outcome());
                }
            }
            if (modeBase != null) for (var it = modeBase.fieldNames(); it.hasNext(); ) if (!seen.contains(it.next())) missing++;
        }
        return new Comparison(reg, fresh, fixed, flips, missing, known, drift);
    }

    static ObjectNode fixture(Regression r, double threshold, double detector, String commit, String date) {
        Result c = r.current();
        ObjectNode n = c.variant().toJson();
        n.put("mode", c.mode());
        n.put("workflow", "RESIDENT");
        n.put("threshold", threshold);
        n.put("detectorThreshold", detector);
        n.put("knownBypass", false);
        n.put("note", "Found by the nightly red team: accepted in " + c.mode() + " mode at " + commit + " (" + date + "), baseline "
                + r.baselineOutcome() + ". Committing this file makes RedTeamFixtureReplayTest demand that it is rejected; set knownBypass "
                + "to true (with a reason) only to record a bypass that is not fixed yet.");
        ObjectNode obs = n.putObject("observed");
        obs.put("commit", commit).put("date", date).put("score", c.score()).put("decision", c.decision());
        if (c.failure() == null) obs.putNull("failure"); else obs.put("failure", c.failure());
        if (c.line().has("signals")) obs.set("signals", c.line().get("signals"));
        obs.put("baselineOutcome", r.baselineOutcome());
        obs.put("renderDrift", r.renderDrift());
        if (!Double.isNaN(r.baselineScore())) obs.put("baselineScore", r.baselineScore());
        return n;
    }

    static ObjectNode baselineJson(Map<String, List<Result>> results, double threshold, String commit, String date, Map<String, RedTeamVariant> byItem, Map<String, String> shas) {
        ObjectNode n = RedTeamVariant.JSON.createObjectNode();
        n.put("generator", RedTeamVariant.GENERATOR);
        n.put("threshold", threshold);
        n.put("commit", commit);
        n.put("date", date);
        n.put("variants", byItem.size());
        n.put("note", "Per-variant outcomes of the red-team run the gate compares against. Regenerate with tools/redteam/run-redteam.sh --update-baseline only after reviewing the change.");
        ObjectNode modes = n.putObject("modes");
        for (Map.Entry<String, List<Result>> e : results.entrySet()) {
            ObjectNode m = modes.putObject(e.getKey());
            Map<String, ObjectNode> sorted = new TreeMap<>();
            for (Result r : e.getValue()) {
                ObjectNode o = RedTeamVariant.JSON.createObjectNode();
                o.put("item", r.variant().itemId()).put("species", r.variant().species().label()).put("outcome", r.outcome().name())
                        .put("score", Math.round(r.score() * 1e4) / 1e4).put("decision", r.decision());
                if (r.failure() != null) o.put("failure", r.failure());
                if (shas.containsKey(r.variant().itemId())) o.put("framesSha256", shas.get(r.variant().itemId()));
                sorted.put(r.variant().id(), o);
            }
            sorted.forEach(m::set);
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------------ trend

    static ObjectNode trendRow(Map<String, List<Result>> results, double threshold, String commit, String date, String source, double runtime,
                               Map<String, RedTeamVariant> byItem, Comparison cmp) {
        ObjectNode n = RedTeamVariant.JSON.createObjectNode();
        n.put("date", date).put("commit", commit).put("source", source).put("generator", RedTeamVariant.GENERATOR).put("threshold", threshold);
        n.put("variants", byItem.size());
        n.put("bonaFide", byItem.values().stream().filter(v -> !v.species().isAttack()).count());
        n.put("attacks", byItem.values().stream().filter(v -> v.species().isAttack()).count());
        if (!Double.isNaN(runtime)) n.put("runtimeSeconds", Math.round(runtime));
        for (Map.Entry<String, List<Result>> e : results.entrySet()) {
            ModeMetrics m = metrics(e.getValue());
            ObjectNode mo = n.putObject(e.getKey());
            ObjectNode ap = mo.putObject("apcer");
            m.apcer().forEach((sp, c) -> ap.putArray(sp).add(c[0]).add(c[1]));
            mo.put("maxApcer", round4(m.maxApcer()));
            mo.putArray("bpcer").add(m.bpcer()[0]).add(m.bpcer()[1]);
            mo.put("bpcerRate", round4(m.bpcerValue()));
            mo.put("acer", round4(m.acer()));
            mo.putArray("apnrr").add(m.apnrr()[0]).add(m.apnrr()[1]);
            mo.putArray("bpnrr").add(m.bpnrr()[0]).add(m.bpnrr()[1]);
        }
        n.put("regressions", cmp.regressions().size());
        n.put("newBypasses", cmp.newBypasses().size());
        n.put("fixed", cmp.fixed().size());
        n.put("bonaFideFlips", cmp.bonaFideFlips().size());
        n.put("renderDrift", cmp.renderDrift());
        return n;
    }

    static List<JsonNode> readTrend(Path f) throws IOException {
        List<JsonNode> rows = new ArrayList<>();
        for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) if (!l.isBlank() && !l.startsWith("#")) rows.add(RedTeamVariant.JSON.readTree(l));
        return rows;
    }

    /** Markdown table of every trend row (newest last). Rates are "accepted/responded" counts with the percentage. */
    public static String renderTrend(List<JsonNode> rows) {
        List<String> species = new ArrayList<>();
        for (AttackSpecies sp : AttackSpecies.values()) if (sp.isAttack()) species.add(sp.label());
        StringBuilder sb = new StringBuilder();
        sb.append("# Red-team trend\n\nGenerated by `RedTeamGateMain` from `trend.jsonl`. Passive replay, RESIDENT policy; APCER per species as "
                + "accepted/responded attacks. Column meanings: `docs/testing/redteam-trend.md`.\n\n");
        sb.append("| Date | Commit | Source | Items (BF/attack) |");
        for (String s : species) sb.append(' ').append(s.replace("SIM_", "")).append(" |");
        sb.append(" Max APCER | BPCER | ACER | Challenge max APCER | Regressions | Run time |\n|---|---|---|---|");
        for (int i = 0; i < species.size(); i++) sb.append("---|");
        sb.append("---|---|---|---|---|---|\n");
        for (JsonNode r : rows) {
            JsonNode p = r.path("passive");
            sb.append("| ").append(r.path("date").asText()).append(" | ").append(r.path("commit").asText()).append(" | ").append(r.path("source").asText())
                    .append(" | ").append(r.path("variants").asInt()).append(" (").append(r.path("bonaFide").asInt()).append('/').append(r.path("attacks").asInt()).append(") |");
            for (String s : species) sb.append(' ').append(rate(p.path("apcer").get(s))).append(" |");
            sb.append(' ').append(pct(p.path("maxApcer"))).append(" | ").append(rate(p.get("bpcer"))).append(" | ").append(pct(p.path("acer"))).append(" | ");
            JsonNode c = r.get("challenge");
            sb.append(c == null ? "n/a" : pct(c.path("maxApcer"))).append(" | ").append(r.path("regressions").asInt()).append(" | ")
                    .append(r.has("runtimeSeconds") ? r.get("runtimeSeconds").asLong() + " s" : "n/a").append(" |\n");
        }
        return sb.toString();
    }

    static String summary(ObjectNode row, Comparison cmp) {
        StringBuilder sb = new StringBuilder("## Red-team run ").append(row.path("date").asText()).append(" @ ").append(row.path("commit").asText()).append("\n\n");
        sb.append(renderTrend(List.of(row)).replaceFirst("(?s)^# Red-team trend\\n\\n.*?\\n\\n", "")).append('\n');
        JsonNode c = row.get("challenge");
        if (c != null) {
            sb.append("Challenge mode (recordings cannot answer prompts, so BPCER there is not meaningful): APCER ");
            List<String> parts = new ArrayList<>();
            c.path("apcer").fields().forEachRemaining(e -> parts.add(e.getKey() + " " + rate(e.getValue())));
            sb.append(String.join(", ", parts)).append(".\n\n");
        }
        sb.append("Compared with baseline: ").append(cmp.known()).append(" known (variant, mode) pairs, ").append(cmp.regressions().size()).append(" regression(s), ")
                .append(cmp.fixed().size()).append(" previously accepted attack(s) now rejected, ").append(cmp.newBypasses().size())
                .append(" accepted attack(s) not in the baseline, ").append(cmp.bonaFideFlips().size()).append(" bona fide flip(s), ")
                .append(cmp.baselineMissing()).append(" baseline entr(ies) not produced by this run, ").append(cmp.renderDrift())
                .append(" (variant, mode) pair(s) whose frames differ from the baseline render.\n");
        if (!cmp.regressions().isEmpty()) {
            sb.append("\n### Regressions (previously rejected attacks now accepted)\n\n| Variant | Mode | Item | Score | Baseline | Render drift |\n|---|---|---|---|---|---|\n");
            for (Regression r : cmp.regressions())
                sb.append(String.format(Locale.ROOT, "| `%s` | %s | %s | %.3f | %s | %s |%n", r.current().variant().id(), r.current().mode(), r.current().variant().itemId(),
                        r.current().score(), r.baselineOutcome(), r.renderDrift() ? "yes (re-baseline on this platform if no engine change explains it)" : "no"));
        }
        if (!cmp.bonaFideFlips().isEmpty()) {
            sb.append("\n### Bona fide flips (warning)\n\n");
            for (String f : cmp.bonaFideFlips()) sb.append("- ").append(f).append('\n');
        }
        return sb.toString();
    }

    private static String rate(JsonNode c) {
        if (c == null || !c.isArray() || c.size() < 2) return "n/a";
        int k = c.get(0).asInt(), n = c.get(1).asInt();
        return n == 0 ? "n/a" : String.format(Locale.ROOT, "%d/%d (%.0f %%)", k, n, 100.0 * k / n);
    }

    private static String pct(JsonNode v) { return v == null || !v.isNumber() ? "n/a" : String.format(Locale.ROOT, "%.1f %%", 100 * v.asDouble()); }

    private static Double round4(double v) { return Double.isNaN(v) ? null : Math.round(v * 1e4) / 1e4; }

    private static String pretty(JsonNode n) throws IOException {
        return RedTeamVariant.JSON.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(n) + "\n";
    }

    private static void usage() {
        System.err.println("usage: redteam-gate --variants variants.jsonl --scores passive=file [--scores challenge=file] [--threshold 0.80] [--detector-threshold 0.35] "
                + "[--baseline baseline.json] [--fixtures-out dir] [--trend trend.jsonl] [--trend-md trend.md] [--summary-md f] [--summary-json f] "
                + "[--commit sha] [--date d] [--source ci|local] [--runtime-seconds n] [--write-baseline f] [--export-accepted dir]");
    }
}
