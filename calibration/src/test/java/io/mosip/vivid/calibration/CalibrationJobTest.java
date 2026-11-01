package io.mosip.vivid.calibration;

import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.policy.PolicyException;
import io.mosip.vivid.policy.PolicyLoader;
import io.mosip.vivid.policy.signing.PolicyKeys;
import io.mosip.vivid.policy.signing.PolicySigner;
import io.mosip.vivid.policy.signing.SignedPolicy;
import io.mosip.vivid.telemetry.audit.AuditRecord;
import io.mosip.vivid.telemetry.audit.LivenessAuditEvent;
import io.mosip.vivid.telemetry.calibration.ScoreBinning;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class CalibrationJobTest {

    private static final ScoreBinning B = ScoreBinning.DEFAULT;

    /** Bona fide histogram with {@code n} samples spread so that the 5 % quantile sits at a known score. */
    private static double[] histogramWithQuantileAt(double score, double n) {
        double[] h = new double[B.bins()];
        int qBin = B.bin(score);
        h[qBin] = 0.05 * n * 2;          // the 5 % quantile falls in the middle of this bin
        h[B.bins() - 1] = n - h[qBin];
        return h;
    }

    @Test
    void estimatorInvertsTheCdfInLogitSpace() {
        double[] bf = histogramWithQuantileAt(0.70, 10_000);
        AggregatedHistogram h = AggregatedHistogram.of("c", Workflow.RESIDENT, B, bf, new double[B.bins()], 10, 0);
        ThresholdEstimator.Estimate e = ThresholdEstimator.estimate(h, 0.05);
        int bin = B.bin(0.70);
        assertThat(e.threshold()).isCloseTo(B.scoreAt(bin, 0.5), within(1e-9));
        assertThat(e.bonaFideTotal()).isEqualTo(10_000);
        assertThat(e.bpcerStdError()).isCloseTo(Math.sqrt(10_000 * 0.05 * 0.95) / 10_000, within(1e-9));
        assertThat(ThresholdEstimator.bpcerAt(h, e.threshold())).isCloseTo(0.05, within(1e-9));
        // negative noisy counts do not break monotonicity
        bf[0] = -40;
        bf[1] = 40;
        assertThat(ThresholdEstimator.estimate(h, 0.05).threshold()).isCloseTo(B.scoreAt(bin, 0.5), within(1e-9));
        assertThat(ThresholdEstimator.estimate(AggregatedHistogram.of("c", Workflow.RESIDENT, B, new double[B.bins()], new double[B.bins()], 1, 0), 0.05).empty()).isTrue();
    }

    @Test
    void rulesApplyEvidenceMinimumChangeStepBoundsAndTighteningOnly() {
        CalibrationRules rules = CalibrationRules.defaults();
        AggregatedHistogram tighter = AggregatedHistogram.of("c", Workflow.RESIDENT, B, histogramWithQuantileAt(0.95, 20_000), new double[B.bins()], 50, 0);
        CalibrationRules.Decision d = rules.decide(tighter, 0.80, 0.05);
        assertThat(d.action()).isEqualTo(CalibrationRules.Action.TIGHTEN);
        assertThat(d.proposed()).isEqualTo(0.85);                               // step-limited from 0.80
        assertThat(d.notes()).anyMatch(n -> n.contains("step limited"));

        AggregatedHistogram near = AggregatedHistogram.of("c", Workflow.RESIDENT, B, histogramWithQuantileAt(0.803, 20_000), new double[B.bins()], 50, 0);
        double est = ThresholdEstimator.estimate(near, 0.05).threshold();
        CalibrationRules.Decision small = rules.decide(near, est + 0.004, 0.05);
        assertThat(small.action()).isEqualTo(CalibrationRules.Action.UNCHANGED_SMALL_CHANGE);

        double[] rej = new double[B.bins()];
        rej[B.bin(0.5)] = 1000;
        AggregatedHistogram looser = AggregatedHistogram.of("c", Workflow.RESIDENT, B, histogramWithQuantileAt(0.40, 20_000), rej, 50, 0);
        CalibrationRules.Decision blocked = rules.decide(looser, 0.80, 0.05);
        assertThat(blocked.action()).isEqualTo(CalibrationRules.Action.LOOSENING_NOT_AUTHORISED);
        assertThat(blocked.proposed()).isEqualTo(0.80);
        CalibrationRules.Decision loosened = rules.withLoosening(true).decide(looser, 0.80, 0.05);
        assertThat(loosened.action()).isEqualTo(CalibrationRules.Action.LOOSEN);
        assertThat(loosened.proposed()).isEqualTo(0.75);
        CalibrationRules.Decision floor = rules.withLoosening(true).decide(looser, 0.62, 0.05);
        assertThat(floor.proposed()).isEqualTo(0.60);                           // RESIDENT floor

        rej[B.bin(0.5)] = 0;
        rej[B.bin(0.77)] = 1000;                                                // rejected mass right under the threshold
        CalibrationRules.Decision guarded = rules.withLoosening(true).decide(looser, 0.80, 0.05);
        assertThat(guarded.action()).isEqualTo(CalibrationRules.Action.LOOSENING_REFUSED_REJECTED_MASS);

        AggregatedHistogram noisy = AggregatedHistogram.of("c", Workflow.RESIDENT, B, histogramWithQuantileAt(0.95, 2_000), new double[B.bins()], 50, 5000);
        assertThat(rules.decide(noisy, 0.80, 0.05).action()).isEqualTo(CalibrationRules.Action.UNCHANGED_INSUFFICIENT_EVIDENCE);
    }

    @Test
    void readerDropsDuplicatesAndForeignEvents() {
        AuditRecord rec = AuditRecord.builder(LivenessAuditEvent.CALIBRATION_RELEASED).workflow(Workflow.RESIDENT).at(Instant.EPOCH)
                .attribute("releaseId", "r1").attribute("client", "m1").attribute("centre", "c1").attribute("mechanism", "discrete-laplace")
                .attribute("epsilon", "1").attribute("rho", "0").attribute("sensitivity", "1").attribute("binning", B.id())
                .attribute("bf.0", zeros(20)).attribute("bf.20", zeros(20)).attribute("bf.40", "5," + zeros(19))
                .attribute("rej.0", zeros(20)).attribute("rej.20", zeros(20)).attribute("rej.40", zeros(20)).attribute("threshold", "0.8").build();
        ReleaseReader r = new ReleaseReader().accept(rec.toJson()).accept(rec.toJson()).accept("not json")
                .accept(AuditRecord.builder(LivenessAuditEvent.SESSION_PASSED).at(Instant.EPOCH).build().toJson());
        assertThat(r.releases()).hasSize(1);
        assertThat(r.duplicates()).isEqualTo(1);
        assertThat(r.malformed()).isEqualTo(1);
        ClientRelease c = r.releases().get(0);
        assertThat(c.bonaFide()[40]).isEqualTo(5);
        assertThat(c.threshold()).isEqualTo(0.8);
        assertThat(c.noiseVariance()).isCloseTo(2 * Math.exp(-1) / Math.pow(1 - Math.exp(-1), 2), within(1e-12));
    }

    @Test
    void updaterChangesOnlyThresholdsAndVersionAndRefusesAnUnverifiedBase() {
        KeyPair k = PolicyKeys.generateEc();
        SignedPolicy base = PolicySigner.signToEnvelope(PolicyLoader.builtinDefaultsJson(), k.getPrivate(), "ops");
        SignedPolicy upd = PolicyUpdater.update(base, k.getPublic(), Map.of(Workflow.RESIDENT, 0.75), "builtin-1.0+cal.t.c1", k.getPrivate(), "ops");
        PolicyLoader loader = new PolicyLoader();
        var loaded = loader.loadVerified(upd.policyJson(), upd.signatureBase64(), k.getPublic(), "calibration");
        assertThat(loaded.report().usedFallback()).isFalse();
        assertThat(loader.toLivenessPolicy(loaded.document(), Workflow.RESIDENT).passiveThreshold()).isEqualTo(0.75);
        assertThat(loader.toLivenessPolicy(loaded.document(), Workflow.OPERATOR).passiveThreshold())
                .isEqualTo(LivenessPolicy.defaults(Workflow.OPERATOR).passiveThreshold());
        assertThat(loader.toLivenessPolicy(loaded.document(), Workflow.RESIDENT).maxRetries()).isEqualTo(3);
        assertThat(loaded.document().version()).isEqualTo("builtin-1.0+cal.t.c1");

        KeyPair other = PolicyKeys.generateEc();
        assertThatThrownBy(() -> PolicyUpdater.update(base, other.getPublic(), Map.of(Workflow.RESIDENT, 0.75), "v", k.getPrivate(), "ops"))
                .isInstanceOf(PolicyException.class);
        assertThatThrownBy(() -> PolicyUpdater.apply(PolicyLoader.builtinDefaultsJson(), Map.of(Workflow.RESIDENT, 1.5), "v"))
                .isInstanceOf(PolicyException.class);
    }

    private static String zeros(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "0"));
    }

    @Test
    void simulationRecoversThresholdsAndShowsTheNoiseTradeOff() {
        CalibrationSimulation.Config cfg = CalibrationSimulation.Config.defaults();
        cfg = new CalibrationSimulation.Config(cfg.targetBpcer(), cfg.currentThreshold(), 300, cfg.attackRate(), cfg.maxAttempts(),
                cfg.withinSubjectSd(), cfg.kernelBandwidth(), cfg.bonaFideChallengePass(), cfg.attackChallengePass(), cfg.attackFlaggedLater(),
                1.0, 2, 7L, List.of("0.1", "1", "inf"), List.of(5, 50));
        List<CalibrationSimulation.Cell> cells = new CalibrationSimulation(cfg).run();
        CalibrationSimulation.Cell weak = find(cells, "0.1", 5);
        CalibrationSimulation.Cell strong = find(cells, "1", 50);
        CalibrationSimulation.Cell exact = find(cells, "inf", 50);
        assertThat(exact.meanAbsBpcerError()).isLessThan(0.01);
        assertThat(strong.meanAbsBpcerError()).isLessThan(0.015);
        assertThat(weak.meanAbsBpcerError()).isGreaterThan(strong.meanAbsBpcerError());
    }

    private static CalibrationSimulation.Cell find(List<CalibrationSimulation.Cell> cells, String eps, int k) {
        return cells.stream().filter(c -> c.epsilon().equals(eps) && c.clients() == k).findFirst().orElseThrow();
    }
}
