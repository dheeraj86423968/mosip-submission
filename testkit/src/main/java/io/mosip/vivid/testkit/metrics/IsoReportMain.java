package io.mosip.vivid.testkit.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * CLI used by CI: {@code iso-report --scores scores.jsonl --out target/iso-report [--threshold 0.5] [--title T]
 * [--context key=value]... [--max-acer 0.1] [--min-subjects 5] [--no-slices]}.
 *
 * <p>Input is JSON Lines, one presentation per line (the format written by
 * {@code io.mosip.vivid.testkit.corpus.CorpusScoreMain}; only {@code label}/{@code pais}/{@code bonaFide} and
 * {@code score} are required, so older files still load):
 * <pre>{@code
 * {"label":"BONA_FIDE","score":0.93,"id":"subj01-take1"}
 * {"label":"PRINT","score":0.12}
 * {"label":"LIVE","score":0.88}                 // LIVE, BONA_FIDE and BONAFIDE are synonyms
 * {"bonaFide":false,"pais":"SCREEN_REPLAY","score":0.40}
 * {"id":"P07-PRINT_A4_MATTE-L1-D1-T1","subject":"P07","label":"PRINT_A4_MATTE","score":0.12,
 *  "decision":"ATTACK","failure":"PAD_DETECTED","response":true,
 *  "groups":{"skinToneBand":"4-7","ageBand":"18-29","eyewear":"none","lighting":"L1","device":"D1"}}
 * {"id":"P07-BONA_FIDE-L3-D1-T1","label":"BONA_FIDE","decision":"NOT_LIVE","failure":"FACE_NOT_DETECTED","response":false}
 * }</pre>
 * <ul>
 *   <li>{@code score}: liveness score in [0,1] (1 = live). Required unless the line is a non-response.</li>
 *   <li>{@code response}: false marks a non-response (no PAD decision; counts towards APNRR/BPNRR only). When absent
 *       it is derived from {@code decision}/{@code failure} with {@link NonResponse}; when those are absent too the
 *       line is a response. {@code decision} or {@code failure} {@code CANCELLED} excludes the line from every rate.</li>
 *   <li>{@code decision}/{@code failure}: a response rejected for any reason other than
 *       {@code LIVENESS_BELOW_THRESHOLD} (hard veto, challenge failure, identity drift) is classified with score 0, so
 *       it counts as rejected at every threshold ({@link #effectiveScore}).</li>
 *   <li>{@code subject}: participant id for slice suppression; defaults to the part of {@code id} before the first '-'.</li>
 *   <li>{@code groups}: object of string tags. The top-level keys {@link #GROUP_KEYS} are accepted as tags too.</li>
 * </ul>
 * Blank lines and lines starting with {@code #} are ignored. Writes {@code iso30107.json} and {@code iso30107.html}
 * into the output directory and prints a one-line summary.
 * Exit codes: 0 ok, 1 ACER above {@code --max-acer}, 2 usage or input error.
 */
public final class IsoReportMain {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Top-level fields read as group tags in addition to the {@code groups} object. */
    public static final List<String> GROUP_KEYS = List.of("skinToneBand", "ageBand", "gender", "eyewear", "headCovering", "facialHair", "lighting", "device");

    /** A parsed score file: the samples plus the number of excluded (cancelled) lines. */
    public record ScoreFile(List<PadSample> samples, int excluded) {}

    private IsoReportMain() {}

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** Testable entry point; returns the exit code. */
    public static int run(String[] args) {
        Path scores = null, out = null;
        double threshold = 0.5, maxAcer = Double.NaN;
        int minSubjects = GroupSlices.MIN_SUBJECTS_FLOOR;
        boolean slicesOn = true;
        String title = "ISO/IEC 30107-3 PAD evaluation";
        Map<String, String> context = new LinkedHashMap<>();
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--scores" -> scores = Path.of(args[++i]);
                    case "--out" -> out = Path.of(args[++i]);
                    case "--threshold" -> threshold = Double.parseDouble(args[++i]);
                    case "--title" -> title = args[++i];
                    case "--max-acer" -> maxAcer = Double.parseDouble(args[++i]);
                    case "--min-subjects" -> minSubjects = Integer.parseInt(args[++i]);
                    case "--no-slices" -> slicesOn = false;
                    case "--context" -> {
                        String kv = args[++i];
                        int eq = kv.indexOf('=');
                        if (eq <= 0) throw new IllegalArgumentException("--context expects key=value, got '" + kv + "'");
                        context.put(kv.substring(0, eq), kv.substring(eq + 1));
                    }
                    case "--help", "-h" -> { usage(); return 0; }
                    default -> throw new IllegalArgumentException("unknown argument '" + args[i] + "'");
                }
            }
            if (scores == null || out == null) throw new IllegalArgumentException("--scores and --out are required");
            ScoreFile file = readScores(scores);
            List<PadSample> samples = file.samples();
            Iso30107Metrics m = Iso30107Metrics.compute(samples, threshold);
            GroupSlices slices = slicesOn ? GroupSlices.compute(samples, threshold, minSubjects) : null;
            context.putIfAbsent("scores", scores.toString());
            if (file.excluded() > 0) context.putIfAbsent("excludedCancelled", String.valueOf(file.excluded()));
            new Iso30107Report(m, slices, title, context).write(out, "iso30107");
            System.out.printf(Locale.ROOT, "samples=%d bonaFide=%d attacks=%d threshold=%.3f maxAPCER=%.4f (%s) BPCER=%.4f ACER=%.4f BPCER@APCER5=%s APNRR=%s BPNRR=%s excluded=%d -> %s%n",
                    samples.size(), m.bonaFideCount(), m.attackCount(), threshold, m.maxApcer(), m.worstSpecies(), m.bpcer(), m.acer(),
                    na(m.bpcerAtApcer5()), na(m.apnrr().value()), na(m.bpnrr().value()), file.excluded(), out.resolve("iso30107.html"));
            if (!Double.isNaN(maxAcer) && m.acer() > maxAcer) {
                System.err.printf(Locale.ROOT, "ACER %.4f exceeds --max-acer %.4f%n", m.acer(), maxAcer);
                return 1;
            }
            return 0;
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            System.err.println("iso-report: " + (e instanceof ArrayIndexOutOfBoundsException ? "missing value for last option" : e.getMessage()));
            usage();
            return 2;
        } catch (IOException e) {
            System.err.println("iso-report: " + e.getMessage());
            return 2;
        }
    }

    private static String na(double v) { return Double.isNaN(v) ? "n/a" : String.format(Locale.ROOT, "%.4f", v); }

    /** Parse a JSON Lines score file (cancelled lines are dropped; see {@link #readScores}). */
    public static List<PadSample> readSamples(Path file) throws IOException {
        return readScores(file).samples();
    }

    /** Parse a JSON Lines score file, reporting how many cancelled lines were excluded. */
    public static ScoreFile readScores(Path file) throws IOException {
        List<PadSample> out = new ArrayList<>();
        int excluded = 0;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line; int n = 0;
            while ((line = r.readLine()) != null) {
                n++;
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                JsonNode j;
                try { j = MAPPER.readTree(t); } catch (IOException e) { throw new IllegalArgumentException(file + ":" + n + ": invalid JSON"); }
                if (!j.isObject()) throw new IllegalArgumentException(file + ":" + n + ": expected a JSON object");
                String decision = text(j, "decision"), failure = text(j, "failure");
                if (NonResponse.isExcluded(decision, failure)) { excluded++; continue; }
                boolean responded = j.has("response") && j.get("response").isBoolean() ? j.get("response").asBoolean()
                        : !NonResponse.isNonResponse(decision, failure);
                String id = text(j, "id");
                String label = j.hasNonNull("label") ? j.get("label").asText() : text(j, "pais");
                boolean bona = j.has("bonaFide") ? j.get("bonaFide").asBoolean()
                        : label != null && (label.equalsIgnoreCase("BONA_FIDE") || label.equalsIgnoreCase("LIVE") || label.equalsIgnoreCase("BONAFIDE"));
                if (!bona && (label == null || label.isBlank())) throw new IllegalArgumentException(file + ":" + n + ": attack line needs 'label' or 'pais'");
                String species = bona ? null : label.toUpperCase(Locale.ROOT);
                PadSample s;
                if (responded) {
                    if (!j.has("score") || !j.get("score").isNumber()) throw new IllegalArgumentException(file + ":" + n + ": missing numeric 'score'");
                    double score = effectiveScore(j.get("score").asDouble(), decision, failure);
                    s = bona ? PadSample.bonaFide(score, id) : PadSample.attack(species, score, id);
                } else {
                    s = PadSample.nonResponse(species, id);
                }
                String subject = text(j, "subject");
                if (subject != null) s = s.withSubject(subject);
                Map<String, String> groups = new TreeMap<>();
                for (String k : GROUP_KEYS) { String v = text(j, k); if (v != null && !v.isBlank()) groups.put(k, v); }
                JsonNode g = j.get("groups");
                if (g != null && !g.isNull()) {
                    if (!g.isObject()) throw new IllegalArgumentException(file + ":" + n + ": 'groups' must be an object");
                    for (Iterator<Map.Entry<String, JsonNode>> it = g.fields(); it.hasNext(); ) {
                        Map.Entry<String, JsonNode> e = it.next();
                        if (!e.getValue().isNull() && !e.getValue().asText().isBlank()) groups.put(e.getKey(), e.getValue().asText());
                    }
                }
                if (!groups.isEmpty()) s = s.withGroups(groups);
                out.add(s);
            }
        }
        return new ScoreFile(out, excluded);
    }

    /**
     * The score used to classify a presentation. A response that the session rejected for a reason the passive
     * threshold does not control (a hard PAD veto, {@code decision = ATTACK}; a failed or timed-out challenge; identity
     * drift) stays rejected at every operating threshold, so it is classified with score 0. Without this, a vetoed
     * attack whose fused score happened to be high (the fused score is computed before the veto is applied) would be
     * counted as accepted, against the ISO/IEC 30107-3 definition used here ({@code Res = 1} when {@code decision != LIVE}).
     * Lines without a {@code decision}, {@code LIVE} decisions and {@code LIVENESS_BELOW_THRESHOLD} rejections keep
     * their fused score.
     */
    static double effectiveScore(double score, String decision, String failure) {
        if (decision == null || decision.isBlank() || decision.equalsIgnoreCase("LIVE")) return score;
        if ("LIVENESS_BELOW_THRESHOLD".equalsIgnoreCase(failure)) return score;
        return 0.0;
    }

    private static String text(JsonNode j, String field) {
        JsonNode v = j.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static void usage() {
        System.err.println("usage: iso-report --scores <file.jsonl> --out <dir> [--threshold 0.5] [--title T] [--context k=v]... [--max-acer X] [--min-subjects 5] [--no-slices]");
    }
}
