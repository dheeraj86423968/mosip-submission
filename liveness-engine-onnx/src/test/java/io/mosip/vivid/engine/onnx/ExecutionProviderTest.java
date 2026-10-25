package io.mosip.vivid.engine.onnx;

import ai.onnxruntime.OrtProvider;
import ai.onnxruntime.OrtSession;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The opt-in execution provider and its CPU fallback, exercised with the stock CPU-only onnxruntime jar. */
class ExecutionProviderTest {
    private static final Path YUNET = Path.of("models/face_detection_yunet_2023mar.onnx");

    @Test
    void parsesNamesAndAliases() {
        assertThat(ExecutionProvider.parse("cpu")).isEqualTo(ExecutionProvider.CPU);
        assertThat(ExecutionProvider.parse(" CUDA ")).isEqualTo(ExecutionProvider.CUDA);
        assertThat(ExecutionProvider.parse("oneDNN")).isEqualTo(ExecutionProvider.DNNL);
        assertThat(ExecutionProvider.parse("open-vino")).isEqualTo(ExecutionProvider.OPENVINO);
        assertThat(ExecutionProvider.parse("DirectML")).isEqualTo(ExecutionProvider.DIRECTML);
        assertThat(ExecutionProvider.parse("core_ml")).isEqualTo(ExecutionProvider.COREML);
        assertThat(ExecutionProvider.parse("auto")).isEqualTo(ExecutionProvider.AUTO);
        assertThat(ExecutionProvider.parse("")).isEqualTo(ExecutionProvider.CPU);
        assertThatThrownBy(() -> ExecutionProvider.parse("tpu")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tpu");
    }

    @Test
    void cpuStaysTheDefaultAndThePropertyIsOptIn() {
        assertThat(EngineConfig.builder().build().executionProvider()).isEqualTo(ExecutionProvider.CPU);
        assertThat(EngineConfig.fromProperties(new Properties(), null).executionProvider()).isEqualTo(ExecutionProvider.CPU);
        Properties p = new Properties();
        p.setProperty("vivid.engine.executionProvider", "openvino");
        assertThat(EngineConfig.fromProperties(p, null).executionProvider()).isEqualTo(ExecutionProvider.OPENVINO);
    }

    @Test
    void stockJarIsCpuOnly() {
        assertThat(ExecutionProvider.available()).contains(OrtProvider.CPU);
    }

    @Test
    void providerMissingFromTheBuildFallsBackToCpu() throws Exception {
        try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
            ExecutionProvider.Resolution r = ExecutionProvider.apply(opts, ExecutionProvider.CUDA, EnumSet.of(OrtProvider.CPU));
            assertThat(r.requested()).isEqualTo(ExecutionProvider.CUDA);
            assertThat(r.used()).isEqualTo(ExecutionProvider.CPU);
            assertThat(r.fellBack()).isTrue();
            assertThat(r.fallbackReason()).contains("not in this ONNX Runtime build");
        }
    }

    @Test
    void providerThatFailsToRegisterFallsBackToCpu() throws Exception {
        // Claim the providers are compiled in: the CPU-only native library then rejects the registration call
        // itself, which is the "compiled in but CUDA/cuDNN or the OpenVINO runtime is missing" path.
        for (ExecutionProvider ep : new ExecutionProvider[] {ExecutionProvider.CUDA, ExecutionProvider.DNNL, ExecutionProvider.OPENVINO}) {
            try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
                ExecutionProvider.Resolution r = ExecutionProvider.apply(opts, ep, EnumSet.of(OrtProvider.CPU, ep.ortProvider()));
                assertThat(r.used()).as(ep.name()).isEqualTo(ExecutionProvider.CPU);
                assertThat(r.fallbackReason()).as(ep.name()).contains("failed to register");
            }
        }
    }

    @Test
    void autoPicksCpuWithoutAWarningWhenNothingElseIsAvailable() throws Exception {
        try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
            ExecutionProvider.Resolution r = ExecutionProvider.apply(opts, ExecutionProvider.AUTO, ExecutionProvider.available());
            assertThat(r.used()).isEqualTo(ExecutionProvider.CPU);
            assertThat(r.fellBack()).isFalse();
        }
    }

    @Test
    void modelLoadsAndRunsOnCpuWhenAnAcceleratorIsRequested() {
        assumeTrue(Files.isRegularFile(YUNET), "models not fetched");
        try (OrtModel m = OrtModel.load(YUNET, 1, ExecutionProvider.CUDA)) {
            assertThat(m.executionProvider().requested()).isEqualTo(ExecutionProvider.CUDA);
            assertThat(m.executionProvider().used()).isEqualTo(ExecutionProvider.CPU);
            float[] out = m.runFirst(new float[3 * 640 * 640], new long[] {1, 3, 640, 640});
            assertThat(out).isNotEmpty();
        }
        try (OrtModel m = OrtModel.load(YUNET, 1)) {
            assertThat(m.executionProvider().used()).isEqualTo(ExecutionProvider.CPU);
            assertThat(m.executionProvider().fellBack()).isFalse();
        }
    }
}
