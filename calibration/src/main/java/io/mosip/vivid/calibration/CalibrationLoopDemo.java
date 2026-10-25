package io.mosip.vivid.calibration;

import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.policy.LoadedPolicy;
import io.mosip.vivid.policy.PolicyLoader;
import io.mosip.vivid.policy.PropertiesPolicyAdapter;
import io.mosip.vivid.policy.signing.PolicyKeys;
import io.mosip.vivid.policy.signing.PolicySigner;
import io.mosip.vivid.policy.signing.SignedPolicy;
import io.mosip.vivid.telemetry.audit.OfflineAuditQueue;
import io.mosip.vivid.telemetry.calibration.CalibrationAccumulator;
import io.mosip.vivid.telemetry.calibration.CalibrationLabel;
import io.mosip.vivid.telemetry.calibration.CalibrationReleaser;
import io.mosip.vivid.telemetry.calibration.DpMechanism;
import io.mosip.vivid.telemetry.calibration.PrivacyBudgetLedger;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

/**
 * The end-to-end calibration loop, in process (there is no MOSIP sandbox in this environment):
 * clients accumulate and release DP histograms into their offline audit queues, the queues are drained into a sync
 * export (with one duplicate delivery, as an at-least-once sync would), the central job aggregates and emits a signed
 * policy, and a client verifies and applies it through the same {@link PolicyLoader} and MOSIP-properties path it uses
 * for every policy. A tampered copy falls back to the compiled defaults, and the privacy ledger refuses releases once
 * the budget is spent.
 *
 * <pre>CalibrationLoopDemo [workDir]</pre>
 */
public final class CalibrationLoopDemo {

    /** Observable outcome of one run. */
    public record Outcome(int clients, int releaseRecords, int duplicatesDropped, double thresholdBefore, double thresholdTighteningOnly,
                          double thresholdApplied, String appliedVersion, boolean signatureOk, double tamperedThreshold,
                          boolean tamperedFellBack, int releasesBeforeExhaustion, double epsilonSpentPerClient) {}

    private CalibrationLoopDemo() {}

    public static void main(String[] args) throws IOException {
        Path dir = args.length > 0 ? Path.of(args[0]) : Files.createTempDirectory("vivid-calibration-loop");
        run(dir, System.out);
    }

    public static Outcome run(Path dir, PrintStream out) throws IOException {
        Files.createDirectories(dir);
        // 1. Policy key and the signed policy in force (the compiled defaults, RESIDENT threshold 0.80).
        KeyPair ops = PolicyKeys.generateEc();
        Path pub = dir.resolve("policy.pub");
        Path priv = dir.resolve("policy.key");
        Files.writeString(pub, PolicyKeys.encodePublic(ops.getPublic()));
        Files.writeString(priv, PolicyKeys.encodePrivate(ops.getPrivate()));
        SignedPolicy inForce = PolicySigner.signToEnvelope(PolicyLoader.builtinDefaultsJson(), ops.getPrivate(), "ops-2026");
        Path inForceFile = dir.resolve("policy-in-force.envelope.json");
        Files.writeString(inForceFile, inForce.toEnvelopeJson());
        out.println("[1] policy in force " + new PolicyLoader().parse(inForce.policyJson()).version() + ", signed with ops-2026");

        // 2. Two centres of 30 registration machines each; one release period of 400 resident sessions per machine.
        CalibrationSimulation sim = new CalibrationSimulation(CalibrationSimulation.Config.defaults());
        CalibrationSimulation.Centre dim = CalibrationSimulation.CENTRES.get(1);
        CalibrationSimulation.Centre ref = CalibrationSimulation.CENTRES.get(0);
        Map<Workflow, Double> thresholds = new EnumMap<>(Workflow.class);
        for (Workflow w : Workflow.values()) {
            thresholds.put(w, new PolicyLoader().toLivenessPolicy(new PolicyLoader().parse(inForce.policyJson()), w).passiveThreshold());
        }
        Random sessions = new Random(42);
        List<CalibrationReleaser> releasers = new ArrayList<>();
        List<CalibrationAccumulator> accumulators = new ArrayList<>();
        List<OfflineAuditQueue> queues = new ArrayList<>();
        int clients = 0;
        for (CalibrationSimulation.Centre centre : List.of(dim, ref)) {
            String centreId = centre == dim ? "10011-dim" : "10012-reference";
            for (int m = 0; m < 30; m++) {
                Path machine = dir.resolve("clients").resolve(centreId + "-m" + m);
                CalibrationAccumulator acc = new CalibrationAccumulator(CalibrationAccumulator.Config.defaults());
                int[] seq = {0};
                String prefix = centreId + "-m" + m;
                sim.sessions(sessions, centre, 400, (score, bonaFide) -> acc.record(Workflow.RESIDENT, prefix + "-s" + seq[0]++, score,
                        bonaFide ? CalibrationLabel.BONA_FIDE : CalibrationLabel.REJECTED));
                OfflineAuditQueue queue = new OfflineAuditQueue(machine.resolve("audit-queue.jsonl"));
                PrivacyBudgetLedger ledger = new PrivacyBudgetLedger(machine.resolve("privacy-ledger.json"), 6.0, 1e-6);
                releasers.add(new CalibrationReleaser(acc, DpMechanism.discreteLaplace("1"), ledger, queue, "machine-" + Integer.toHexString(prefix.hashCode()),
                        centreId, new SecureRandom()));
                accumulators.add(acc);
                queues.add(queue);
                clients++;
            }
        }
        int records = 0;
        for (CalibrationReleaser r : releasers) {
            CalibrationReleaser.Result res = r.release("2026-Q4", Instant.parse("2026-12-31T18:00:00Z"), thresholds, "builtin-1.0");
            records += res.records().size();
        }
        out.printf("[2] %d clients released %d CALIBRATION_RELEASED records (epsilon 1 per workflow, 3 per release) into their offline audit queues%n",
                clients, records);

        // 3. Audit sync: drain every queue into one export; one machine's batch is delivered twice.
        Path export = dir.resolve("audit-sync-export.jsonl");
        Files.deleteIfExists(export);
        List<String> lines = new ArrayList<>();
        for (OfflineAuditQueue q : queues) {
            q.drain(rec -> lines.add(rec.toJson()));
        }
        lines.addAll(lines.subList(0, 3));
        Files.write(export, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE);
        out.printf("[3] audit sync export: %d lines (3 re-delivered)%n", lines.size());

        // 4. Central job, first with the default tightening-only rules, then with loosening authorised after review.
        Path jobOut = dir.resolve("job");
        String[] base = {"--releases", export.toString(), "--policy", inForceFile.toString(), "--public-key", pub.toString(),
                "--private-key", priv.toString(), "--key-id", "ops-2026", "--tag", "2026Q4"};
        int rc = CalibrationJobMain.run(concat(base, "--out", jobOut.resolve("tightening-only").toString()), out);
        boolean blocked = !Files.exists(jobOut.resolve("tightening-only").resolve("10011-dim.policy.envelope.json"));
        rc |= CalibrationJobMain.run(concat(base, "--out", jobOut.resolve("reviewed").toString(), "--allow-loosening"), out);
        if (rc != 0) {
            throw new IllegalStateException("calibration job failed with " + rc);
        }
        Path update = jobOut.resolve("reviewed").resolve("10011-dim.policy.envelope.json");
        out.println("[4] tightening-only run emitted nothing for 10011-dim: " + blocked + "; reviewed run emitted " + update.getFileName());

        // 5. Client side: the update arrives as MOSIP config properties and goes through the unchanged verify-and-load path.
        SignedPolicy delivered = SignedPolicy.fromEnvelopeJson(Files.readString(update));
        Properties props = new Properties();
        PropertiesPolicyAdapter adapter = new PropertiesPolicyAdapter();
        adapter.putJson(props, delivered.policyJson(), delivered.signatureBase64());
        String json = props.getProperty(PropertiesPolicyAdapter.JSON_KEY);
        String sig = adapter.signature(props).orElseThrow();
        LoadedPolicy loaded = new PolicyLoader().loadVerified(json, sig, PolicyKeys.decodePublicAny(Files.readString(pub)), "mosip-config");
        LivenessPolicy resident = loaded.effective(Workflow.RESIDENT);
        out.printf("[5] client at 10011-dim: signature %s, fallback %s, version %s, RESIDENT passiveThreshold %.3f (was %.3f)%n",
                loaded.report().signatureStatus(), loaded.report().usedFallback(), resident.policyVersion(), resident.passiveThreshold(),
                thresholds.get(Workflow.RESIDENT));

        // 6. Tampering: lower the threshold in transit without re-signing.
        String tampered = json.replaceFirst("\"passiveThreshold\"\\s*:\\s*" + java.util.regex.Pattern.quote(String.valueOf(resident.passiveThreshold())),
                "\"passiveThreshold\":0.30");
        if (tampered.equals(json)) {
            throw new IllegalStateException("tampering did not change the document");
        }
        LoadedPolicy bad = new PolicyLoader().loadVerified(tampered, sig, PolicyKeys.decodePublicAny(Files.readString(pub)), "mosip-config");
        double tamperedThreshold = bad.effective(Workflow.RESIDENT).passiveThreshold();
        out.printf("[6] tampered copy: signature %s, fallback %s, RESIDENT passiveThreshold %.3f%n", bad.report().signatureStatus(),
                bad.report().usedFallback(), tamperedThreshold);

        // 7. Budget: each release costs 3 (epsilon 1 x 3 workflows) against a lifetime budget of 6.
        CalibrationReleaser first = releasers.get(0);
        int extra = 0;
        for (String period : List.of("2027-Q1", "2027-Q2", "2027-Q3")) {
            accumulators.get(0).record(Workflow.RESIDENT, "late-" + period, 0.9, CalibrationLabel.BONA_FIDE);
            if (first.release(period, Instant.parse("2027-06-30T18:00:00Z"), thresholds, "builtin-1.0").released()) {
                extra++;
            }
        }
        double spent = new PrivacyBudgetLedger(dir.resolve("clients").resolve("10011-dim-m0").resolve("privacy-ledger.json"), 6.0, 1e-6).spentEpsilon();
        out.printf("[7] machine 10011-dim-m0: %d further release(s) accepted, then refused; epsilon spent %.1f of 6.0%n", extra, spent);

        ReleaseReader rr = new ReleaseReader().read(export);
        return new Outcome(clients, records, rr.duplicates(), thresholds.get(Workflow.RESIDENT), blocked ? thresholds.get(Workflow.RESIDENT) : Double.NaN,
                resident.passiveThreshold(), resident.policyVersion(), loaded.report().signatureOk() && !loaded.report().usedFallback(),
                tamperedThreshold, bad.report().usedFallback(), 1 + extra, spent);
    }

    private static String[] concat(String[] a, String... b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
