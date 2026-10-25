package io.mosip.vivid.calibration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.policy.PolicyDocument;
import io.mosip.vivid.policy.PolicyLoader;
import io.mosip.vivid.policy.signing.PolicyKeys;
import io.mosip.vivid.policy.signing.PolicyVerifier;
import io.mosip.vivid.policy.signing.SignedPolicy;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Central calibration job (PLAN.md 17.10, design 14-dp-calibration.md).
 *
 * <pre>
 * CalibrationJobMain --releases audit-export.jsonl [--releases more.jsonl ...]
 *                    --policy policy-in-force.envelope.json --public-key policy.pub
 *                    --private-key policy.key [--key-id ops-2026] --tag 2026Q4 --out out/
 *                    [--target-bpcer 0.05] [--group centre|all] [--allow-loosening]
 *                    [--min-change 0.01] [--max-step 0.05] [--min-bona-fide 300] [--max-std-error 0.01]
 * </pre>
 *
 * <p>Reads the {@code CALIBRATION_RELEASED} audit records, aggregates them per centre (or across all centres) and
 * workflow, re-estimates the threshold for the target BPCER, applies {@link CalibrationRules}, and for every group with
 * a change writes {@code <group>.policy.envelope.json}: the policy in force with the new thresholds, signed. Always
 * writes {@code calibration-report.json} and {@code calibration-report.md}. Exit codes: 0 success, 1 usage error,
 * 2 the policy in force does not verify.</p>
 */
public final class CalibrationJobMain {

    private CalibrationJobMain() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    public static int run(String[] args, PrintStream out) {
        Map<String, List<String>> a;
        try {
            a = parse(args);
        } catch (IllegalArgumentException e) {
            out.println("usage error: " + e.getMessage());
            return 1;
        }
        try {
            return execute(a, out);
        } catch (IOException e) {
            out.println("I/O error: " + e.getMessage());
            return 1;
        }
    }

    private static int execute(Map<String, List<String>> a, PrintStream out) throws IOException {
        for (String req : List.of("releases", "policy", "public-key", "private-key", "tag", "out")) {
            if (!a.containsKey(req)) {
                out.println("usage error: --" + req + " is required");
                return 1;
            }
        }
        double target = Double.parseDouble(one(a, "target-bpcer", "0.05"));
        boolean perCentre = !"all".equals(one(a, "group", "centre"));
        CalibrationRules d = CalibrationRules.defaults();
        CalibrationRules rules = new CalibrationRules(d.bounds(), Double.parseDouble(one(a, "min-change", "0.01")),
                Double.parseDouble(one(a, "max-step", "0.05")), Double.parseDouble(one(a, "min-bona-fide", "300")),
                Double.parseDouble(one(a, "max-std-error", "0.01")), a.containsKey("allow-loosening"), d.maxRejectedShare());
        PublicKey pub = PolicyKeys.decodePublicAny(Files.readString(Path.of(one(a, "public-key", null))).trim());
        PrivateKey priv = PolicyKeys.decodePrivateAny(Files.readString(Path.of(one(a, "private-key", null))).trim());
        String keyId = one(a, "key-id", "calibration");
        SignedPolicy inForce = SignedPolicy.fromEnvelopeJson(Files.readString(Path.of(one(a, "policy", null))));
        if (!PolicyVerifier.verify(inForce, pub)) {
            out.println("the policy in force does not verify with the configured key; nothing emitted");
            return 2;
        }
        PolicyLoader loader = new PolicyLoader();
        PolicyDocument doc = loader.parse(inForce.policyJson());

        ReleaseReader reader = new ReleaseReader();
        for (String f : a.get("releases")) {
            reader.read(Path.of(f));
        }
        List<ClientRelease> releases = reader.releases();
        List<AggregatedHistogram> groups = AggregatedHistogram.aggregate(releases, perCentre ? ClientRelease::centre : r -> "all");
        out.printf(Locale.ROOT, "read %d releases (%d duplicates dropped, %d malformed, %d budget-exhausted notices) into %d groups%n",
                releases.size(), reader.duplicates(), reader.malformed(), reader.budgetExhausted(), groups.size());

        Path outDir = Path.of(one(a, "out", null));
        Files.createDirectories(outDir);
        Map<String, Map<Workflow, Double>> changes = new TreeMap<>();
        List<CalibrationRules.Decision> decisions = new ArrayList<>();
        for (AggregatedHistogram h : groups) {
            double current = loader.toLivenessPolicy(doc, h.workflow()).passiveThreshold();
            CalibrationRules.Decision dec = rules.decide(h, current, target);
            decisions.add(dec);
            out.printf(Locale.ROOT, "  %-14s %-10s clients=%-4d bonaFide=%-8.0f current=%.3f estimate=%.4f -> %-34s proposed=%.3f%n",
                    h.group(), h.workflow(), h.clients(), ThresholdEstimator.estimate(h, target).bonaFideTotal(), current,
                    dec.estimate(), dec.action(), dec.proposed());
            if (dec.changes()) {
                changes.computeIfAbsent(h.group(), k -> new EnumMap<>(Workflow.class)).put(h.workflow(), dec.proposed());
            }
        }
        Map<String, String> emitted = new LinkedHashMap<>();
        for (Map.Entry<String, Map<Workflow, Double>> e : changes.entrySet()) {
            String version = (doc.version() == null ? "unversioned" : doc.version()) + "+cal." + one(a, "tag", null) + "." + sanitize(e.getKey());
            SignedPolicy update = PolicyUpdater.update(inForce, pub, e.getValue(), version, priv, keyId);
            Path file = outDir.resolve(sanitize(e.getKey()) + ".policy.envelope.json");
            Files.writeString(file, update.toEnvelopeJson(), StandardCharsets.UTF_8);
            emitted.put(e.getKey(), file.getFileName().toString());
            out.println("  signed " + file.getFileName() + " (version " + version + ")");
        }
        writeReports(outDir, a, target, rules, reader, decisions, emitted, inForce, doc);
        return 0;
    }

    private static void writeReports(Path outDir, Map<String, List<String>> a, double target, CalibrationRules rules, ReleaseReader reader,
                                     List<CalibrationRules.Decision> decisions, Map<String, String> emitted, SignedPolicy inForce,
                                     PolicyDocument doc) throws IOException {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("tag", one(a, "tag", null));
        report.put("targetBpcer", target);
        report.put("policyInForce", doc.version());
        report.put("rules", rules);
        report.put("releasesRead", reader.releases().size());
        report.put("duplicatesDropped", reader.duplicates());
        report.put("malformed", reader.malformed());
        report.put("budgetExhaustedNotices", reader.budgetExhausted());
        report.put("decisions", decisions);
        report.put("emitted", emitted);
        ObjectMapper m = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Files.writeString(outDir.resolve("calibration-report.json"), m.writeValueAsString(report), StandardCharsets.UTF_8);
        StringBuilder md = new StringBuilder();
        md.append("# Calibration report ").append(one(a, "tag", null)).append("\n\n");
        md.append(String.format(Locale.ROOT, "Target BPCER %.3f; policy in force `%s`; %d client releases, %d duplicates dropped.%n%n",
                target, doc.version(), reader.releases().size(), reader.duplicates()));
        md.append("| Group | Workflow | Current | Estimate | Proposed | Action | Est. BPCER now | Est. BPCER proposed | Std error | Notes |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (CalibrationRules.Decision d : decisions) {
            md.append(String.format(Locale.ROOT, "| %s | %s | %.3f | %.4f | %.3f | %s | %.4f | %.4f | %.4f | %s |%n", d.group(), d.workflow(),
                    d.current(), d.estimate(), d.proposed(), d.action(), d.bpcerAtCurrent(), d.bpcerAtProposed(), d.bpcerStdError(),
                    String.join("; ", d.notes())));
        }
        Files.writeString(outDir.resolve("calibration-report.md"), md.toString(), StandardCharsets.UTF_8);
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String one(Map<String, List<String>> a, String k, String def) {
        List<String> v = a.get(k);
        return v == null || v.isEmpty() ? def : v.get(v.size() - 1);
    }

    private static Map<String, List<String>> parse(String[] args) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument " + args[i]);
            }
            String k = args[i].substring(2);
            if (k.equals("allow-loosening")) {
                m.computeIfAbsent(k, x -> new ArrayList<>()).add("true");
                continue;
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("--" + k + " needs a value");
            }
            m.computeIfAbsent(k, x -> new ArrayList<>()).add(args[++i]);
        }
        return m;
    }
}
