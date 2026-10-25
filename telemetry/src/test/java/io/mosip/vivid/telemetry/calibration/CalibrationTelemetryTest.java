package io.mosip.vivid.telemetry.calibration;

import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.telemetry.audit.AuditRecord;
import io.mosip.vivid.telemetry.audit.InMemoryAuditSink;
import io.mosip.vivid.telemetry.audit.LivenessAuditEvent;
import io.mosip.vivid.telemetry.audit.OfflineAuditQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class CalibrationTelemetryTest {

    private static final Instant T0 = Instant.parse("2026-10-24T10:00:00Z");

    @Test
    void bernoulliExpIsExactInDistribution() {
        Random rnd = new Random(1);
        int n = 200_000;
        int hits = 0;
        int hits2 = 0;
        for (int i = 0; i < n; i++) {
            if (ExactSampling.bernoulliExp(rnd, 3, 10)) hits++;
            if (ExactSampling.bernoulliExp(rnd, BigInteger.valueOf(5), BigInteger.valueOf(2))) hits2++;
        }
        assertThat((double) hits / n).isCloseTo(Math.exp(-0.3), within(0.005));
        assertThat((double) hits2 / n).isCloseTo(Math.exp(-2.5), within(0.003));
    }

    @Test
    void discreteLaplaceHasTheRightShape() {
        Random rnd = new Random(2);
        int n = 200_000;
        double sum = 0;
        double sq = 0;
        int zeros = 0;
        for (int i = 0; i < n; i++) {
            long x = ExactSampling.discreteLaplace(rnd, 2, 1);   // scale 2
            sum += x;
            sq += x * x;
            if (x == 0) zeros++;
        }
        double q = Math.exp(-0.5);
        assertThat(sum / n).isCloseTo(0, within(0.05));
        assertThat(sq / n).isCloseTo(2 * q / ((1 - q) * (1 - q)), within(0.2));
        assertThat((double) zeros / n).isCloseTo((1 - q) / (1 + q), within(0.005));
        assertThat(DpMechanism.discreteLaplace("0.5").noiseVariance(1)).isCloseTo(2 * q / ((1 - q) * (1 - q)), within(1e-9));
    }

    @Test
    void discreteGaussianHasTheRightShape() {
        Random rnd = new Random(3);
        int n = 100_000;
        double sum = 0;
        double sq = 0;
        for (int i = 0; i < n; i++) {
            long x = ExactSampling.discreteGaussian(rnd, BigInteger.valueOf(9), BigInteger.ONE);
            sum += x;
            sq += x * x;
        }
        assertThat(sum / n).isCloseTo(0, within(0.05));
        assertThat(sq / n).isCloseTo(9, within(0.25));   // the discrete Gaussian's variance is just below sigma^2
    }

    /** Empirical privacy check: neighbouring counts produce output distributions within a factor e^epsilon. */
    @Test
    void laplaceNoiseBoundsThePrivacyLossOfOneSubject() {
        DpMechanism m = DpMechanism.discreteLaplace("1");
        Random rnd = new Random(4);
        int n = 400_000;
        Map<Long, Integer> a = new HashMap<>();
        Map<Long, Integer> b = new HashMap<>();
        for (int i = 0; i < n; i++) {
            a.merge(m.privatize(new long[] {10}, 1, rnd)[0], 1, Integer::sum);
            b.merge(m.privatize(new long[] {11}, 1, rnd)[0], 1, Integer::sum);
        }
        double maxRatio = 0;
        for (long k = 8; k <= 13; k++) {
            double pa = a.getOrDefault(k, 0) / (double) n;
            double pb = b.getOrDefault(k, 0) / (double) n;
            maxRatio = Math.max(maxRatio, Math.max(pa / pb, pb / pa));
        }
        assertThat(maxRatio).isLessThan(Math.E * 1.05).isGreaterThan(Math.E * 0.95);
    }

    @Test
    void gaussianConfigurationMeetsTheTargetEpsilon() {
        DpMechanism g = DpMechanism.discreteGaussian(1.0, 1e-6);
        assertThat(g.epsilonAt(1e-6)).isCloseTo(1.0, within(1e-9));
        assertThat(g.noiseVariance(3)).isCloseTo(9 / (2 * g.rho()), within(1e-9));
        long[] out = g.privatize(new long[] {100, 0, 5}, 3, new Random(5));
        assertThat(out).hasSize(3);
    }

    @Test
    void accumulatorBoundsContributions() {
        Map<Workflow, Integer> caps = new HashMap<>();
        caps.put(Workflow.RESIDENT, 1);
        caps.put(Workflow.OPERATOR, 3);
        CalibrationAccumulator acc = new CalibrationAccumulator(new CalibrationAccumulator.Config(ScoreBinning.DEFAULT, caps, 5));
        assertThat(acc.record(Workflow.RESIDENT, "s1", 0.95, CalibrationLabel.BONA_FIDE)).isTrue();
        assertThat(acc.record(Workflow.RESIDENT, "s1", 0.95, CalibrationLabel.BONA_FIDE)).as("one sample per session").isFalse();
        for (int i = 0; i < 3; i++) {
            assertThat(acc.record(Workflow.OPERATOR, "op-7", 0.9, CalibrationLabel.BONA_FIDE)).isTrue();
        }
        assertThat(acc.record(Workflow.OPERATOR, "op-7", 0.9, CalibrationLabel.BONA_FIDE)).as("operator cap").isFalse();
        for (int i = 0; i < 4; i++) {
            acc.record(Workflow.RESIDENT, "s" + (i + 2), 0.2, CalibrationLabel.REJECTED);
        }
        assertThat(acc.record(Workflow.RESIDENT, "s99", 0.5, CalibrationLabel.REJECTED)).as("client cap of 5").isFalse();
        assertThat(acc.samples(Workflow.RESIDENT)).isEqualTo(5);
        assertThat(acc.dropped()).containsExactly(2, 1);
        assertThat(acc.sensitivity(Workflow.OPERATOR)).isEqualTo(3);
        assertThat(sum(acc.counts(Workflow.RESIDENT, CalibrationLabel.REJECTED))).isEqualTo(4);
        assertThat(acc.counts(Workflow.RESIDENT, CalibrationLabel.BONA_FIDE)[ScoreBinning.DEFAULT.bin(0.95)]).isEqualTo(1);

        CalibrationAccumulator back = CalibrationAccumulator.fromJson(acc.toJson(), acc.config());
        assertThat(back.counts(Workflow.RESIDENT, CalibrationLabel.REJECTED)).isEqualTo(acc.counts(Workflow.RESIDENT, CalibrationLabel.REJECTED));
        assertThat(back.record(Workflow.OPERATOR, "op-7", 0.9, CalibrationLabel.BONA_FIDE)).as("cap survives restart").isFalse();
        assertThat(acc.toJson()).doesNotContain("op-7").doesNotContain("s99");
        acc.reset();
        assertThat(acc.samples(Workflow.RESIDENT)).isZero();
        assertThatThrownBy(() -> acc.record(Workflow.RESIDENT, "x", 1.5, CalibrationLabel.BONA_FIDE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void binningIsLogitUniform() {
        ScoreBinning b = ScoreBinning.DEFAULT;
        assertThat(b.bin(0.5)).isEqualTo(20);
        assertThat(b.bin(0.0)).isZero();
        assertThat(b.bin(1.0)).isEqualTo(59);
        assertThat(b.scoreAt(b.bin(0.8), b.position(0.8) - b.bin(0.8))).isCloseTo(0.8, within(1e-9));
        assertThat(ScoreBinning.parse(b.id())).isEqualTo(b);
    }

    @Test
    void ledgerRefusesOnceTheBudgetIsSpentAndSurvivesRestarts(@TempDir Path dir) {
        Path f = dir.resolve("ledger.json");
        PrivacyBudgetLedger l = new PrivacyBudgetLedger(f, 2.0, 1e-6);
        assertThat(l.tryCharge("r1", DpMechanism.LAPLACE, 1.5, 0, T0)).isTrue();
        assertThat(l.tryCharge("r1", DpMechanism.LAPLACE, 0.1, 0, T0)).as("release id reused").isFalse();
        assertThat(l.tryCharge("r2", DpMechanism.LAPLACE, 1.0, 0, T0)).as("would exceed").isFalse();
        assertThat(l.tryCharge("r3", DpMechanism.LAPLACE, 0.5, 0, T0)).isTrue();
        assertThat(l.remainingEpsilon()).isCloseTo(0, within(1e-12));
        PrivacyBudgetLedger reopened = new PrivacyBudgetLedger(f, 2.0, 1e-6);
        assertThat(reopened.spentEpsilon()).isCloseTo(2.0, within(1e-12));
        assertThat(reopened.tryCharge("r4", DpMechanism.LAPLACE, 0.01, 0, T0)).isFalse();
        assertThat(reopened.entries()).extracting(PrivacyBudgetLedger.Entry::releaseId).containsExactly("r1", "r3");

        PrivacyBudgetLedger z = new PrivacyBudgetLedger(null, 3.0, 1e-6);
        DpMechanism g = DpMechanism.discreteGaussian(1.0, 1e-6);
        assertThat(z.tryCharge("g1", g.id(), 0, g.rho(), T0)).isTrue();
        assertThat(z.tryCharge("g2", g.id(), 0, g.rho(), T0)).isTrue();
        // zCDP composes better than adding epsilons: two releases of epsilon 1 cost well under 2
        assertThat(z.spentEpsilon()).isLessThan(2.0).isGreaterThan(1.0);
    }

    @Test
    void releaseGoesThroughTheAuditSyncPathAndIsChargedFirst(@TempDir Path dir) {
        CalibrationAccumulator acc = new CalibrationAccumulator(CalibrationAccumulator.Config.defaults());
        Random scores = new Random(9);
        for (int i = 0; i < 500; i++) {
            acc.record(Workflow.RESIDENT, "session-" + i, 1 / (1 + Math.exp(-(4 + scores.nextGaussian()))), CalibrationLabel.BONA_FIDE);
        }
        long[] raw = acc.counts(Workflow.RESIDENT, CalibrationLabel.BONA_FIDE);
        OfflineAuditQueue queue = new OfflineAuditQueue(dir.resolve("audit.jsonl"));
        PrivacyBudgetLedger ledger = new PrivacyBudgetLedger(dir.resolve("ledger.json"), 3.0, 1e-6);
        CalibrationReleaser r = new CalibrationReleaser(acc, DpMechanism.discreteLaplace("0.5"), ledger, queue, "machine-a1", "centre-10011", new Random(10));
        assertThat(r.epsilonPerRelease()).isCloseTo(1.5, within(1e-12));
        Map<Workflow, Double> thresholds = Map.of(Workflow.RESIDENT, 0.8, Workflow.OPERATOR, 0.85, Workflow.SUPERVISOR, 0.9);
        CalibrationReleaser.Result first = r.release("2026-Q4", T0, thresholds, "builtin-1.0");
        assertThat(first.released()).isTrue();
        assertThat(first.records()).hasSize(3);
        assertThat(ledger.spentEpsilon()).isCloseTo(1.5, within(1e-12));
        assertThat(acc.samples(Workflow.RESIDENT)).as("new period").isZero();

        List<AuditRecord> synced = new ArrayList<>();
        assertThat(queue.drain(synced::add)).isEqualTo(3);
        AuditRecord resident = synced.stream().filter(a -> a.workflow().orElseThrow() == Workflow.RESIDENT).findFirst().orElseThrow();
        assertThat(resident.event()).isEqualTo(LivenessAuditEvent.CALIBRATION_RELEASED);
        assertThat(resident.attributes()).containsEntry("centre", "centre-10011").containsEntry("mechanism", DpMechanism.LAPLACE)
                .containsEntry("sensitivity", "1").containsEntry("threshold", "0.800000").containsEntry("binning", ScoreBinning.DEFAULT.id());
        resident.attributes().values().forEach(v -> assertThat(v).doesNotEndWith("..."));
        long[] noisy = CalibrationReleaser.readCounts(resident.attributes(), "bf", ScoreBinning.DEFAULT.bins());
        assertThat(noisy).isNotEqualTo(raw);
        assertThat((double) sum(noisy)).isCloseTo(500, within(60.0));

        // Second release uses the remaining 1.5; a third is refused and only says so.
        acc.record(Workflow.RESIDENT, "late", 0.9, CalibrationLabel.BONA_FIDE);
        assertThat(r.release("2027-Q1", T0, thresholds, "builtin-1.0").released()).isTrue();
        acc.record(Workflow.RESIDENT, "later", 0.9, CalibrationLabel.BONA_FIDE);
        CalibrationReleaser.Result refused = r.release("2027-Q2", T0, thresholds, "builtin-1.0");
        assertThat(refused.released()).isFalse();
        assertThat(refused.records()).singleElement().extracting(AuditRecord::event).isEqualTo(LivenessAuditEvent.CALIBRATION_BUDGET_EXHAUSTED);
        assertThat(acc.samples(Workflow.RESIDENT)).as("unreleasable data is discarded, not hoarded").isZero();
        assertThat(r.release("2026-Q4", T0, thresholds, "x").released()).as("a release id is never re-noised").isFalse();

        InMemoryAuditSink mem = new InMemoryAuditSink();
        CalibrationReleaser gauss = new CalibrationReleaser(acc, DpMechanism.discreteGaussian(0.5, 1e-6),
                new PrivacyBudgetLedger(null, 2.0, 1e-6), mem, "machine-b", "centre-2", new Random(11));
        assertThat(gauss.release("g", T0, thresholds, "v").released()).isTrue();
        assertThat(mem.find(LivenessAuditEvent.CALIBRATION_RELEASED)).hasSize(3);
    }

    private static long sum(long[] a) {
        long s = 0;
        for (long v : a) s += v;
        return s;
    }
}
