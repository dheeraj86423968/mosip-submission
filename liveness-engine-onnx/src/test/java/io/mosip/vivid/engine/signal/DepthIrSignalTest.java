package io.mosip.vivid.engine.signal;

import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.PixelFormat;
import io.mosip.vivid.engine.detect.FaceDetection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S8 on synthetic planes ({@link SyntheticDepth}). These check the geometry and the decision rules, not real-sensor
 * performance: no physical depth or IR camera was available.
 */
class DepthIrSignalTest {

    static FaceFrame frame(ByteBuffer depth, ByteBuffer ir) {
        return FaceFrame.builder().width(SyntheticDepth.W).height(SyntheticDepth.H).format(PixelFormat.RGB888)
                .pixels(new byte[SyntheticDepth.W * SyntheticDepth.H * 3]).depth(depth).ir(ir).build();
    }

    static FaceContext ctx(FaceDetection face) {
        return new FaceContext(SyntheticFaces.skinTexture(1), face, 0, 0, null);
    }

    static SignalScore depth(DepthSignal s, double[] mm, FaceDetection face) {
        return s.score(ctx(face), frame(SyntheticDepth.gray16(mm), null)).orElseThrow();
    }

    static SignalScore depth(double[] mm) { return depth(new DepthSignal(null, null), mm, SyntheticFaces.face()); }

    static SignalScore ir(ByteBuffer plane) {
        return new IrSignal(null, null).score(ctx(SyntheticFaces.face()), frame(null, plane)).orElseThrow();
    }

    // ---------------------------------------------------------------- depth

    @ParameterizedTest(name = "live head yaw={0} pitch={1}")
    @CsvSource({"0,0", "25,0", "-25,0", "0,12", "-20,-10"})
    void liveHeadHasReliefAndPasses(double yaw, double pitch) {
        DepthSignal s = new DepthSignal(null, null);
        double[] z = SyntheticDepth.degrade(SyntheticDepth.head(yaw, pitch), 1.5, 0.02, 11);
        SignalScore r = depth(s, z, SyntheticDepth.detection(yaw, pitch));
        DepthSignal.Measurement m = s.lastMeasurement();
        assertThat(r.signalId()).isEqualTo(Signals.DEPTH);
        assertThat(r.attackProbability()).as("%s", m).isLessThan(0.3);
        assertThat(r.confidence()).isGreaterThan(0.9);
        assertThat(r.hardVeto()).isFalse();
        assertThat(m.planeReliefMm).as("face is far from planar").isGreaterThan(5);
        assertThat(m.noseProtrusionMm).as("nose stands proud of the quadratic surface").isGreaterThan(6);
    }

    @ParameterizedTest(name = "sheet yaw={0} pitch={1}")
    @CsvSource({"0,0", "30,10", "45,0", "-20,-25"})
    void flatOrTiltedSheetIsPlanarAndVetoes(double yaw, double pitch) {
        DepthSignal s = new DepthSignal(null, null);
        double[] z = SyntheticDepth.degrade(SyntheticDepth.sheet(450, yaw, pitch, 90, 110), 1.5, 0.02, 3);
        SignalScore r = depth(s, z, SyntheticFaces.face());
        assertThat(s.lastMeasurement().planeReliefMm).as("%s", s.lastMeasurement()).isLessThan(1.0);
        assertThat(r.attackProbability()).isGreaterThan(0.95);
        assertThat(r.hardVeto()).isTrue();
        assertThat(r.attackClass()).isEqualTo(Signals.ATTACK_PRINT);
    }

    @Test
    void inverseDepthFitRemovesPerspectiveOfAStrongTilt() {
        // 45 deg tilt, noise-free: a Z-linear plane would leave millimetres of curvature; the 1/Z fit leaves quantisation only.
        DepthSignal s = new DepthSignal(null, null);
        depth(s, SyntheticDepth.sheet(450, 45, 0, 90, 110), SyntheticFaces.face());
        assertThat(s.lastMeasurement().planeRmsMm).isLessThan(0.6);
    }

    /**
     * Judgement call, documented in 03-liveness-and-pad.md: a print bent into a cylinder is not planar (plane relief
     * 2.6-5.6 mm for radii 150-80 mm), so it does not hit the planar hard veto. It is still almost exactly quadratic
     * and has no nose, so the signal flags it at the fused-only cap and leaves the decision to fusion.
     */
    @ParameterizedTest(name = "cylinder radius={0} mm")
    @CsvSource({"150", "100", "80"})
    void curvedPaperIsFlaggedButNotVetoed(double radius) {
        DepthSignal s = new DepthSignal(null, null);
        SignalScore r = depth(s, SyntheticDepth.degrade(SyntheticDepth.cylinder(450, radius, 90, 110), 1.5, 0.02, 4), SyntheticFaces.face());
        DepthSignal.Measurement m = s.lastMeasurement();
        assertThat(m.planeReliefMm).as("%s", m).isGreaterThan(DepthIrSettings.defaults().vetoPlaneReliefMm());
        assertThat(m.quadReliefMm).isLessThan(1.0);
        assertThat(m.noseProtrusionMm).isLessThan(2.0);
        assertThat(r.attackProbability()).isEqualTo(DepthIrSettings.defaults().depthMaxFusedProbability());
        assertThat(r.hardVeto()).isFalse();
    }

    @Test
    void noisyDepthWithHolesStillSeparatesFaceFromSheet() {
        double[] live = SyntheticDepth.degrade(SyntheticDepth.head(0, 0), 4, 0.25, 21);
        double[] sheet = SyntheticDepth.degrade(SyntheticDepth.sheet(450, 20, 0, 90, 110), 4, 0.25, 22);
        SignalScore l = depth(live), a = depth(sheet);
        assertThat(l.attackProbability()).isLessThan(0.3);
        assertThat(l.confidence()).isBetween(0.5, 0.95);
        assertThat(a.attackProbability()).isGreaterThan(0.8);
        assertThat(l.hardVeto()).isFalse();
    }

    @Test
    void mostlyHolesGivesNoEvidence() {
        SignalScore r = depth(SyntheticDepth.degrade(SyntheticDepth.head(0, 0), 1.5, 0.75, 5));
        assertThat(r.confidence()).isZero();
        assertThat(r.hardVeto()).isFalse();
        SignalScore empty = depth(new double[SyntheticDepth.W * SyntheticDepth.H]);
        assertThat(empty.confidence()).isZero();
    }

    @Test
    void outOfRangeFaceGivesNoEvidence() {
        double[] far = SyntheticDepth.head(0, 0);
        for (int i = 0; i < far.length; i++) far[i] += 2500;
        assertThat(depth(far).confidence()).isZero();
    }

    @Test
    void vetoCanBeDisabledAndByteOrderConfigured() {
        DepthIrSettings noVeto = DepthIrSettings.builder().depthHardVeto(false).build();
        SignalScore r = depth(new DepthSignal(noVeto, null), SyntheticDepth.sheet(450, 0, 0, 90, 110), SyntheticFaces.face());
        assertThat(r.hardVeto()).isFalse();
        assertThat(r.attackProbability()).isEqualTo(0.85);

        DepthIrSettings big = DepthIrSettings.builder().depthByteOrder(ByteOrder.BIG_ENDIAN).build();
        double[] head = SyntheticDepth.head(0, 0);
        SignalScore be = new DepthSignal(big, null).score(ctx(SyntheticFaces.face()),
                frame(SyntheticDepth.gray16(head, ByteOrder.BIG_ENDIAN), null)).orElseThrow();
        assertThat(be.attackProbability()).isLessThan(0.3);
        assertThat(be.confidence()).isGreaterThan(0.9);
    }

    // ---------------------------------------------------------------- IR

    @Test
    void liveIrPassesIn8And16Bit() {
        byte[] live = SyntheticDepth.irLive(SyntheticDepth.head(0, 0));
        for (SignalScore r : new SignalScore[] {ir(ByteBuffer.wrap(live)), ir(SyntheticDepth.ir16(live))}) {
            assertThat(r.signalId()).isEqualTo(Signals.IR);
            assertThat(r.attackProbability()).isLessThan(0.1);
            assertThat(r.confidence()).isGreaterThan(0.9);
        }
    }

    @Test
    void irDarkScreenIsFlaggedAsScreenReplay() {
        SignalScore r = ir(ByteBuffer.wrap(SyntheticDepth.irScreen(1)));
        assertThat(r.attackProbability()).isGreaterThan(0.7);
        assertThat(r.attackClass()).isEqualTo(Signals.ATTACK_SCREEN);
        assertThat(r.hardVeto()).as("IR is a heuristic and never vetoes").isFalse();
        assertThat(r.attackProbability()).isLessThanOrEqualTo(DepthIrSettings.defaults().irMaxFusedProbability());
    }

    @Test
    void irFlatPrintIsFlaggedAsPrint() {
        SignalScore r = ir(ByteBuffer.wrap(SyntheticDepth.irPrint(2)));
        assertThat(r.attackProbability()).isGreaterThan(0.7);
        assertThat(r.attackClass()).isEqualTo(Signals.ATTACK_PRINT);
        assertThat(r.hardVeto()).isFalse();
    }

    @Test
    void tiltedPrintBrightnessGradientIsNotMistakenForTexture() {
        // inverse-square fall-off across a 30 deg tilted sheet: about +-15 % brightness ramp across the face
        byte[] flat = SyntheticDepth.irPrint(3);
        byte[] ramp = new byte[flat.length];
        for (int i = 0; i < flat.length; i++) {
            double gain = 1 + 0.15 * ((i % SyntheticDepth.W) - 160) / 60.0;
            ramp[i] = (byte) Math.max(0, Math.min(255, Math.round((flat[i] & 0xff) * gain)));
        }
        SignalScore r = ir(ByteBuffer.wrap(ramp));
        assertThat(r.attackProbability()).isGreaterThan(0.7);
        assertThat(r.attackClass()).isEqualTo(Signals.ATTACK_PRINT);
    }

    @Test
    void unlitSceneLowersIrConfidence() {
        byte[] dark = new byte[SyntheticDepth.W * SyntheticDepth.H];
        java.util.Arrays.fill(dark, (byte) 5);
        SignalScore r = ir(ByteBuffer.wrap(dark));
        assertThat(r.confidence()).isLessThanOrEqualTo(0.3);
    }

    // ---------------------------------------------------------------- abstention

    @Test
    void absentOrMalformedPlanesAbstain() {
        FaceContext c = ctx(SyntheticFaces.face());
        FaceFrame rgbOnly = frame(null, null);
        assertThat(new DepthSignal(null, null).score(c, rgbOnly)).isEmpty();
        assertThat(new IrSignal(null, null).score(c, rgbOnly)).isEmpty();
        FaceFrame tiny = frame(ByteBuffer.allocate(16), ByteBuffer.allocate(16));
        assertThat(new DepthSignal(null, null).score(c, tiny)).isEmpty();
        assertThat(new IrSignal(null, null).score(c, tiny)).isEmpty();
        // the plain RGB entry point is neutral: zero confidence, ignored by fusion
        assertThat(new DepthSignal(null, null).score(c).confidence()).isZero();
        Optional<SignalScore> noFace = new DepthSignal(null, null).scoreWithoutFace(0);
        assertThat(noFace).isEmpty();
    }
}
