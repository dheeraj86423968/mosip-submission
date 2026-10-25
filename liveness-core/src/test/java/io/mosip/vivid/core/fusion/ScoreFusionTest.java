package io.mosip.vivid.core.fusion;

import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.api.Workflow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ScoreFusionTest {

    private static final double TOL = 1e-6;

    private static double logit(double p) {
        return Math.log(p / (1 - p));
    }

    private static double sigmoid(double x) {
        return 1 / (1 + Math.exp(-x));
    }

    @Test
    void combineIsWeightedMeanOfLogOdds() {
        // cnn: p=0.1 c=1.0 w=1  -> weight 1.0 ; moire: p=0.3 c=0.5 w=2 -> weight 1.0
        List<SignalScore> s = List.of(SignalScore.of(Signals.CNN_TEXTURE, 0.1, 1.0), SignalScore.of(Signals.MOIRE, 0.3, 0.5));
        Map<String, Double> w = Map.of(Signals.MOIRE, 2.0);
        double expected = 1 - sigmoid((logit(0.1) + logit(0.3)) / 2);
        assertThat(ScoreFusion.combine(s, id -> w.getOrDefault(id, 1.0))).isCloseTo(expected, within(TOL));
        assertThat(expected).isCloseTo(0.820871, within(1e-5)); // hand computed: mean logit -1.522261
    }

    @Test
    void combineIgnoresDisabledAndQualitySignalsAndDefaultsToHalf() {
        List<SignalScore> s = List.of(SignalScore.of(Signals.QUALITY, 0.9, 1.0), SignalScore.of(Signals.RPPG, 0.9, 1.0),
                SignalScore.of(Signals.CNN_TEXTURE, 0.2, 0.0));
        assertThat(ScoreFusion.combine(s, id -> id.equals(Signals.RPPG) ? 0.0 : 1.0)).isEqualTo(0.5);
        assertThat(ScoreFusion.combine(List.of(), id -> 1.0)).isEqualTo(0.5);
        assertThat(ScoreFusion.combine(List.of(SignalScore.of(Signals.RPPG, 0.9, 1.0)), id -> 1.0)).isCloseTo(0.1, within(TOL));
    }

    @Test
    void certainSignalsAreClampedNotInfinite() {
        double live = ScoreFusion.combine(List.of(SignalScore.of(Signals.CNN_TEXTURE, 0.0, 1.0)), id -> 1.0);
        double attack = ScoreFusion.combine(List.of(SignalScore.of(Signals.CNN_TEXTURE, 1.0, 1.0)), id -> 1.0);
        assertThat(live).isCloseTo(1 - ScoreFusion.EPS, within(1e-6));
        assertThat(attack).isCloseTo(ScoreFusion.EPS, within(1e-6));
        assertThat(Double.isFinite(ScoreFusion.logit(0))).isTrue();
    }

    @Test
    void exponentialMovingAverageSmoothsFrames() {
        ScoreFusion f = new ScoreFusion(id -> 1.0, 0.9, 0.5);
        FusionResult r1 = f.update(List.of(SignalScore.of(Signals.CNN_TEXTURE, 0.1, 1.0)));
        double a = 1 - sigmoid(logit(0.1));
        assertThat(r1.livenessScore()).isCloseTo(a, within(TOL));
        assertThat(r1.instantaneousScore()).isCloseTo(a, within(TOL));
        assertThat(r1.framesFused()).isEqualTo(1);
        FusionResult r2 = f.update(List.of(SignalScore.of(Signals.CNN_TEXTURE, 0.8, 1.0)));
        double b = 1 - sigmoid(logit(0.8));
        assertThat(r2.livenessScore()).isCloseTo(0.5 * b + 0.5 * a, within(TOL));
        assertThat(r2.framesFused()).isEqualTo(2);
        assertThat(r2.attackProbability()).isCloseTo(1 - r2.livenessScore(), within(TOL));
        assertThat(r2.vetoed()).isFalse();
        assertThat(r2.vetoSignalOptional()).isEmpty();
        assertThat(r2.attackClassOptional()).isEmpty();
        // Frames without evidence do not move the average or count.
        FusionResult r3 = f.update(List.of());
        assertThat(r3.livenessScore()).isCloseTo(r2.livenessScore(), within(TOL));
        assertThat(r3.framesFused()).isEqualTo(2);
        assertThat(f.livenessScore()).isCloseTo(r2.livenessScore(), within(TOL));
        f.reset();
        assertThat(f.livenessScore()).isEqualTo(0.5);
        assertThat(f.framesFused()).isZero();
        assertThat(f.lastAttackProbabilities()).isEmpty();
    }

    @Test
    void oneBadFrameDoesNotFailAndOneGoodFrameDoesNotPass() {
        ScoreFusion f = new ScoreFusion(id -> 1.0, 0.99, ScoreFusion.DEFAULT_ALPHA);
        for (int i = 0; i < 10; i++) {
            f.update(List.of(SignalScore.of(Signals.CNN_TEXTURE, 0.05, 1.0)));
        }
        double bad = f.update(List.of(SignalScore.of(Signals.CNN_TEXTURE, 0.85, 1.0))).livenessScore();
        assertThat(bad).isGreaterThan(0.7);
        ScoreFusion g = new ScoreFusion(id -> 1.0, 0.99, ScoreFusion.DEFAULT_ALPHA);
        for (int i = 0; i < 10; i++) {
            g.update(List.of(SignalScore.of(Signals.CNN_TEXTURE, 0.85, 1.0)));
        }
        double good = g.update(List.of(SignalScore.of(Signals.CNN_TEXTURE, 0.05, 1.0))).livenessScore();
        assertThat(good).isLessThan(0.4);
    }

    @Test
    void hardVetoRules() {
        ScoreFusion f = new ScoreFusion(id -> id.equals(Signals.COLOR_STATS) ? 0.0 : 1.0, 0.9, 0.5);
        FusionResult flagged = f.update(List.of(SignalScore.of(Signals.CNN_TEXTURE, 0.1, 1.0), SignalScore.veto(Signals.MOIRE, Signals.ATTACK_SCREEN)));
        assertThat(flagged.vetoed()).isTrue();
        assertThat(flagged.vetoSignal()).isEqualTo(Signals.MOIRE);
        assertThat(flagged.attackClass()).isEqualTo(Signals.ATTACK_SCREEN);
        FusionResult threshold = f.update(List.of(SignalScore.of(Signals.CNN_TEXTURE, 0.95, 0.5)));
        assertThat(threshold.vetoed()).isTrue();
        assertThat(threshold.attackClass()).isEqualTo(Signals.ATTACK_UNKNOWN);
        FusionResult lowConfidence = f.update(List.of(SignalScore.of(Signals.CNN_TEXTURE, 0.95, 0.49)));
        assertThat(lowConfidence.vetoed()).isFalse();
        FusionResult belowThreshold = f.update(List.of(SignalScore.of(Signals.CNN_TEXTURE, 0.89, 1.0)));
        assertThat(belowThreshold.vetoed()).isFalse();
        FusionResult disabled = f.update(List.of(SignalScore.veto(Signals.COLOR_STATS, Signals.ATTACK_PRINT)));
        assertThat(disabled.vetoed()).isFalse();
        FusionResult quality = f.update(List.of(SignalScore.veto(Signals.QUALITY, null)));
        assertThat(quality.vetoed()).isFalse();
        assertThat(ScoreFusion.findVeto(List.of(), id -> 1.0, 0.9)).isNull();
        assertThat(f.padHardThreshold()).isEqualTo(0.9);
    }

    @Test
    void sessionSignalsParticipateInEveryUpdate() {
        ScoreFusion f = new ScoreFusion(LivenessPolicy.defaults(Workflow.RESIDENT), 1.0);
        f.setSessionSignal(SignalScore.of(Signals.CHALLENGE_TIMING, 0.8, 0.8));
        FusionResult r = f.update(List.of(SignalScore.of(Signals.CNN_TEXTURE, 0.1, 1.0)));
        double expected = 1 - sigmoid((logit(0.1) * 1.0 + logit(0.8) * 0.8) / 1.8);
        assertThat(r.livenessScore()).isCloseTo(expected, within(TOL));
        assertThat(f.lastAttackProbabilities()).containsEntry(Signals.CHALLENGE_TIMING, 0.8).containsEntry(Signals.CNN_TEXTURE, 0.1);
        f.setSessionSignal(SignalScore.veto(Signals.CHALLENGE_TIMING, Signals.ATTACK_VIDEO));
        assertThat(f.update(List.of()).vetoed()).isTrue();
        f.clearSessionSignal(Signals.CHALLENGE_TIMING);
        f.recordAuxiliary(SignalScore.of(Signals.IDENTITY, 0.0, 0.9));
        assertThat(f.lastAttackProbabilities()).containsEntry(Signals.IDENTITY, 0.0);
        assertThat(f.update(List.of()).vetoed()).isFalse();
        f.reset();
        assertThat(f.lastAttackProbabilities()).isEmpty();
    }

    @Test
    void policyConstructorsAndValidation() {
        assertThat(new ScoreFusion(LivenessPolicy.defaults(Workflow.OPERATOR)).padHardThreshold()).isEqualTo(0.9);
        assertThatThrownBy(() -> new ScoreFusion(id -> 1.0, 0.9, 0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScoreFusion(id -> 1.0, 0.9, 1.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScoreFusion(null, 0.9, 0.5)).isInstanceOf(NullPointerException.class);
    }
}
