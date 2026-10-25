package io.mosip.vivid.bundle;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BundleManifestTest {
    @Test
    void roundTripsJsonWithSnakeCaseCalibration() throws Exception {
        BundleManifest m = TestBundles.manifest("mosip-vivid-desktop", "1.0.0");
        m.getModels().forEach(e -> e.setSha256("a".repeat(64)));
        String json = new String(m.toJsonBytes(), StandardCharsets.UTF_8);
        assertThat(json).contains("\"platt_a\"").contains("\"inputShape\"").contains("\"expectedSignal\"");
        BundleManifest back = BundleManifest.parse(m.toJsonBytes());
        back.validate();
        assertThat(back).isEqualTo(m);
        assertThat(back.calibration("cnn_texture")).isPresent();
        assertThat(back.calibration("cnn_texture").get().apply(0.0)).isCloseTo(1 / (1 + Math.exp(-0.2)), org.assertj.core.data.Offset.offset(1e-9));
        assertThat(back.model("yunet").get().getInputShape()).containsExactly(1, 3, 320, 320);
        assertThat(back.modelByPurpose("anti-spoofing").get().getFile()).isEqualTo("antispoof.onnx");
        assertThat(back.referencedFiles()).containsExactly("detector.onnx", "antispoof.onnx", "selftest/live.jpg");
    }

    @Test
    void ignoresUnknownFieldsAndRejectsMalformed() throws Exception {
        BundleManifest m = BundleManifest.parse(("{\"bundleId\":\"x\",\"version\":\"1.0.0\",\"platform\":\"jvm\",\"minEngineVersion\":\"0.1.0\",\"future\":1,\"models\":[{\"name\":\"a\",\"file\":\"a.onnx\",\"sha256\":\"" + "0".repeat(64) + "\"}]}").getBytes(StandardCharsets.UTF_8));
        m.validate();
        assertThatThrownBy(() -> BundleManifest.parse("{not json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BundleRejectedException.class)
                .extracting(e -> ((BundleRejectedException) e).reason()).isEqualTo(BundleRejectedException.Reason.MALFORMED_MANIFEST);
    }

    @Test
    void validationCatchesEachField() {
        BundleManifest m = TestBundles.manifest("ok", "1.0.0");
        m.getModels().forEach(e -> e.setSha256("f".repeat(64)));
        assertThat(validationMessage(m)).isNull();

        m.setVersion("1.0"); assertThat(validationMessage(m)).contains("semver"); m.setVersion("1.0.0");
        m.setPlatform("ios"); assertThat(validationMessage(m)).contains("platform"); m.setPlatform("jvm");
        m.setBundleId("../evil"); assertThat(validationMessage(m)).contains("bundleId"); m.setBundleId("ok");
        m.getModels().get(0).setFile("../../etc/passwd"); assertThat(validationMessage(m)).contains("unsafe"); m.getModels().get(0).setFile("detector.onnx");
        m.getModels().get(0).setSha256("abc"); assertThat(validationMessage(m)).contains("sha256"); m.getModels().get(0).setSha256("f".repeat(64));
        m.getModels().get(1).setName("yunet"); assertThat(validationMessage(m)).contains("duplicate"); m.getModels().get(1).setName("other");
        m.getSelfTest().get(0).setExpectedMin(0.9); assertThat(validationMessage(m)).contains("expectedMin");
    }

    private static String validationMessage(BundleManifest m) {
        try {
            m.validate();
            return null;
        } catch (BundleRejectedException e) {
            assertThat(e.reason()).isEqualTo(BundleRejectedException.Reason.MALFORMED_MANIFEST);
            return e.getMessage();
        }
    }

    @Test
    void safePathRules() {
        assertThat(BundleManifest.isSafeRelativePath("models/a.onnx")).isTrue();
        assertThat(BundleManifest.isSafeRelativePath("/abs")).isFalse();
        assertThat(BundleManifest.isSafeRelativePath("a/../b")).isFalse();
        assertThat(BundleManifest.isSafeRelativePath("C:\\x")).isFalse();
        assertThat(BundleManifest.isSafeRelativePath("a//b")).isFalse();
        assertThat(BundleManifest.isSafeRelativePath("")).isFalse();
    }
}
