package io.mosip.vivid.engine.pose;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class GeometryTest {
    @Test
    void solverSolvesAndDetectsSingularity() {
        double[] x = LinearSolver.solve(new double[][] {{2, 1, -1}, {-3, -1, 2}, {-2, 1, 2}}, new double[] {8, -11, -3});
        assertThat(x).containsExactly(new double[] {2, 3, -1}, offset(1e-9));
        assertThatThrownBy(() -> LinearSolver.solve(new double[][] {{1, 2}, {2, 4}}, new double[] {1, 2})).isInstanceOf(ArithmeticException.class);
        double[] ls = LinearSolver.leastSquares(new double[][] {{1, 0}, {1, 1}, {1, 2}}, new double[] {1, 3, 5});
        assertThat(ls).containsExactly(new double[] {1, 2}, offset(1e-9));
    }

    @Test
    void homographyRecoversKnownProjectiveMap() {
        Homography truth = Homography.of(new double[] {1.1, 0.05, 12, -0.03, 0.95, -7, 0.0004, -0.0002, 1});
        double[] src = {10, 10, 200, 15, 190, 220, 20, 180, 100, 100, 60, 140};
        double[] dst = new double[src.length];
        for (int i = 0; i < src.length; i += 2) {
            double[] p = truth.apply(src[i], src[i + 1]);
            dst[i] = p[0]; dst[i + 1] = p[1];
        }
        Homography fit = Homography.fit(src, dst);
        assertThat(fit.residualRms(src, dst)).isLessThan(1e-6);
        assertThat(fit.matrix()).containsExactly(truth.matrix(), offset(1e-6));
        assertThatThrownBy(() -> Homography.fit(new double[6], new double[6])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void affineFitAndResidual() {
        double[] m = {0.9, -0.1, 5, 0.2, 1.05, -3};
        double[] src = {0, 0, 10, 0, 0, 10, 10, 10, 5, 7};
        double[] dst = new double[src.length];
        for (int i = 0; i < src.length; i += 2) {
            dst[i] = m[0] * src[i] + m[1] * src[i + 1] + m[2];
            dst[i + 1] = m[3] * src[i] + m[4] * src[i + 1] + m[5];
        }
        assertThat(Homography.fitAffine(src, dst)).containsExactly(m, offset(1e-9));
        dst[8] += 2; // move one point off the affine model
        double[] fit = Homography.fitAffine(src, dst);
        assertThat(Homography.affineResidualRms(fit, src, dst)).isGreaterThan(0.3);
    }

    @Test
    void similarityTransformMapsTemplate() {
        double[] tpl = {38.29, 51.69, 73.53, 51.50, 56.02, 71.73, 41.54, 92.36, 70.72, 92.20};
        double s = 2.5, a = Math.toRadians(10);
        double[] src = new double[10];
        for (int i = 0; i < 10; i += 2) {
            src[i] = s * (Math.cos(a) * tpl[i] - Math.sin(a) * tpl[i + 1]) + 100;
            src[i + 1] = s * (Math.sin(a) * tpl[i] + Math.cos(a) * tpl[i + 1]) + 40;
        }
        double[] m = SimilarityTransform.fit(src, tpl);
        assertThat(SimilarityTransform.scale(m)).isCloseTo(1 / s, offset(1e-9));
        assertThat(SimilarityTransform.rotationDegrees(m)).isCloseTo(-10, offset(1e-9));
    }

    /** Projects a simple 3-D face model (IOD 60) rotated by yaw/pitch/roll. */
    static double[] face(double yawDeg, double pitchDeg, double rollDeg) {
        double[][] pts = {{-30, 0, 0}, {30, 0, 0}, {0, 36, -30}, {-24, 60, -5}, {24, 60, -5}};
        double y = Math.toRadians(yawDeg), p = Math.toRadians(pitchDeg), r = Math.toRadians(rollDeg);
        double[] out = new double[10];
        for (int i = 0; i < 5; i++) {
            double X = pts[i][0], Y = pts[i][1], Z = pts[i][2];
            // yaw about the vertical axis; subject turning to their left moves the nose to camera right (+x)
            double x1 = X * Math.cos(y) - Z * Math.sin(y), z1 = X * Math.sin(y) + Z * Math.cos(y);
            // pitch: looking up moves the nose up (-y)
            double y2 = Y * Math.cos(p) - z1 * Math.sin(p) * -1, z2 = -Y * Math.sin(p) * -1 + z1 * Math.cos(p);
            double x3 = x1 * Math.cos(r) - y2 * Math.sin(r), y3 = x1 * Math.sin(r) + y2 * Math.cos(r);
            out[2 * i] = 320 + x3; out[2 * i + 1] = 200 + y3;
        }
        return out;
    }

    @Test
    void headPoseSignsAndMagnitudes() {
        HeadPose frontal = HeadPose.estimate(face(0, 0, 0));
        assertThat(frontal.yaw()).isCloseTo(0, offset(1e-6));
        assertThat(frontal.roll()).isCloseTo(0, offset(1e-6));
        assertThat(Math.abs(frontal.pitch())).isLessThan(5);
        HeadPose left = HeadPose.estimate(face(25, 0, 0));
        assertThat(left.yaw()).isBetween(15.0, 40.0);
        assertThat(HeadPose.estimate(face(-25, 0, 0)).yaw()).isBetween(-40.0, -15.0);
        assertThat(HeadPose.estimate(face(0, 0, 20)).roll()).isCloseTo(20, offset(1e-6));
        assertThat(HeadPose.estimate(face(0, 0, 20)).yaw()).isCloseTo(0, offset(1e-6));
        double upPitch = HeadPose.estimate(face(0, 20, 0)).pitch(), downPitch = HeadPose.estimate(face(0, -20, 0)).pitch();
        assertThat(upPitch).isGreaterThan(downPitch + 15);
    }
}
