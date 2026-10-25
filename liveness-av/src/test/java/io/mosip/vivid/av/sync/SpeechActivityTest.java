package io.mosip.vivid.av.sync;

import io.mosip.vivid.av.testing.Audio;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class SpeechActivityTest {
    @Test
    void silenceAndNoiseAreNotSpeech() {
        Random r = new Random(1);
        assertThat(SpeechActivity.of(Audio.clip(Audio.silence(3000), 0)).hasSpeech()).isFalse();
        assertThat(SpeechActivity.of(Audio.clip(Audio.withNoise(Audio.silence(3000), 0.01, r), 0)).hasSpeech()).isFalse();
    }

    @Test
    void findsOnsetAndTrailingSilence() {
        Random r = new Random(2);
        short[] speech = Audio.syntheticSpeech("4817", r);
        short[] pcm = Audio.withNoise(Audio.pad(speech, 700, 900), 0.002, r);
        SpeechActivity a = SpeechActivity.of(Audio.clip(pcm, 0));
        assertThat(a.hasSpeech()).isTrue();
        assertThat(a.firstVoiced() * SpeechActivity.FRAME_MS).isBetween(650, 760);
        // the pause after the last digit belongs to the trailing silence
        assertThat(a.trailingSilenceMillis()).isBetween(900L, 1300L);
        // pauses between digits are bridged, so speech is one run from onset to the last syllable
        assertThat(a.speechMillis()).isGreaterThan(speech.length * 1000L / Audio.RATE / 2);
    }
}
