package io.mosip.vivid.core.session;

import io.mosip.vivid.api.Challenge;
import io.mosip.vivid.api.ChallengeOutcome;
import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.FaceQuality;
import io.mosip.vivid.api.FailureReason;
import io.mosip.vivid.api.LivenessResult;
import io.mosip.vivid.api.SessionState.Hint;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.core.testing.Scenarios;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QualityGateAndEvidenceTest {

    private final QualityGate gate = new QualityGate(0.4);

    private QualityGate.Verdict eval(FaceQuality q, double x, double y, double w) {
        return gate.evaluate(Scenarios.analysis(q, Scenarios.neutralFace().box(x, y, w, w).build(), List.of(), null));
    }

    @Test
    void faceCountAndGeometryHints() {
        assertThat(gate.evaluate(Scenarios.noFace())).isEqualTo(new QualityGate.Verdict(false, FailureReason.FACE_NOT_DETECTED, Hint.LOOK_AT_CAMERA));
        assertThat(gate.evaluate(Scenarios.multipleFaces()).hint()).isEqualTo(Hint.ONLY_ONE_FACE);
        assertThat(gate.evaluate(Scenarios.multipleFaces()).reasonOptional()).contains(FailureReason.MULTIPLE_FACES);
        assertThat(gate.evaluate(Scenarios.analysis(Scenarios.goodQuality(), null, List.of(), null)).reason()).isEqualTo(FailureReason.POOR_FACE_QUALITY);
        FaceQuality good = Scenarios.goodQuality();
        assertThat(eval(good, 0.45, 0.45, 0.1).hint()).isEqualTo(Hint.MOVE_CLOSER);
        assertThat(eval(good, 0.1, 0.1, 0.8).hint()).isEqualTo(Hint.MOVE_BACK);
        assertThat(eval(good, 0.75, 0.3, 0.2).hint()).isEqualTo(Hint.CENTER_FACE);
        assertThat(eval(good, 0.3, 0.3, 0.4).passed()).isTrue();
        assertThat(eval(good, 0.3, 0.3, 0.4).reasonOptional()).isEmpty();
        assertThat(gate.minFaceQuality()).isEqualTo(0.4);
    }

    @Test
    void worstSubScoreChoosesHint() {
        assertThat(eval(new FaceQuality(1, 0.9, 0.1, 0.9, 1, 0.9), 0.3, 0.3, 0.4).hint()).isEqualTo(Hint.IMPROVE_LIGHTING);
        assertThat(eval(new FaceQuality(1, 0.1, 0.9, 0.9, 1, 0.9), 0.3, 0.3, 0.4).hint()).isEqualTo(Hint.HOLD_STILL);
        assertThat(eval(new FaceQuality(1, 0.9, 0.9, 0.1, 1, 0.9), 0.3, 0.3, 0.4).hint()).isEqualTo(Hint.LOOK_AT_CAMERA);
        assertThat(eval(new FaceQuality(1, 0.9, 0.9, 0.9, 0.1, 0.9), 0.3, 0.3, 0.4).hint()).isEqualTo(Hint.LOOK_AT_CAMERA);
        assertThat(eval(new FaceQuality(1, 0.9, 0.9, 0.9, 1, 0.1), 0.35, 0.35, 0.3).hint()).isEqualTo(Hint.MOVE_CLOSER);
        assertThat(eval(new FaceQuality(1, 0.9, 0.9, 0.9, 1, 0.1), 0.25, 0.25, 0.5).hint()).isEqualTo(Hint.MOVE_BACK);
    }

    /** Regression: a head-turn challenge lowers pose quality exactly as the subject complies. */
    @Test
    void headMotionChallengeIgnoresPoseButKeepsOtherChecks() {
        FaceQuality turned = new FaceQuality(1, 0.9, 0.9, 0.05, 1, 0.9);
        var turnedFace = Scenarios.analysis(turned, Scenarios.neutralFace().box(0.3, 0.3, 0.4, 0.4).build(), List.of(), null);
        assertThat(gate.evaluate(turnedFace).passed()).isFalse();
        assertThat(gate.evaluate(turnedFace, true).passed()).isTrue();

        FaceQuality turnedAndDark = new FaceQuality(1, 0.9, 0.1, 0.05, 1, 0.9);
        var dark = Scenarios.analysis(turnedAndDark, Scenarios.neutralFace().box(0.3, 0.3, 0.4, 0.4).build(), List.of(), null);
        assertThat(gate.evaluate(dark, true).passed()).isFalse();
        assertThat(gate.evaluate(Scenarios.multipleFaces(), true).passed()).isFalse();
    }

    @Test
    void evidenceHashIsDeterministicAndSensitive() {
        Challenge c = new Challenge(ChallengeType.BLINK, 0, 1, 0, 8000);
        List<ChallengeOutcome> log = List.of(new ChallengeOutcome(c, ChallengeOutcome.Result.PASSED, 400, 600));
        Map<String, Double> scores = Map.of("moire", 0.1, "cnn_texture", 0.2);
        String a = EvidenceHash.compute("s", Workflow.RESIDENT, LivenessResult.Decision.LIVE, scores, log, "00ff", "p1", "b1");
        String b = EvidenceHash.compute("s", Workflow.RESIDENT, LivenessResult.Decision.LIVE, Map.of("cnn_texture", 0.2, "moire", 0.1), log, "00ff", "p1", "b1");
        assertThat(a).hasSize(64).isEqualTo(b);
        assertThat(EvidenceHash.compute("s", Workflow.RESIDENT, LivenessResult.Decision.ATTACK, scores, log, "00ff", "p1", "b1")).isNotEqualTo(a);
        assertThat(EvidenceHash.compute("s", Workflow.RESIDENT, LivenessResult.Decision.LIVE, scores, List.of(), "00ff", "p1", "b1")).isNotEqualTo(a);
        assertThat(EvidenceHash.canonicalString("s", Workflow.RESIDENT, LivenessResult.Decision.LIVE, null, null, null, null, null))
                .isEqualTo("v1|s|RESIDENT|LIVE|||||");
        assertThat(EvidenceHash.canonicalString("s", Workflow.OPERATOR, LivenessResult.Decision.LIVE, scores, log, "00ff", "p1", "b1"))
                .isEqualTo("v1|s|OPERATOR|LIVE|cnn_texture=0.200000,moire=0.100000|BLINK:0:PASSED:400:600|00ff|p1|b1");
        assertThat(EvidenceHash.sha256Hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(EvidenceHash.toHex(new byte[] {0, (byte) 0xAB, 0x7F})).isEqualTo("00ab7f");
    }

    @Test
    void timeSources() {
        Clock fixed = Clock.fixed(Instant.parse("2026-01-01T00:00:01Z"), ZoneOffset.UTC);
        TimeSource fromClock = TimeSource.of(fixed);
        assertThat(fromClock.now()).isEqualTo(fixed.instant());
        assertThat(fromClock.nanoTime()).isEqualTo(fixed.millis() * 1_000_000L);
        assertThat(fromClock.millisSince(fromClock.nanoTime() - 5_000_000L)).isEqualTo(5);
        TimeSource fromNanos = TimeSource.of(() -> 3_500_000_000L);
        assertThat(fromNanos.nanoTime()).isEqualTo(3_500_000_000L);
        assertThat(fromNanos.now()).isEqualTo(Instant.ofEpochSecond(3, 500_000_000));
        TimeSource sys = TimeSource.system();
        assertThat(sys.nanoTime()).isPositive();
        assertThat(sys.now()).isNotNull();
    }
}
