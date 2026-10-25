package io.mosip.vivid.testkit.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Wilson 95 % bounds checked against values computed independently (Python, z = 1.959964). */
class RateTest {
    private static void assertWilson(int k, int n, double lo, double hi) {
        Rate.Interval i = new Rate(k, n).wilson95();
        assertThat(i.lower()).as("lower %d/%d", k, n).isCloseTo(lo, within(1e-9));
        assertThat(i.upper()).as("upper %d/%d", k, n).isCloseTo(hi, within(1e-9));
    }

    @Test
    void wilsonMatchesHandComputedBounds() {
        assertWilson(1, 2, 0.0945312057, 0.9054687943);
        assertWilson(2, 3, 0.2076596008, 0.9385080553);
        assertWilson(1, 4, 0.0455872608, 0.6993581574);
        assertWilson(3, 40, 0.0258360258, 0.1986423352);   // the corpus-ethics.md example
        assertWilson(0, 20, 0.0, 0.1611251581);            // "0 errors in 20 -> upper bound about 16 %"
        assertWilson(1, 1, 0.2065493144, 1.0);
    }

    @Test
    void valueAndEdgeCases() {
        assertThat(new Rate(1, 4).value()).isEqualTo(0.25);
        assertThat(new Rate(0, 0).value()).isNaN();
        assertThat(new Rate(0, 0).wilson95().defined()).isFalse();
        assertThat(new Rate(0, 7).wilson95().lower()).isZero();
        assertThat(new Rate(7, 7).wilson95().upper()).isEqualTo(1.0);
        assertThatThrownBy(() -> new Rate(3, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Rate(-1, 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonResponseClassification() {
        assertThat(NonResponse.isNonResponse("NOT_LIVE", "FACE_NOT_DETECTED")).isTrue();
        assertThat(NonResponse.isNonResponse("NOT_LIVE", "session_timeout")).isTrue();
        assertThat(NonResponse.isNonResponse("ERROR", null)).isTrue();
        assertThat(NonResponse.isNonResponse("ATTACK", "PAD_DETECTED")).isFalse();
        assertThat(NonResponse.isNonResponse("NOT_LIVE", "CHALLENGE_TIMEOUT")).isFalse();
        assertThat(NonResponse.isNonResponse("NOT_LIVE", "LIVENESS_BELOW_THRESHOLD")).isFalse();
        assertThat(NonResponse.isNonResponse("LIVE", null)).isFalse();
        assertThat(NonResponse.isNonResponse(null, null)).isFalse();
        assertThat(NonResponse.isExcluded("CANCELLED", "CANCELLED")).isTrue();
        assertThat(NonResponse.isExcluded("LIVE", null)).isFalse();
    }
}
