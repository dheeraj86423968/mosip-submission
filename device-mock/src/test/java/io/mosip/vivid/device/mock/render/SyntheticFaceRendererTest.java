package io.mosip.vivid.device.mock.render;

import io.mosip.vivid.device.mock.scenario.OverlayKind;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class SyntheticFaceRendererTest {
    private final SyntheticFaceRenderer r = new SyntheticFaceRenderer(320, 240);

    @Test
    void timelineResolvesChallenges() {
        ExpressionTimeline tl = ExpressionTimeline.builder().blinkAt(1200).yaw(2000, 3000, 25).smile(3500, 4500).nod(5000, 6000, 10).build();
        assertThat(tl.stateAt(0)).usingRecursiveComparison().isEqualTo(FaceState.neutral());
        assertThat(tl.stateAt(1200).eyeOpen()).isLessThan(0.05);
        assertThat(tl.stateAt(1000).eyeOpen()).isEqualTo(1.0);
        assertThat(tl.stateAt(2500).yawDeg()).isEqualTo(25.0);
        assertThat(tl.stateAt(2100).yawDeg()).isBetween(1.0, 24.0);   // ramping in
        assertThat(tl.stateAt(4000).smile()).isEqualTo(1.0);
        assertThat(tl.stateAt(5250).pitchDeg()).isLessThan(-5.0);      // nod goes down first
        assertThat(tl.durationMs()).isEqualTo(6000);
    }

    /**
     * Pitch must change the face's 2-D geometry the way a real head does, not just translate it: when the head tilts
     * down the protruding nose tip moves down further than the eyes, the nose sits lower between eye line and mouth
     * line, and the eye-to-mouth span foreshortens. Looking up does the opposite for the nose.
     */
    @Test
    void pitchRotatesFeaturesInDepth() {
        double down = Math.toRadians(-20), up = Math.toRadians(20);
        double eye = -0.10, nose = 0.08, mouth = 0.27;
        for (double p : new double[] {down, up}) {
            double e = eye + SyntheticFaceRenderer.pitchShift(eye, 0.20, p);
            double n = nose + SyntheticFaceRenderer.pitchShift(nose, 0.36, p);
            double m = mouth + SyntheticFaceRenderer.pitchShift(mouth, 0.20, p);
            double neutralFraction = (nose - eye) / (mouth - eye);
            double fraction = (n - e) / (m - e);
            assertThat(m - e).as("eye-mouth span foreshortens").isLessThan(mouth - eye);
            if (p < 0) {
                assertThat(n - nose).as("nose moves down more than eyes").isGreaterThan(e - eye).isPositive();
                assertThat(fraction).isGreaterThan(neutralFraction + 0.1);
            } else {
                assertThat(n - nose).as("nose moves up more than eyes").isLessThan(e - eye).isNegative();
                assertThat(fraction).isLessThan(neutralFraction - 0.1);
            }
        }
        assertThat(SyntheticFaceRenderer.pitchShift(0.27, 0.2, 0)).isZero();   // neutral geometry unchanged
        // the published ground-truth landmarks follow the same geometry, mirrored for turns to the subject's right
        double[] n = r.landmarks(FaceState.neutral()), dn = r.landmarks(new FaceState(0, -20, 1, 0, 0, 0));
        assertThat(dn[5] - dn[1]).isGreaterThan(n[5] - n[1]);                     // nose drops relative to the eyes
        assertThat(dn[7] - dn[1]).isLessThan(n[7] - n[1]);                        // eye-mouth span foreshortens
        assertThat(n[0]).isLessThan(n[2]);
        double[] left = r.landmarks(new FaceState(20, 5, 1, 0, 0, 0)), right = r.landmarks(new FaceState(-20, 5, 1, 0, 0, 0));
        for (int i = 0; i < 10; i += 2) {
            int j = i == 0 ? 2 : i == 2 ? 0 : i == 6 ? 8 : i == 8 ? 6 : i;
            assertThat(right[i]).isCloseTo(320 - left[j], org.assertj.core.data.Offset.offset(1e-9));
            assertThat(right[i + 1]).isCloseTo(left[j + 1], org.assertj.core.data.Offset.offset(1e-9));
        }
        // the eye-centre landmark lands on the drawn iris/pupil, the nose landmark on the nose stroke
        BufferedImage img = r.render(FaceState.neutral());
        assertThat(img.getRGB((int) Math.round(n[0]), (int) Math.round(n[1])) & 0xffffff).isNotEqualTo(0xe8be9a);
        boolean strokeNearNose = false;   // the nose is a curve ending at the tip; look in a 5 px neighbourhood
        for (int y = -5; y <= 5; y++) for (int x = -5; x <= 5; x++) {
            strokeNearNose |= (img.getRGB((int) Math.round(n[4]) + x, (int) Math.round(n[5]) + y) & 0xffffff) == 0xc99b78;
        }
        assertThat(strokeNearNose).isTrue();
        assertThat(diff(r.render(FaceState.neutral()), r.render(new FaceState(0, -20, 1, 0, 0, 0)))).isGreaterThan(50);
    }

    @Test
    void differentStatesProduceDifferentImages() {
        BufferedImage neutral = r.render(FaceState.neutral());
        BufferedImage blink = r.render(new FaceState(0, 0, 0, 0, 0, 0));
        BufferedImage turned = r.render(new FaceState(30, 0, 1, 0, 0, 0));
        assertThat(diff(neutral, blink)).isGreaterThan(50);
        assertThat(diff(neutral, turned)).isGreaterThan(diff(neutral, blink));
        assertThat(diff(neutral, r.render(FaceState.neutral()))).isZero();   // deterministic
    }

    @Test
    void overlaysAlterTextureDeterministically() {
        BufferedImage a = r.render(FaceState.neutral()), b = r.render(FaceState.neutral()), c = r.render(FaceState.neutral());
        Overlays.apply(OverlayKind.PRINT_TEXTURE, a, 7, 0);
        Overlays.apply(OverlayKind.PRINT_TEXTURE, b, 7, 500);
        Overlays.apply(OverlayKind.MOIRE_GRID, c, 7, 0);
        assertThat(diff(a, b)).isZero();                       // print texture does not move
        assertThat(diff(a, r.render(FaceState.neutral()))).isGreaterThan(1000);
        assertThat(diff(c, r.render(FaceState.neutral()))).isGreaterThan(1000);
        BufferedImage d = r.render(FaceState.neutral());
        Overlays.apply(OverlayKind.MOIRE_GRID, d, 7, 40);
        assertThat(diff(c, d)).isGreaterThan(0);               // moiré drifts over time
    }

    private static long diff(BufferedImage a, BufferedImage b) {
        long n = 0;
        for (int y = 0; y < a.getHeight(); y++) for (int x = 0; x < a.getWidth(); x++) if (a.getRGB(x, y) != b.getRGB(x, y)) n++;
        return n;
    }
}
