package io.mosip.vivid.engine.signal;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class RppgSignalTest {
    /** Feeds 7 s of samples at an irregular ~15 fps with a 1.2 Hz (72 bpm) pulse of the given amplitude. */
    private static RppgSignal feed(double amplitude, double noise, long seed) {
        RppgSignal s = new RppgSignal(null);
        Random r = new Random(seed);
        double t = 100.0;
        while (t < 107.0) {
            double pulse = amplitude * Math.sin(2 * Math.PI * 1.2 * t);
            double n = noise * r.nextGaussian();
            // pulse is strongest in green; red weaker; blue weakest (haemoglobin absorption)
            s.addSample(t, 180 + 0.5 * pulse + n, 120 + pulse + n, 100 + 0.2 * pulse + n, 0);
            t += 1.0 / 15 + (r.nextDouble() - 0.5) * 0.03; // 52-82 ms jitter
        }
        return s;
    }

    @Test
    void periodicPulseIsDetectedAsLive() {
        RppgSignal.Evaluation e = feed(1.0, 0.2, 1).evaluate();
        assertThat(e.bpm).isCloseTo(72, offset(6.0));
        assertThat(e.snr).isGreaterThan(3);
        assertThat(e.attackProbability).isLessThan(0.2);
        assertThat(e.confidence).isGreaterThan(0.9);
    }

    @Test
    void noPulseIsAnAttack() {
        RppgSignal.Evaluation noise = feed(0, 0.5, 2).evaluate();
        assertThat(noise.attackProbability).isGreaterThan(0.5);
        RppgSignal.Evaluation constant = feed(0, 0, 3).evaluate();
        assertThat(constant.constant).isTrue();
        assertThat(constant.attackProbability).isGreaterThan(0.5);
    }

    @Test
    void confidenceRampsWithBufferAndGapsResetIt() {
        RppgSignal s = new RppgSignal(null);
        assertThat(s.evaluate().confidence).isZero();
        for (int i = 0; i < 30; i++) s.addSample(i / 15.0, 100, 100, 100, 0); // 2 s < minSeconds
        assertThat(s.evaluate().confidence).isZero();
        for (int i = 30; i < 60; i++) s.addSample(i / 15.0, 100, 100 + Math.sin(i), 100, 0); // 4 s
        double c4 = s.evaluate().confidence;
        assertThat(c4).isBetween(0.5, 0.75);
        s.addSample(10.0, 100, 100, 100, 0); // 6 s gap -> buffer restarts
        assertThat(s.bufferedSamples()).isEqualTo(1);
        s.reset();
        assertThat(s.bufferedSamples()).isZero();
    }

    @Test
    void scoresThroughTheSignalInterfaceFromFrames() {
        RppgSignal s = new RppgSignal(null);
        long t = 0;
        for (int i = 0; i < 100; i++) {
            int g = (int) Math.round(150 + 3 * Math.sin(2 * Math.PI * 1.1 * t / 1e9));
            int gg = g;
            FaceContext ctx = new FaceContext(SyntheticFaces.frame((x, y) -> SyntheticFaces.rgb(190, gg, 120)), SyntheticFaces.face(), t, i, null);
            s.score(ctx);
            t += 66_000_000L;
        }
        RppgSignal.Evaluation e = s.lastEvaluation();
        assertThat(e.seconds).isGreaterThan(6.0 - 0.2);
        assertThat(e.bpm).isCloseTo(66, offset(8.0));
        assertThat(e.attackProbability).isLessThan(0.3);
    }
}
