package io.mosip.vivid.engine.pose;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * Pitch from five landmarks, checked against a simple 3-D head projected at known angles.
 *
 * <p>The head model (millimetres; x to the camera's right, y down, z towards the camera, origin at the eye
 * midpoint) has eye centres, a nose tip in front of them and mouth corners at about eye depth. It rotates about a
 * neck pivot 80 mm below and 100 mm behind the eyes and is projected with a pinhole camera (f = 600 px) at 30 or
 * 50 cm, optionally from below the eyes as a laptop camera is. Several anatomies span the population: nose drop
 * 36-48 mm, nose protrusion 18-36 mm, eye-to-mouth 64-74 mm, mouth corners 12 mm behind to 3 mm in front of the eyes.
 * The last one has the proportions of the device mock's cartoon face.
 */
class HeadPoseTest {

    record Anatomy(String name, double iod, double noseDrop, double noseDepth, double eyeMouth, double mouthDepth, double mouthHalf) {
        double[][] points() {
            return new double[][] {{-iod / 2, 0, 0}, {iod / 2, 0, 0}, {0, noseDrop, noseDepth},
                    {-mouthHalf, eyeMouth, mouthDepth}, {mouthHalf, eyeMouth, mouthDepth}};
        }
    }

    static final Anatomy[] ANATOMIES = {
            new Anatomy("average", 63, 44, 30, 70, -2, 25),
            new Anatomy("flat nose, long face", 60, 38, 22, 72, -8, 24),
            new Anatomy("prominent nose, short face", 66, 48, 36, 66, 3, 27),
            new Anatomy("flat nose, deep-set mouth", 58, 42, 18, 74, -12, 22),
            new Anatomy("short nose", 62, 36, 32, 64, 0, 25),
            // device-mock cartoon: eyes 0.10 H above centre, nose 0.08 H below, mouth 0.27 H below, H = 288 px
            new Anatomy("cartoon", 0.44 * 225, 0.18 * 288, 0.16 * 288, 0.37 * 288, 0, 0.17 * 225),
    };

    /**
     * Pixel landmarks (YuNet order: subject-right eye, subject-left eye, nose, mouth corners) of the head pitched by
     * {@code pitchDeg} (positive = looking up), turned by {@code yawDeg} (positive = subject's left), seen from
     * {@code distMm} with the camera {@code camBelowDeg} below the eye line.
     */
    static double[] project(Anatomy a, double pitchDeg, double yawDeg, double rollDeg, double distMm, double camBelowDeg) {
        double[][] pts = a.points();
        double p = Math.toRadians(pitchDeg), yw = Math.toRadians(yawDeg), r = Math.toRadians(rollDeg), e = Math.toRadians(camBelowDeg);
        double pivotY = 80, pivotZ = -100;
        double[] out = new double[10];
        for (int i = 0; i < 5; i++) {
            double x = pts[i][0], y = pts[i][1] - pivotY, z = pts[i][2] - pivotZ;
            // pitch about the neck: looking up moves points in front of the pivot up (-y)
            double y1 = y * Math.cos(p) - z * Math.sin(p), z1 = y * Math.sin(p) + z * Math.cos(p);
            y1 += pivotY; z1 += pivotZ;
            // yaw: turning to the subject's left moves the nose (z > 0) to the camera's right (+x)
            double x2 = x * Math.cos(yw) + z1 * Math.sin(yw), z2 = -x * Math.sin(yw) + z1 * Math.cos(yw);
            // roll in the image plane
            double x3 = x2 * Math.cos(r) - y1 * Math.sin(r), y3 = x2 * Math.sin(r) + y1 * Math.cos(r);
            // camera below the eyes looking up at the face: same as the head pitched up by camBelowDeg
            double y4 = y3 * Math.cos(e) - z2 * Math.sin(e), z4 = y3 * Math.sin(e) + z2 * Math.cos(e);
            double depth = distMm - z4, f = 600;
            out[2 * i] = 320 + f * x3 / depth;
            out[2 * i + 1] = 240 + f * y4 / depth;
        }
        return out;
    }

    private static final double[][] VIEWS = {{500, 0}, {300, 0}, {500, 15}, {300, 15}};   // {distance mm, camera below deg}

    @Test
    void populationConstantsGiveLargeAbsoluteBiasSoNeutralMustBeCalibrated() {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (Anatomy a : ANATOMIES) for (double[] v : VIEWS) {
            double neutral = HeadPose.estimate(project(a, 0, 0, 0, v[0], v[1])).pitch();
            min = Math.min(min, neutral); max = Math.max(max, neutral);
        }
        // A neutral head can read beyond the 10 degree "neutral start" gate of the action detectors in both directions.
        assertThat(min).isLessThan(-10);
        assertThat(max).isGreaterThan(10);
    }

    @Test
    void calibratedPitchTracksUpAndDownForEveryAnatomyAndView() {
        for (Anatomy a : ANATOMIES) for (double[] v : VIEWS) {
            double[] n0 = project(a, 0, 0, 0, v[0], v[1]);
            double t0 = HeadPose.noseFraction(n0), s0 = HeadPose.noseDrop(n0);
            String where = a.name() + " at " + v[0] + " mm, camera " + v[1] + " deg below";
            assertThat(HeadPose.estimateRelative(project(a, 0, 0, 0, v[0], v[1]), t0, s0).pitch()).as(where).isCloseTo(0, offset(1e-9));
            double previous = Double.NEGATIVE_INFINITY;
            // -20..+30: the flattest nose with the deepest-set mouth (18 mm, 12 mm behind the eyes) peaks at about -18
            // degrees read for a -20 degree tilt and reads less for steeper tilts (its nose-to-eye drop stops growing);
            // the other anatomies stay monotonic to beyond -30.
            for (int deg = -20; deg <= 30; deg += 5) {
                double read = HeadPose.estimateRelative(project(a, deg, 0, 0, v[0], v[1]), t0, s0).pitch();
                // where the nose-support cap takes over from the mouth-referenced estimate, the curve may step back by up to 5 degrees (flattest nose, close camera)
                assertThat(read).as(where + " monotonic at " + deg).isGreaterThan(previous - 5);
                previous = read;
                if (deg != 0) {
                    assertThat(Math.signum(read)).as(where + " sign at " + deg).isEqualTo(Math.signum(deg));
                    // The gain depends on nose depth / eye-mouth distance, which a frontal view cannot measure; across
                    // these anatomies and views it stays between 0.7 and 2.1 (highest: prominent nose, close camera).
                    assertThat(read / deg).as(where + " gain at " + deg).isBetween(0.7, 2.1);
                }
            }
            // A 20 degree look up or down clears the 15 degree LOOK threshold; a 15 degree nod clears the 10 degree NOD threshold.
            assertThat(HeadPose.estimateRelative(project(a, 20, 0, 0, v[0], v[1]), t0, s0).pitch()).as(where).isGreaterThan(15);
            assertThat(HeadPose.estimateRelative(project(a, -20, 0, 0, v[0], v[1]), t0, s0).pitch()).as(where).isLessThan(-15);
            assertThat(HeadPose.estimateRelative(project(a, -15, 0, 0, v[0], v[1]), t0, s0).pitch()).as(where).isLessThan(-10);
        }
    }

    @Test
    void relativePitchKeepsYawAndRollAndIgnoresTurnsAndExpressions() {
        Random rnd = new Random(7);
        for (int i = 0; i < 200; i++) {
            Anatomy a = ANATOMIES[rnd.nextInt(ANATOMIES.length)];
            double[] lm = project(a, rnd.nextGaussian() * 15, rnd.nextGaussian() * 20, rnd.nextGaussian() * 8, 400, 0);
            HeadPose base = HeadPose.estimate(lm), calibrated = HeadPose.estimateRelative(lm, 0.5 + rnd.nextDouble() * 0.2, 0.5 + rnd.nextDouble() * 0.4);
            assertThat(calibrated.yaw()).isEqualTo(base.yaw());
            assertThat(calibrated.roll()).isEqualTo(base.roll());
        }
        double[] lm = project(ANATOMIES[0], 12, 5, 3, 500, 0);
        assertThat(HeadPose.estimateRelative(lm, Double.NaN, Double.NaN).pitch()).isEqualTo(HeadPose.estimate(lm).pitch());
        for (Anatomy a : ANATOMIES) {
            double[] n0 = project(a, 0, 0, 0, 500, 0);
            double t0 = HeadPose.noseFraction(n0), s0 = HeadPose.noseDrop(n0);
            // a 30 degree turn or a 15 degree roll is not a nod (cross-talk below the 10 degree NOD threshold)
            for (double yaw : new double[] {-30, -15, 15, 30}) {
                assertThat(HeadPose.estimateRelative(project(a, 0, yaw, 0, 500, 0), t0, s0).pitch()).as(a.name() + " yaw " + yaw).isBetween(-7.0, 7.0);
            }
            assertThat(HeadPose.estimateRelative(project(a, 0, 0, 15, 500, 0), t0, s0).pitch()).isCloseTo(0, offset(1e-6));
            // smiling lifts the mouth corners ~6 mm and opening the mouth lowers them ~5 mm: pitch must not move
            double[] neutral = project(a, 0, 0, 0, 500, 0);
            double[] smile = neutral.clone(), open = neutral.clone();
            double px = (neutral[2] - neutral[0]) / a.iod();   // pixels per mm
            smile[7] -= 6 * px; smile[9] -= 6 * px; open[7] += 5 * px; open[9] += 5 * px;
            assertThat(Math.abs(HeadPose.estimateRelative(smile, t0, s0).pitch())).isLessThanOrEqualTo(HeadPose.PITCH_SUPPORT_SLACK + 1e-9);
            assertThat(Math.abs(HeadPose.estimateRelative(open, t0, s0).pitch())).isLessThanOrEqualTo(HeadPose.PITCH_SUPPORT_SLACK + 1e-9);
            // ...whereas the mouth-referenced estimate alone moves by several degrees
            assertThat(Math.abs(HeadPose.estimate(smile).pitch() - HeadPose.estimate(neutral).pitch())).isGreaterThan(3);
        }
    }

    @Test
    void noseDropIgnoresRollAndScale() {
        Anatomy a = ANATOMIES[0];
        double s = HeadPose.noseDrop(project(a, 0, 0, 0, 500, 0));
        assertThat(s).isCloseTo(44.0 / 63.0, offset(0.05));
        assertThat(HeadPose.noseDrop(project(a, 0, 0, 25, 500, 0))).isCloseTo(s, offset(1e-9));
        double[] lm = project(a, 0, 0, 0, 500, 0), scaled = new double[10];
        for (int i = 0; i < 10; i++) scaled[i] = lm[i] * 2.5 + (i % 2 == 0 ? -100 : 40);
        assertThat(HeadPose.noseDrop(scaled)).isCloseTo(s, offset(1e-9));
        // distance does matter a little (perspective): one reason the neutral is calibrated per session
        assertThat(HeadPose.noseDrop(project(a, 0, 0, 0, 800, 0))).isCloseTo(s, offset(0.03));
    }

    @Test
    void baselineLearnsNeutralBeforeFirstChallengeAndThenStaysFixed() {
        Anatomy a = ANATOMIES[2];   // reads about -22 degrees with population constants
        PitchBaseline b = new PitchBaseline();
        assertThat(b.estimate(project(a, 0, 0, 0, 500, 0)).pitch()).isLessThan(-15);
        Random rnd = new Random(3);
        for (int i = 0; i < 20; i++) b.observe(project(a, rnd.nextGaussian() * 1.5, rnd.nextGaussian() * 3, 0, 500, 0));
        assertThat(b.neutralNoseDrop()).isNaN();                 // nothing applied until frozen
        b.freeze();
        assertThat(b.isFrozen()).isTrue();
        assertThat(b.estimate(project(a, 0, 0, 0, 500, 0)).pitch()).isCloseTo(0, offset(2.5));
        assertThat(b.estimate(project(a, -20, 0, 0, 500, 0)).pitch()).isLessThan(-15);
        assertThat(b.estimate(project(a, 20, 0, 0, 500, 0)).pitch()).isGreaterThan(15);
        double fixed = b.neutralNoseDrop();
        for (int i = 0; i < 20; i++) b.observe(project(a, -25, 0, 0, 500, 0));   // a held LOOK_DOWN must not move it
        assertThat(b.neutralNoseDrop()).isEqualTo(fixed);
        b.reset();
        assertThat(b.isFrozen()).isFalse();
        assertThat(b.neutralNoseDrop()).isNaN();
    }

    @Test
    void baselineRejectsTooFewTurnedOrInconsistentFrames() {
        Anatomy a = ANATOMIES[0];
        PitchBaseline few = new PitchBaseline();
        for (int i = 0; i < PitchBaseline.MIN_FRAMES - 1; i++) few.observe(project(a, 0, 0, 0, 500, 0));
        few.freeze();
        assertThat(few.neutralNoseDrop()).isNaN();

        PitchBaseline turned = new PitchBaseline();
        for (int i = 0; i < 20; i++) turned.observe(project(a, 0, 35, 0, 500, 0));   // only a profile view was seen
        turned.freeze();
        assertThat(turned.neutralNoseDrop()).isNaN();

        PitchBaseline restless = new PitchBaseline();
        for (int i = 0; i < 20; i++) restless.observe(project(a, i % 2 == 0 ? 25 : -25, 0, 0, 500, 0));
        restless.freeze();
        assertThat(restless.neutralNoseDrop()).isNaN();
        // rejected calibration falls back to the population constants
        double[] lm = project(a, 10, 0, 0, 500, 0);
        assertThat(restless.estimate(lm).pitch()).isEqualTo(HeadPose.estimate(lm).pitch());
    }
}
