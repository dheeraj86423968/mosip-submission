package io.mosip.vivid.core.action;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.FaceObservation;
import io.mosip.vivid.core.testing.Scenarios;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionDetectorsTest {

    private static final long ISSUED = 10_000_000_000L;
    private static final long MS = 1_000_000L;
    private static final ActionThresholds T = ActionThresholds.defaults();

    private static FaceObservation.Builder face() {
        return Scenarios.neutralFace();
    }

    /** Feeds faces at the given step starting one step after issue; returns the detector. */
    private static ActionDetector run(ChallengeType type, List<FaceObservation> faces, long stepMs) {
        return run(ActionDetectors.create(type, T, ISSUED), faces, stepMs, 1);
    }

    private static ActionDetector run(ActionDetector d, List<FaceObservation> faces, long stepMs, int startStep) {
        long t = ISSUED + startStep * stepMs * MS;
        for (FaceObservation f : faces) {
            d.update(f, t);
            t += stepMs * MS;
        }
        return d;
    }

    @ParameterizedTest
    @EnumSource(ChallengeType.class)
    void everyChallengeCompletesFromItsScriptedPerformance(ChallengeType type) {
        ActionDetector d = run(type, Scenarios.perform(type), 100);
        assertThat(d.type()).isEqualTo(type);
        assertThat(d.issuedAtNanos()).isEqualTo(ISSUED);
        assertThat(d.status()).isEqualTo(ActionStatus.COMPLETED);
        assertThat(d.status().isTerminal()).isTrue();
        assertThat(d.progress()).isEqualTo(1.0);
        assertThat(d.onsetNanos()).isPresent();
        assertThat(d.completedNanos()).isPresent();
        assertThat(d.onsetNanos().getAsLong()).isGreaterThan(ISSUED).isLessThanOrEqualTo(d.completedNanos().getAsLong());
        assertThat(d.timingApplies()).isEqualTo(type != ChallengeType.HOLD_STILL);
        if (type != ChallengeType.HOLD_STILL) {
            assertThat(d.onsetNanos().getAsLong()).isEqualTo(ISSUED + 400 * MS);
        }
        // Terminal: further input is ignored.
        assertThat(d.update(face().build(), ISSUED + 99_000 * MS)).isEqualTo(ActionStatus.COMPLETED);
    }

    @ParameterizedTest
    @EnumSource(ChallengeType.class)
    void neutralFacesNeverComplete(ChallengeType type) {
        if (type == ChallengeType.HOLD_STILL) {
            return;
        }
        ActionDetector d = run(type, java.util.Collections.nCopies(30, face().build()), 100);
        assertThat(d.status()).isEqualTo(ActionStatus.WAITING);
        assertThat(d.completedNanos()).isEmpty();
        assertThat(d.onsetNanos()).isEmpty();
    }

    @Test
    void nullFaceIsIgnored() {
        ActionDetector d = ActionDetectors.create(ChallengeType.BLINK, T, ISSUED);
        assertThat(d.update(null, ISSUED)).isEqualTo(ActionStatus.WAITING);
    }

    @Test
    void blinkRequiresOpenEyesAfterPromptBeforeClosureCounts() {
        FaceObservation closed = face().eyeAspect(0.1, 0.1).build();
        FaceObservation open = face().build();
        // Eyes already closed when prompted: the reopening must not count as a blink.
        ActionDetector d = run(ChallengeType.BLINK, List.of(closed, closed, open, open), 100);
        assertThat(d.status()).isEqualTo(ActionStatus.WAITING);
        run(d, List.of(closed, open), 100, 5);
        assertThat(d.status()).isEqualTo(ActionStatus.COMPLETED);
        assertThat(d.onsetNanos().getAsLong()).isEqualTo(ISSUED + 500 * MS);
    }

    @Test
    void blinkClosureDurationWindow() {
        FaceObservation closed = face().eyeAspect(0.1, 0.1).build();
        FaceObservation open = face().build();
        // Too short (40 ms at 40 ms step)
        ActionDetector shortBlink = run(ChallengeType.BLINK, List.of(open, closed, open), 40);
        assertThat(shortBlink.status()).isEqualTo(ActionStatus.WAITING);
        assertThat(shortBlink.onsetNanos()).isEmpty();
        // Too long: eyes held shut 700 ms
        ActionDetector longClose = run(ChallengeType.BLINK, List.of(open, closed, closed, closed, closed, closed, closed, closed, closed, open), 100);
        assertThat(longClose.status()).isEqualTo(ActionStatus.WAITING);
        assertThat(longClose.onsetNanos()).isEmpty();
        // Hysteresis: EAR between thresholds neither opens nor closes.
        ActionDetector mid = run(ChallengeType.BLINK, List.of(face().eyeAspect(0.22, 0.22).build(), closed, open), 100);
        assertThat(mid.status()).isEqualTo(ActionStatus.WAITING);
    }

    @Test
    void blinkTwiceNeedsBothWithinWindow() {
        FaceObservation closed = face().eyeAspect(0.1, 0.1).build();
        FaceObservation open = face().build();
        ActionDetector d = run(ChallengeType.BLINK_TWICE, List.of(open, closed, open), 100);
        assertThat(d.status()).isEqualTo(ActionStatus.IN_PROGRESS);
        assertThat(d.progress()).isEqualTo(0.5);
        // Second blink 3 s later: outside the 2.5 s window, so it only counts as the first of a new pair.
        run(d, List.of(closed, open), 100, 33);
        assertThat(d.status()).isEqualTo(ActionStatus.IN_PROGRESS);
        run(d, List.of(open, closed, open), 100, 40);
        assertThat(d.status()).isEqualTo(ActionStatus.COMPLETED);
        // A held closure between blinks resets the open requirement but keeps the counted blink.
        ActionDetector e = run(ChallengeType.BLINK_TWICE, List.of(open, closed, open, closed, closed, closed, closed, closed, closed, closed), 100);
        assertThat(e.status()).isEqualTo(ActionStatus.IN_PROGRESS);
        assertThat(e.onsetNanos()).isPresent();
    }

    @Test
    void smileFallbacks() {
        // Mouth narrowing heuristic when no smile score is produced.
        FaceObservation neutral = face().mouthAspect(0.4).build();
        FaceObservation wide = face().mouthAspect(0.25).build();
        ActionDetector d = run(ChallengeType.SMILE, List.of(neutral, wide, wide, wide, wide), 100);
        assertThat(d.status()).isEqualTo(ActionStatus.COMPLETED);
        // Blendshapes.
        FaceObservation bs = face().blendshapes(Map.of("mouthSmileLeft", 0.7, "mouthSmileRight", 0.7)).build();
        assertThat(run(ChallengeType.SMILE, List.of(face().build(), bs, bs, bs, bs), 100).status()).isEqualTo(ActionStatus.COMPLETED);
        // Smile already on at prompt time must drop first (anti-replay).
        FaceObservation smiling = face().smileScore(0.9).build();
        ActionDetector pre = run(ChallengeType.SMILE, List.of(smiling, smiling, smiling, smiling, smiling, smiling), 100);
        assertThat(pre.status()).isEqualTo(ActionStatus.WAITING);
        // Interrupted smile restarts the hold.
        ActionDetector broken = run(ChallengeType.SMILE, List.of(face().build(), smiling, smiling, face().build(), smiling, smiling), 100);
        assertThat(broken.status()).isEqualTo(ActionStatus.IN_PROGRESS);
        assertThat(broken.progress()).isBetween(0.3, 0.4);
        // No score, no blendshape, no baseline mouth: never a smile.
        FaceObservation flat = face().mouthAspect(0).build();
        assertThat(run(ChallengeType.SMILE, List.of(flat, flat, flat, flat, flat), 100).status()).isEqualTo(ActionStatus.WAITING);
    }

    @Test
    void eyebrowBlendshapeFallback() {
        FaceObservation up = face().blendshapes(Map.of("browInnerUp", 0.8)).build();
        assertThat(run(ChallengeType.RAISE_EYEBROWS, List.of(face().build(), up, up, up, up), 100).status()).isEqualTo(ActionStatus.COMPLETED);
    }

    @Test
    void headMotionNeedsNeutralBaselineAndReturn() {
        FaceObservation turned = face().pose(25, 0, 0).build();
        // Already turned when prompted: no baseline, no onset.
        ActionDetector d = run(ChallengeType.TURN_LEFT, List.of(turned, turned, turned), 100);
        assertThat(d.status()).isEqualTo(ActionStatus.WAITING);
        assertThat(d.progress()).isZero();
        // Wrong direction does nothing.
        ActionDetector wrong = run(ChallengeType.TURN_LEFT, List.of(face().build(), face().pose(-30, 0, 0).build()), 100);
        assertThat(wrong.status()).isEqualTo(ActionStatus.WAITING);
        // Turned but not returned: in progress.
        ActionDetector half = run(ChallengeType.TURN_RIGHT, List.of(face().build(), face().pose(-12, 0, 0).build(), face().pose(-25, 0, 0).build(), face().pose(-20, 0, 0).build()), 100);
        assertThat(half.status()).isEqualTo(ActionStatus.IN_PROGRESS);
        assertThat(half.progress()).isBetween(0.6, 1.0);
        // Gaze alone satisfies LOOK_UP.
        ActionDetector gaze = run(ChallengeType.LOOK_UP, List.of(face().build(), face().gaze(0, 0.7).build(), face().build()), 100);
        assertThat(gaze.status()).isEqualTo(ActionStatus.COMPLETED);
        // Gaze counts relative to the gaze at baseline: a constant offset in the gaze estimate is not a look.
        ActionDetector offset = run(ChallengeType.LOOK_DOWN, List.of(face().gaze(0, -0.78).build(), face().gaze(0, -0.78).build(), face().gaze(0, -0.78).build()), 100);
        assertThat(offset.status()).isNotEqualTo(ActionStatus.COMPLETED);
        ActionDetector fromOffset = run(ChallengeType.LOOK_UP, List.of(face().gaze(0, -0.78).build(), face().gaze(0, -0.1).build(), face().gaze(0, -0.75).build()), 100);
        assertThat(fromOffset.status()).isEqualTo(ActionStatus.COMPLETED);
        // A blink moves the eye band but is not a look: gaze is ignored while the eyes are closed.
        ActionDetector blink = run(ChallengeType.LOOK_UP, List.of(face().build(), face().eyeAspect(0.05, 0.05).gaze(0, 0.9).build(), face().build()), 100);
        assertThat(blink.status()).isNotEqualTo(ActionStatus.COMPLETED);
        // Without neutral-start requirement any pose becomes the baseline.
        ActionThresholds lax = T.toBuilder().requireNeutralStart(false).build();
        ActionDetector laxD = run(ActionDetectors.create(ChallengeType.TURN_LEFT, lax, ISSUED), List.of(face().pose(-20, 0, 0).build(), face().pose(5, 0, 0).build(), face().pose(-18, 0, 0).build()), 100, 1);
        assertThat(laxD.status()).isEqualTo(ActionStatus.COMPLETED);
    }

    @Test
    void holdStillRestartsOnMovement() {
        FaceObservation still = face().build();
        FaceObservation moved = face().box(0.4, 0.25, 0.4, 0.5).build();
        ActionDetector d = run(ChallengeType.HOLD_STILL, List.of(still, still, still, still, still, still, still, still, still, still, moved), 100);
        assertThat(d.status()).isEqualTo(ActionStatus.IN_PROGRESS);
        assertThat(d.progress()).isZero();
        assertThat(d.onsetNanos().getAsLong()).isEqualTo(ISSUED + 1100 * MS);
        run(d, java.util.Collections.nCopies(16, moved), 100, 12);
        assertThat(d.status()).isEqualTo(ActionStatus.COMPLETED);
        ActionDetector pose = run(ChallengeType.HOLD_STILL, List.of(still, face().pose(0, 9, 0).build()), 100);
        assertThat(pose.progress()).isZero();
    }

    @Test
    void blinkWithoutNeutralStartRequirement() {
        ActionThresholds lax = T.toBuilder().requireNeutralStart(false).build();
        ActionDetector d = run(ActionDetectors.create(ChallengeType.BLINK, lax, ISSUED), List.of(face().eyeAspect(0.1, 0.1).build(), face().build()), 100, 1);
        assertThat(d.status()).isEqualTo(ActionStatus.COMPLETED);
        ActionDetector s = run(ActionDetectors.create(ChallengeType.OPEN_MOUTH, lax, ISSUED), java.util.Collections.nCopies(5, face().mouthAspect(0.8).build()), 100, 1);
        assertThat(s.status()).isEqualTo(ActionStatus.COMPLETED);
    }

    @Test
    void thresholdsValidationAndCopy() {
        ActionThresholds t = ActionThresholds.builder().blinkCloseEar(0.18).blinkOpenEar(0.24).blinkMinClosedMillis(50).blinkMaxClosedMillis(400)
                .blinkTwiceWindowMillis(2000).smileScore(0.5).smileMouthNarrowingRatio(0.7).smileHoldMillis(200).mouthOpenAspect(0.45)
                .mouthOpenHoldMillis(250).browRaiseScore(0.55).browRaiseHoldMillis(250).turnYawDegrees(22).lookPitchDegrees(16)
                .nodPitchDegrees(12).gazeThreshold(0.4).baselineMaxDegrees(9).returnDegrees(7).holdStillMillis(1000)
                .holdStillCentreTolerance(0.02).holdStillPoseToleranceDegrees(4).requireNeutralStart(true).build();
        ActionThresholds copy = t.toBuilder().build();
        assertThat(copy.blinkCloseEar()).isEqualTo(0.18);
        assertThat(copy.blinkOpenEar()).isEqualTo(0.24);
        assertThat(copy.blinkMinClosedMillis()).isEqualTo(50);
        assertThat(copy.blinkMaxClosedMillis()).isEqualTo(400);
        assertThat(copy.blinkTwiceWindowMillis()).isEqualTo(2000);
        assertThat(copy.smileScore()).isEqualTo(0.5);
        assertThat(copy.smileMouthNarrowingRatio()).isEqualTo(0.7);
        assertThat(copy.smileHoldMillis()).isEqualTo(200);
        assertThat(copy.mouthOpenAspect()).isEqualTo(0.45);
        assertThat(copy.mouthOpenHoldMillis()).isEqualTo(250);
        assertThat(copy.browRaiseScore()).isEqualTo(0.55);
        assertThat(copy.browRaiseHoldMillis()).isEqualTo(250);
        assertThat(copy.turnYawDegrees()).isEqualTo(22);
        assertThat(copy.lookPitchDegrees()).isEqualTo(16);
        assertThat(copy.nodPitchDegrees()).isEqualTo(12);
        assertThat(copy.gazeThreshold()).isEqualTo(0.4);
        assertThat(copy.baselineMaxDegrees()).isEqualTo(9);
        assertThat(copy.returnDegrees()).isEqualTo(7);
        assertThat(copy.holdStillMillis()).isEqualTo(1000);
        assertThat(copy.holdStillCentreTolerance()).isEqualTo(0.02);
        assertThat(copy.holdStillPoseToleranceDegrees()).isEqualTo(4);
        assertThat(copy.requireNeutralStart()).isTrue();
        assertThatThrownBy(() -> ActionThresholds.builder().blinkCloseEar(0.3).blinkOpenEar(0.25).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActionThresholds.builder().blinkMinClosedMillis(600).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActionThresholds.builder().blinkMinClosedMillis(-1).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActionThresholds.builder().returnDegrees(12).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActionThresholds.builder().holdStillMillis(0).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActionDetectors.create(null, T, 0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ActionDetectors.create(ChallengeType.BLINK, null, 0)).isInstanceOf(NullPointerException.class);
        assertThat(ActionStatus.WAITING.isTerminal()).isFalse();
        assertThat(ActionStatus.FAILED.isTerminal()).isTrue();
    }
}
