package io.mosip.vivid.engine.signal;

import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.engine.image.RgbImage;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class MoireAndColorSignalTest {
    private final MoireSignal moire = new MoireSignal(null);
    private final ColorStatsSignal color = new ColorStatsSignal(null);

    @Test
    void periodicScreenPatternScoresAsMoireAttack() {
        // fine, slightly rotated grating like the beat pattern of a screen pixel grid under a camera sensor
        RgbImage grating = SyntheticFaces.frame((x, y) -> {
            int v = (int) (128 + 90 * Math.sin(2 * Math.PI * (0.31 * x + 0.07 * y)));
            return SyntheticFaces.rgb(v, v, v);
        });
        SignalScore attack = moire.score(SyntheticFaces.ctx(grating));
        double[] fa = moire.lastFeatures();
        SignalScore live = moire.score(SyntheticFaces.ctx(SyntheticFaces.skinTexture(1)));
        double[] fl = moire.lastFeatures();
        org.slf4j.LoggerFactory.getLogger(getClass()).info("moire grating hf={} peak={} p={} | skin hf={} peak={} p={}", fa[0], fa[1], attack.attackProbability(), fl[0], fl[1], live.attackProbability());
        assertThat(fa[1]).as("peak score").isGreaterThan(fl[1]);
        assertThat(attack.attackProbability()).isGreaterThan(0.5);
        assertThat(attack.attackClass()).isEqualTo(Signals.ATTACK_SCREEN);
        assertThat(live.attackProbability()).isLessThan(attack.attackProbability());
        assertThat(live.attackProbability()).isLessThan(0.5);
    }

    @Test
    void flatPatchHasNoConfidence() {
        SignalScore s = moire.score(SyntheticFaces.ctx(SyntheticFaces.flat(200, 150, 120)));
        assertThat(s.confidence()).isZero();
        assertThat(MoireSignal.features(new float[64 * 64], 64)).containsExactly(0, 0);
    }

    @Test
    void whiteNoiseHasHighHighFrequencyRatio() {
        float[] noise = new float[64 * 64];
        Random r = new Random(5);
        for (int i = 0; i < noise.length; i++) noise[i] = r.nextFloat() * 255;
        assertThat(MoireSignal.features(noise, 64)[0]).isGreaterThan(0.5);
        float[] smooth = new float[64 * 64];
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) smooth[y * 64 + x] = (float) (100 + 40 * Math.sin(x / 10.0) + 30 * Math.cos(y / 13.0));
        assertThat(MoireSignal.features(smooth, 64)[0]).isLessThan(0.05);
    }

    @Test
    void colourStatsSeparatesSkinFromDesaturatedPrint() {
        SignalScore skin = color.score(SyntheticFaces.ctx(SyntheticFaces.skinTexture(2)));
        ColorStatsSignal.Features fs = color.lastFeatures();
        SignalScore grey = color.score(SyntheticFaces.ctx(SyntheticFaces.flat(170, 168, 166)));
        ColorStatsSignal.Features fg = color.lastFeatures();
        assertThat(fs.sMean).isGreaterThan(fg.sMean);
        assertThat(skin.attackProbability()).isLessThan(0.5);
        assertThat(grey.attackProbability()).isGreaterThan(0.5);
        assertThat(grey.attackClass()).isEqualTo(Signals.ATTACK_PRINT);
    }

    @Test
    void blueClippedScreenIsClassedAsScreen() {
        SignalScore s = color.score(SyntheticFaces.ctx(SyntheticFaces.flat(120, 160, 255)));
        assertThat(color.lastFeatures().blueShift).isGreaterThan(0.3);
        assertThat(s.attackProbability()).isGreaterThan(0.5);
        assertThat(s.attackClass()).isEqualTo(Signals.ATTACK_SCREEN);
    }

    @Test
    void calibrationShiftsProbability() {
        ColorStatsSignal strict = new ColorStatsSignal(new PlattCalibration(1, 3, 0.5));
        double base = color.score(SyntheticFaces.ctx(SyntheticFaces.skinTexture(3))).attackProbability();
        double shifted = strict.score(SyntheticFaces.ctx(SyntheticFaces.skinTexture(3))).attackProbability();
        assertThat(shifted).isGreaterThan(base);
    }
}
