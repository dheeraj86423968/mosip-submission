package io.mosip.vivid.engine.onnx;

import io.mosip.vivid.api.Signals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineConfigTest {
    @TempDir Path tmp;

    @Test
    void loadsPropertiesWithRelativePathsAndOverrides() throws Exception {
        Path props = tmp.resolve("engine.properties");
        Files.writeString(props, String.join("\n",
                "vivid.engine.threads=3",
                "vivid.engine.detector.model=m/yunet.onnx",
                "vivid.engine.antispoof.model=m/2.7_80x80_MiniFASNetV2.onnx",
                "vivid.engine.antispoof2.model=m/custom.onnx",
                "vivid.engine.antispoof2.inputSize=96",
                "vivid.engine.antispoof2.colorOrder=BGR",
                "vivid.engine.antispoof2.realClassIndex=2",
                "vivid.engine.embedding.model=/abs/sface.onnx",
                "vivid.engine.embedding.everyFrames=3",
                "vivid.engine.signals=moire, rppg",
                "vivid.engine.calibration.moire.a=2.0",
                "vivid.engine.calibration.moire.b=-1",
                "vivid.engine.calibration.moire.threshold=0.7",
                "vivid.engine.bundle.id=desk",
                "vivid.engine.bundle.version=1.2.0",
                "vivid.engine.selftest.0.file=selftest/live.jpg",
                "vivid.engine.selftest.0.signal=cnn_texture",
                "vivid.engine.selftest.0.max=0.3"));
        EngineConfig c = EngineConfig.load(props);
        assertThat(c.threads()).isEqualTo(3);
        assertThat(c.detectorModel()).contains(tmp.resolve("m/yunet.onnx"));
        assertThat(c.antiSpoofModels()).hasSize(2);
        AntiSpoofModelConfig mini = c.antiSpoofModels().get(0);
        assertThat(mini.inputSize()).isEqualTo(80);
        assertThat(mini.cropScale()).isEqualTo(2.7);
        assertThat(mini.bgr()).isTrue();
        assertThat(mini.realClassIndex()).isEqualTo(1);
        AntiSpoofModelConfig custom = c.antiSpoofModels().get(1);
        assertThat(custom.inputSize()).isEqualTo(96);
        assertThat(custom.bgr()).isTrue();
        assertThat(custom.realClassIndex()).isEqualTo(2);
        assertThat(c.embeddingModel()).contains(Path.of("/abs/sface.onnx"));
        assertThat(c.embeddingEveryFrames()).isEqualTo(3);
        assertThat(c.enabledSignals()).containsExactly(Signals.MOIRE, Signals.RPPG);
        assertThat(c.calibration(Signals.MOIRE).a()).isEqualTo(2.0);
        assertThat(c.calibration(Signals.MOIRE).threshold()).isEqualTo(0.7);
        assertThat(c.calibration(Signals.RPPG).a()).isEqualTo(1.0);
        assertThat(c.bundleId()).isEqualTo("desk");
        assertThat(c.selfTests()).hasSize(1);
        assertThat(c.selfTests().get(0).max()).isEqualTo(0.3);
    }

    @Test
    void presetsFromFileNames() {
        assertThat(AntiSpoofModelConfig.preset(Path.of("4_0_0_80x80_MiniFASNetV1SE.onnx"), 0).cropScale()).isEqualTo(4.0);
        assertThat(AntiSpoofModelConfig.preset(Path.of("2.7_80x80_MiniFASNetV2.onnx"), 0).cropScale()).isEqualTo(2.7);
        AntiSpoofModelConfig bundled = AntiSpoofModelConfig.preset(Path.of("antispoof_minifasnetv2se_128.onnx"), 0);
        assertThat(bundled.inputSize()).isEqualTo(128);
        assertThat(bundled.bgr()).isFalse();
        assertThat(bundled.realClassIndex()).isZero();
        assertThat(bundled.cropScale()).isEqualTo(1.2);
        assertThat(bundled.inputScale()).isEqualTo(1f / 255f);
        assertThat(bundled.flip()).isFalse();
        // upstream Silent-Face to_tensor does not divide by 255
        assertThat(AntiSpoofModelConfig.preset(Path.of("2.7_80x80_MiniFASNetV2.onnx"), 0).inputScale()).isEqualTo(1f);
        assertThat(AntiSpoofModelConfig.preset(Path.of("4_0_0_80x80_MiniFASNetV1SE.onnx"), 0).inputScale()).isEqualTo(1f);
        assertThat(AntiSpoofModelConfig.preset(Path.of("4_0_0_80x80_MiniFASNetV1SE.onnx"), 0).fitCrop()).isTrue();
        assertThat(bundled.fitCrop()).isFalse();
    }

    @Test
    void inputScaleAndFlipFromProperties() throws Exception {
        Path props = tmp.resolve("engine.properties");
        Files.writeString(props, String.join("\n",
                "vivid.engine.detector.model=yunet.onnx",
                "vivid.engine.antispoof.model=antispoof_minifasnetv2se_128.onnx",
                "vivid.engine.antispoof.flip=true",
                "vivid.engine.antispoof2.model=2.7_80x80_MiniFASNetV2.onnx",
                "vivid.engine.antispoof2.inputScale=0.5",
                "vivid.engine.antispoof2.cropMode=square"));
        EngineConfig c = EngineConfig.load(props);
        assertThat(c.antiSpoofModels().get(0).flip()).isTrue();
        assertThat(c.antiSpoofModels().get(0).inputScale()).isEqualTo(1f / 255f);
        assertThat(c.antiSpoofModels().get(1).flip()).isFalse();
        assertThat(c.antiSpoofModels().get(1).inputScale()).isEqualTo(0.5f);
        assertThat(c.antiSpoofModels().get(1).fitCrop()).isFalse();
        assertThat(c.antiSpoofModels().get(0).fitCrop()).isFalse();
        assertThat(c.antiSpoofModels().get(1).toString()).contains("80px").doesNotContain("raw");
        assertThatThrownBy(() -> new AntiSpoofModelConfig(Path.of("m.onnx"), 80, 2.7, true, 1, 0f, false, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validationAndDiscovery() throws Exception {
        assertThatThrownBy(() -> EngineConfig.builder().threads(0).build()).isInstanceOf(IllegalArgumentException.class);
        Files.write(tmp.resolve("face_detection_yunet_2023mar.onnx"), new byte[1]);
        Files.write(tmp.resolve("antispoof_x.onnx"), new byte[1]);
        Files.write(tmp.resolve("face_recognition_sface.onnx"), new byte[1]);
        Files.write(tmp.resolve("unrelated.onnx"), new byte[1]);
        EngineConfig c = OnnxEngineFactory.configFor(tmp);
        assertThat(c.detectorModel()).contains(tmp.resolve("face_detection_yunet_2023mar.onnx"));
        assertThat(c.antiSpoofModels()).hasSize(1);
        // Silent-Face files are only used when no other anti-spoof model is present
        Files.write(tmp.resolve("2.7_80x80_MiniFASNetV2.onnx"), new byte[1]);
        Files.write(tmp.resolve("4_0_0_80x80_MiniFASNetV1SE.onnx"), new byte[1]);
        assertThat(OnnxEngineFactory.configFor(tmp).antiSpoofModels()).extracting(m -> m.path().getFileName().toString())
                .containsExactly("antispoof_x.onnx");
        Files.delete(tmp.resolve("antispoof_x.onnx"));
        assertThat(OnnxEngineFactory.configFor(tmp).antiSpoofModels()).extracting(m -> m.path().getFileName().toString())
                .containsExactly("2.7_80x80_MiniFASNetV2.onnx", "4_0_0_80x80_MiniFASNetV1SE.onnx");
        assertThat(c.embeddingModel()).contains(tmp.resolve("face_recognition_sface.onnx"));
        assertThatThrownBy(() -> new OnnxLivenessEngine(EngineConfig.builder().build()))
                .isInstanceOf(EngineException.class).hasMessageContaining("detector");
        assertThatThrownBy(() -> new OnnxLivenessEngine(EngineConfig.builder().detectorModel(tmp.resolve("nope.onnx")).build()))
                .isInstanceOf(EngineException.class).hasMessageContaining("not found");
    }
}
