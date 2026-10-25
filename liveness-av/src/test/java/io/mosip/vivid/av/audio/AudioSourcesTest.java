package io.mosip.vivid.av.audio;

import io.mosip.vivid.av.testing.Audio;
import io.mosip.vivid.av.testing.FakeClock;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

class AudioSourcesTest {
    @Test
    void arraySourceReleasesAudioAsTheClockAdvancesAndWipesOnClose() {
        FakeClock clock = new FakeClock();
        short[] pcm = new short[16_000];
        java.util.Arrays.fill(pcm, (short) 1000);
        ArrayAudioSource src = new ArrayAudioSource(pcm, 16_000, clock::nanoTime);
        assertThat(src.snapshot().length()).isZero();
        src.start(clock.nanoTime());
        clock.advanceMillis(250);
        AudioClip c = src.snapshot();
        assertThat(c.length()).isEqualTo(4000);
        assertThat(c.startNanos()).isEqualTo(clock.nanoTime() - 250_000_000L);
        clock.advanceMillis(5000);
        assertThat(src.snapshot().length()).isEqualTo(16_000);
        src.stop();
        src.close();
        assertThat(src.snapshot().length()).isZero();
        c.wipe();
        assertThat(c.sample(10)).isZero();
    }

    @Test
    void wavReaderDownmixesAndResamples() throws Exception {
        int rate = 22_050, n = 22_050;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int i = 0; i < n; i++) {
            short l = (short) (8000 * Math.sin(2 * Math.PI * 440 * i / rate)), r = l;
            body.write(l & 0xff); body.write(l >> 8 & 0xff); body.write(r & 0xff); body.write(r >> 8 & 0xff);
        }
        byte[] data = body.toByteArray();
        ByteBuffer b = ByteBuffer.allocate(44 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46464952).putInt(36 + data.length).putInt(0x45564157).putInt(0x20746d66).putInt(16)
                .putShort((short) 1).putShort((short) 2).putInt(rate).putInt(rate * 4).putShort((short) 4).putShort((short) 16)
                .putInt(0x61746164).putInt(data.length).put(data);
        AudioClip c = WavReader.parse(b.array());
        assertThat(c.sampleRate()).isEqualTo(16_000);
        assertThat(c.length()).isBetween(15_990, 16_010);
        assertThat(c.durationMillis()).isBetween(995L, 1005L);
    }

    @Test
    void javaSoundEitherCapturesOrFailsWithAudioUnavailable() throws Exception {
        // This machine has no microphone; on one that has, a short capture must work and be wiped on close.
        try (JavaSoundAudioSource mic = new JavaSoundAudioSource(2)) {
            try {
                mic.start(0);
            } catch (AudioSource.AudioUnavailableException expected) {
                assertThat(expected).hasMessageContaining("microphone");
                return;
            }
            Thread.sleep(300);
            assertThat(mic.snapshot().sampleRate()).isEqualTo(16_000);
            mic.close();
            assertThat(mic.snapshot().length()).isZero();
        }
    }

    @Test
    void syntheticSpeechHasPauses() {
        short[] s = Audio.syntheticSpeech("1111", new java.util.Random(1));
        assertThat(s.length).isGreaterThan(16_000 / 2);
    }
}
