package io.mosip.vivid.engine.onnx;

import io.mosip.vivid.api.FrameAnalysis;
import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.PixelFormat;
import io.mosip.vivid.engine.detect.FaceDetection;
import io.mosip.vivid.engine.signal.ColorStatsSignal;
import io.mosip.vivid.engine.signal.DepthIrSettings;
import io.mosip.vivid.engine.signal.DepthSignal;
import io.mosip.vivid.engine.signal.IrSignal;
import io.mosip.vivid.engine.signal.MoireSignal;
import io.mosip.vivid.engine.signal.PlattCalibration;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** S8 wiring: the analyzer emits depth / IR scores only when the frame carries the planes. */
class DepthIrWiringTest {
    static final int W = 320, H = 240;
    static final FaceDetection FACE = new FaceDetection(110, 70, 100, 110, 0.95, new double[] {140, 110, 180, 110, 160, 135, 145, 155, 175, 155});

    static OnnxFrameAnalyzer analyzer() {
        return new OnnxFrameAnalyzer(rgb -> List.of(FACE), null, 5, List.of(
                new MoireSignal(PlattCalibration.identity()), new ColorStatsSignal(PlattCalibration.identity()),
                new DepthSignal(DepthIrSettings.defaults(), null), new IrSignal(DepthIrSettings.defaults(), null)), 0.7);
    }

    static FaceFrame.Builder rgb() {
        byte[] px = new byte[W * H * 3];
        new Random(3).nextBytes(px);
        return FaceFrame.builder().width(W).height(H).format(PixelFormat.RGB888).pixels(px);
    }

    static ByteBuffer flatDepth() {
        ByteBuffer b = ByteBuffer.allocate(W * H * 2).order(ByteOrder.LITTLE_ENDIAN);
        Random r = new Random(1);
        for (int i = 0; i < W * H; i++) b.putShort((short) (450 + Math.round(r.nextGaussian())));
        return b.flip();
    }

    @Test
    void rgbOnlyFramesCarryNoDepthOrIrScore() {
        FrameAnalysis a = analyzer().analyze(rgb().build());
        assertThat(a.signals()).extracting(SignalScore::signalId).containsExactly(Signals.MOIRE, Signals.COLOR_STATS);
    }

    @Test
    void depthAndIrPlanesAddScores() {
        byte[] ir = new byte[W * H];
        java.util.Arrays.fill(ir, (byte) 6);
        FrameAnalysis a = analyzer().analyze(rgb().depth(flatDepth()).ir(ByteBuffer.wrap(ir)).build());
        assertThat(a.signals()).extracting(SignalScore::signalId).containsExactly(Signals.MOIRE, Signals.COLOR_STATS, Signals.DEPTH, Signals.IR);
        SignalScore depth = a.signals().get(2);
        assertThat(depth.hardVeto()).isTrue();
        assertThat(depth.attackClass()).isEqualTo(Signals.ATTACK_PRINT);
    }

    @Test
    void configEnablesS8ByDefaultAndParsesThresholds() {
        assertThat(EngineConfig.builder().build().enabledSignals()).contains(Signals.DEPTH, Signals.IR);
        Properties p = new Properties();
        p.setProperty("vivid.engine.depth.byteOrder", "BIG_ENDIAN");
        p.setProperty("vivid.engine.depth.scaleMm", "0.25");
        p.setProperty("vivid.engine.depth.hardVeto", "false");
        p.setProperty("vivid.engine.depth.vetoPlaneReliefMm", "1.0");
        p.setProperty("vivid.engine.ir.darkFraction", "0.2");
        p.setProperty("vivid.engine.calibration.depth.a", "2");
        EngineConfig c = EngineConfig.fromProperties(p, Path.of("."));
        DepthIrSettings d = c.depthIr();
        assertThat(d.depthByteOrder()).isEqualTo(ByteOrder.BIG_ENDIAN);
        assertThat(d.depthScaleMm()).isEqualTo(0.25);
        assertThat(d.depthHardVeto()).isFalse();
        assertThat(d.vetoPlaneReliefMm()).isEqualTo(1.0);
        assertThat(d.irDarkFraction()).isEqualTo(0.2);
        assertThat(d.quadReliefMm()).isEqualTo(DepthIrSettings.defaults().quadReliefMm());
        assertThat(c.calibration(Signals.DEPTH).a()).isEqualTo(2.0);
    }
}
