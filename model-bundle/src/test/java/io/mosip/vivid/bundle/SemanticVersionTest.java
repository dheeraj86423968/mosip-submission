package io.mosip.vivid.bundle;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticVersionTest {
    @Test
    void parsesAndCompares() {
        assertThat(SemanticVersion.parse("1.2.3")).isLessThan(SemanticVersion.parse("1.10.0"));
        assertThat(SemanticVersion.parse("2.0.0")).isGreaterThan(SemanticVersion.parse("1.99.99"));
        assertThat(SemanticVersion.parse("1.0.0-rc.1")).isLessThan(SemanticVersion.parse("1.0.0"));
        assertThat(SemanticVersion.parse("1.0.0-alpha")).isLessThan(SemanticVersion.parse("1.0.0-beta"));
        assertThat(SemanticVersion.parse("1.0.0-rc.2")).isGreaterThan(SemanticVersion.parse("1.0.0-rc.1"));
        assertThat(SemanticVersion.parse("1.0.0+build.5")).isEqualTo(SemanticVersion.parse("1.0.0"));
        assertThat(SemanticVersion.parse("v0.1.0-SNAPSHOT").toString()).isEqualTo("0.1.0-SNAPSHOT");
    }

    @Test
    void rejectsGarbage() {
        assertThat(SemanticVersion.isValid("1.2")).isFalse();
        assertThat(SemanticVersion.isValid("01.2.3")).isFalse();
        assertThat(SemanticVersion.isValid("a.b.c")).isFalse();
        assertThatThrownBy(() -> SemanticVersion.parse("1.2")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void compatibilityMatrix() {
        BundleManifest m = TestBundles.manifest("b", "1.4.0");
        m.setMinEngineVersion("0.2.0");
        assertThat(new BundleCompatibility("0.2.0", "jvm").isCompatible(m)).isTrue();
        assertThat(new BundleCompatibility("0.3.1", "jvm").isCompatible(m)).isTrue();
        assertThat(new BundleCompatibility("0.1.9", "jvm").isCompatible(m)).isFalse();
        assertThat(new BundleCompatibility("0.2.0", "android").isCompatible(m)).isFalse();
        assertThat(new BundleCompatibility("0.2.0", "jvm", 0).isCompatible(m)).isFalse();
        assertThatThrownBy(() -> new BundleCompatibility("0.1.0", "jvm").check(m))
                .isInstanceOf(BundleRejectedException.class)
                .extracting(e -> ((BundleRejectedException) e).reason()).isEqualTo(BundleRejectedException.Reason.INCOMPATIBLE_ENGINE);
        assertThatThrownBy(() -> new BundleCompatibility("1.0.0", "android").check(m))
                .extracting(e -> ((BundleRejectedException) e).reason()).isEqualTo(BundleRejectedException.Reason.INCOMPATIBLE_PLATFORM);
    }
}
