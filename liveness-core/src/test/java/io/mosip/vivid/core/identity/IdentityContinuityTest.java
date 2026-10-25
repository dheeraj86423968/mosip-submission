package io.mosip.vivid.core.identity;

import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Signals;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class IdentityContinuityTest {

    private static float[] v(float... c) {
        return c;
    }

    @Test
    void cosineDistance() {
        assertThat(IdentityContinuity.compare(v(1, 0), v(1, 0))).isCloseTo(0.0, within(1e-9));
        assertThat(IdentityContinuity.compare(v(1, 0), v(0, 1))).isCloseTo(1.0, within(1e-9));
        assertThat(IdentityContinuity.compare(v(1, 0), v(-1, 0))).isCloseTo(2.0, within(1e-9));
        assertThat(IdentityContinuity.compare(v(2, 0), v(5, 0))).isCloseTo(0.0, within(1e-9));
        assertThat(IdentityContinuity.compare(v(1, 1), v(1, 0))).isCloseTo(1 - Math.sqrt(0.5), within(1e-9));
        assertThatThrownBy(() -> IdentityContinuity.compare(v(1), v(1, 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdentityContinuity.compare(v(), v())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdentityContinuity.compare(v(0, 0), v(1, 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdentityContinuity.compare(null, v(1))).isInstanceOf(NullPointerException.class);
    }

    @Test
    void anchorIsMeanOfFirstGoodEmbeddings() {
        IdentityContinuity id = new IdentityContinuity(0.45, 3);
        assertThat(id.observe(null, true)).isEmpty();
        assertThat(id.observe(v(), true)).isEmpty();
        assertThat(id.observe(v(1, 0), false)).isEmpty(); // poor quality does not build the anchor
        Optional<SignalScore> s1 = id.observe(v(1, 0), true);
        assertThat(s1).isPresent();
        assertThat(s1.get().signalId()).isEqualTo(Signals.IDENTITY);
        assertThat(s1.get().confidence()).isZero();
        assertThat(id.anchored()).isFalse();
        id.observe(v(1, 0), true);
        assertThat(id.anchor()).isEmpty();
        id.observe(v(0, 1), true);
        assertThat(id.anchored()).isTrue();
        float[] anchor = id.anchor().orElseThrow();
        assertThat(anchor[0]).isCloseTo(2f / 3, within(1e-6f));
        assertThat(anchor[1]).isCloseTo(1f / 3, within(1e-6f));
        anchor[0] = 99; // defensive copy
        assertThat(id.anchor().orElseThrow()[0]).isCloseTo(2f / 3, within(1e-6f));
        assertThat(Double.isNaN(id.lastDistance())).isTrue();
        assertThat(id.driftThreshold()).isEqualTo(0.45);
    }

    @Test
    void driftOnThreeConsecutiveFramesVetoes() {
        IdentityContinuity id = new IdentityContinuity(0.45, 1);
        id.observe(v(1, 0, 0), true);
        SignalScore same = id.observe(v(1, 0.1f, 0), true).orElseThrow();
        assertThat(same.hardVeto()).isFalse();
        assertThat(same.attackProbability()).isLessThan(0.1);
        assertThat(same.confidence()).isEqualTo(0.9);
        SignalScore d1 = id.observe(v(0, 1, 0), true).orElseThrow();
        SignalScore d2 = id.observe(v(0, 1, 0), false).orElseThrow();
        assertThat(d1.hardVeto()).isFalse();
        assertThat(d1.attackProbability()).isEqualTo(1.0); // distance 1.0 / (2*0.45) clamps to 1
        assertThat(d2.hardVeto()).isFalse();
        assertThat(d2.confidence()).isEqualTo(0.4);
        SignalScore d3 = id.observe(v(0, 1, 0), true).orElseThrow();
        assertThat(d3.hardVeto()).isTrue();
        assertThat(d3.attackClass()).isNull();
        assertThat(id.lastDistance()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void streakResetsOnMatchingFrameAndOnRetry() {
        IdentityContinuity id = new IdentityContinuity(0.45, 1);
        id.observe(v(1, 0), true);
        id.observe(v(0, 1), true);
        id.observe(v(0, 1), true);
        assertThat(id.observe(v(1, 0), true).orElseThrow().hardVeto()).isFalse();
        id.observe(v(0, 1), true);
        id.observe(v(0, 1), true);
        id.resetStreak();
        assertThat(id.observe(v(0, 1), true).orElseThrow().hardVeto()).isFalse();
        assertThat(id.observe(v(0, 1), true).orElseThrow().hardVeto()).isFalse();
        assertThat(id.observe(v(0, 1), true).orElseThrow().hardVeto()).isTrue();
    }

    @Test
    void validation() {
        assertThatThrownBy(() -> new IdentityContinuity(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdentityContinuity(0.4, 0)).isInstanceOf(IllegalArgumentException.class);
        IdentityContinuity id = new IdentityContinuity(0.4, 2);
        id.observe(v(1, 0), true);
        assertThatThrownBy(() -> id.observe(v(1, 0, 0), true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new IdentityContinuity(0.4).anchored()).isFalse();
    }
}
