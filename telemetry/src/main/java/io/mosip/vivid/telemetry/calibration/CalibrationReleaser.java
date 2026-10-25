package io.mosip.vivid.telemetry.calibration;

import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.telemetry.audit.AuditRecord;
import io.mosip.vivid.telemetry.audit.AuditSink;
import io.mosip.vivid.telemetry.audit.LivenessAuditEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * Releases a client's calibration histograms through the existing audit sync path (design 14-dp-calibration.md).
 *
 * <p>One release covers every workflow, always, whether or not it has data, so the release itself does not reveal
 * which workflows were used. Order of operations: (1) charge the ledger for all workflows (sequential composition,
 * conservative), (2) add noise, (3) hand one {@link LivenessAuditEvent#CALIBRATION_RELEASED} record per workflow to the
 * audit sink (in a Registration Client: the {@code OfflineAuditQueue} that the audit sync drains), (4) start a new
 * period. If the ledger refuses, nothing but a {@link LivenessAuditEvent#CALIBRATION_BUDGET_EXHAUSTED} record is
 * emitted and the period's counts are discarded rather than kept for later.</p>
 *
 * <p>Wire format of a record: attributes {@code releaseId, client, centre, mechanism, epsilon, rho, delta,
 * sensitivity, binning, samplesCap, threshold, policyVersion} and the noisy counts in chunks of {@value #CHUNK}
 * bins, {@code bf.0, bf.20, ...} (bona fide) and {@code rej.0, rej.20, ...} (rejected), each a comma-separated list
 * of integers that may be negative.</p>
 */
public final class CalibrationReleaser {

    /** Bins per attribute chunk; keeps every attribute well under the audit value limit. */
    public static final int CHUNK = 20;

    /** Outcome of {@link #release}. */
    public static final class Result {
        private final boolean released;
        private final List<AuditRecord> records;
        private final String reason;
        private final double epsilonCharged;

        Result(boolean released, List<AuditRecord> records, String reason, double epsilonCharged) {
            this.released = released;
            this.records = Collections.unmodifiableList(records);
            this.reason = reason;
            this.epsilonCharged = epsilonCharged;
        }

        public boolean released() { return released; }
        public List<AuditRecord> records() { return records; }
        public String reason() { return reason; }
        public double epsilonCharged() { return epsilonCharged; }
    }

    private final CalibrationAccumulator accumulator;
    private final DpMechanism mechanism;
    private final PrivacyBudgetLedger ledger;
    private final AuditSink sink;
    private final String clientPseudonym;
    private final String centreId;
    private final Random random;
    private final Set<Workflow> workflows;

    /**
     * @param clientPseudonym stable pseudonymous id of the registration machine (not the machine serial)
     * @param centreId        registration centre id, the unit the central job calibrates for
     * @param random          noise source; production must pass a {@link java.security.SecureRandom}
     */
    public CalibrationReleaser(CalibrationAccumulator accumulator, DpMechanism mechanism, PrivacyBudgetLedger ledger, AuditSink sink,
                               String clientPseudonym, String centreId, Random random) {
        this.accumulator = Objects.requireNonNull(accumulator, "accumulator");
        this.mechanism = Objects.requireNonNull(mechanism, "mechanism");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.clientPseudonym = Objects.requireNonNull(clientPseudonym, "clientPseudonym");
        this.centreId = Objects.requireNonNull(centreId, "centreId");
        this.random = Objects.requireNonNull(random, "random");
        this.workflows = EnumSet.allOf(Workflow.class);
    }

    /** Privacy cost of one release in (epsilon, delta) terms. */
    public double epsilonPerRelease() {
        return workflows.size() * mechanism.epsilonAt(ledger.delta());
    }

    /**
     * Releases the current period.
     *
     * @param releaseId     unique id of this release (for example the period, "2026-Q4")
     * @param thresholds    passive threshold in force per workflow, reported so the job knows where labels were cut
     * @param policyVersion policy version in force
     */
    public Result release(String releaseId, Instant at, Map<Workflow, Double> thresholds, String policyVersion) {
        Objects.requireNonNull(releaseId, "releaseId");
        Objects.requireNonNull(at, "at");
        int n = workflows.size();
        boolean charged = ledger.tryCharge(clientPseudonym + "/" + releaseId, mechanism.id(), n * mechanism.pureEpsilon(), n * mechanism.rho(), at);
        if (!charged) {
            AuditRecord r = AuditRecord.builder(LivenessAuditEvent.CALIBRATION_BUDGET_EXHAUSTED).sessionId(releaseId).at(at)
                    .attribute("releaseId", releaseId).attribute("client", clientPseudonym).attribute("centre", centreId)
                    .attribute("spentEpsilon", fmt(ledger.spentEpsilon())).attribute("budgetEpsilon", fmt(ledger.budgetEpsilon()))
                    .build();
            sink.accept(r);
            accumulator.reset();
            return new Result(false, List.of(r), "privacy budget exhausted or release id already used", 0);
        }
        List<AuditRecord> out = new ArrayList<>();
        for (Workflow w : workflows) {
            int sensitivity = accumulator.sensitivity(w);
            long[] bf = mechanism.privatize(accumulator.counts(w, CalibrationLabel.BONA_FIDE), sensitivity, random);
            long[] rej = mechanism.privatize(accumulator.counts(w, CalibrationLabel.REJECTED), sensitivity, random);
            AuditRecord.Builder b = AuditRecord.builder(LivenessAuditEvent.CALIBRATION_RELEASED).sessionId(releaseId).workflow(w).at(at)
                    .attribute("releaseId", releaseId).attribute("client", clientPseudonym).attribute("centre", centreId)
                    .attribute("mechanism", mechanism.id()).attribute("epsilon", fmt(mechanism.pureEpsilon()))
                    .attribute("rho", fmt(mechanism.rho())).attribute("delta", fmt(ledger.delta()))
                    .attribute("sensitivity", sensitivity).attribute("binning", accumulator.config().binning().id())
                    .attribute("samplesCap", accumulator.config().maxSamplesPerRelease())
                    .attribute("threshold", thresholds == null || thresholds.get(w) == null ? "" : fmt(thresholds.get(w)))
                    .attribute("policyVersion", policyVersion == null ? "" : policyVersion);
            putChunks(b, "bf", bf);
            putChunks(b, "rej", rej);
            AuditRecord r = b.build();
            sink.accept(r);
            out.add(r);
        }
        accumulator.reset();
        return new Result(true, out, null, epsilonPerRelease());
    }

    private static void putChunks(AuditRecord.Builder b, String prefix, long[] counts) {
        for (int start = 0; start < counts.length; start += CHUNK) {
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < Math.min(counts.length, start + CHUNK); i++) {
                if (i > start) {
                    sb.append(',');
                }
                sb.append(counts[i]);
            }
            b.attribute(prefix + "." + start, sb.toString());
        }
    }

    /** Reassembles the counts written by {@link #release} from a record's attributes. */
    public static long[] readCounts(Map<String, String> attributes, String prefix, int bins) {
        long[] out = new long[bins];
        for (int start = 0; start < bins; start += CHUNK) {
            String v = attributes.get(prefix + "." + start);
            if (v == null) {
                throw new IllegalArgumentException("missing " + prefix + "." + start);
            }
            String[] parts = v.split(",");
            for (int i = 0; i < parts.length; i++) {
                out[start + i] = Long.parseLong(parts[i].trim());
            }
        }
        return out;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }
}
