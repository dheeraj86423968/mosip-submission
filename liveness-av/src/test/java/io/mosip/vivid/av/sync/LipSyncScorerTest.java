package io.mosip.vivid.av.sync;

import io.mosip.vivid.av.testing.Audio;
import io.mosip.vivid.av.testing.LipTracks;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.function.LongToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;

/** Sync scorer on the pure-Java speech-like signal (no TTS needed). */
class LipSyncScorerTest {
    private static final long T0 = 5_000_000_000L;
    private final LipSyncScorer scorer = new LipSyncScorer();

    private static short[] utterance(Random r) {
        return Audio.withNoise(Audio.pad(Audio.syntheticSpeech(String.format("%04d", r.nextInt(10_000)), r), 600, 1200), 0.002, r);
    }

    private SyncResult score(short[] audio, LongToDoubleFunction mouth, double fps, Random r) {
        long end = T0 + audio.length * 1_000_000_000L / Audio.RATE;
        return scorer.score(Audio.clip(audio, T0), LipTracks.sample(mouth, T0, end, fps, r));
    }

    @RepeatedTest(10)
    void lipsThatFollowTheSpeechAreSynchronised() {
        Random r = new Random(System.nanoTime());
        short[] a = utterance(r);
        SyncResult s = score(a, LipTracks.articulated(a, T0, 80, r), 25, r);
        assertThat(s.status()).as(s.toString()).isEqualTo(SyncResult.Status.SYNCED);
    }

    @Test
    void recoversTheLipLead() {
        Random r = new Random(3);
        short[] a = utterance(r);
        SyncResult s = score(a, LipTracks.fromAudio(a, T0, 100, 0.005, r), 30, r);
        assertThat(s.synced()).isTrue();
        assertThat(s.lagMillis()).as("mouth leads by 100 ms, so the best lag is about -100").isBetween(-140, -60);
    }

    @Test
    void stillFaceIsStaticMouth() {
        Random r = new Random(4);
        short[] a = utterance(r);
        assertThat(score(a, LipTracks.still(0.004, r), 25, r).status()).isEqualTo(SyncResult.Status.STATIC_MOUTH);
    }

    @RepeatedTest(10)
    void lipsShiftedBeyondTheLagWindowAreOutOfSync() {
        Random r = new Random(System.nanoTime());
        short[] a = utterance(r);
        int shift = (450 + r.nextInt(350)) * (r.nextBoolean() ? 1 : -1);
        SyncResult s = score(a, LipTracks.articulated(a, T0, shift, r), 25, r);
        assertThat(s.synced()).as(s + " shift " + shift).isFalse();
    }

    @Test
    void sparseVideoIsInsufficient() {
        Random r = new Random(5);
        short[] a = utterance(r);
        // 2 fps leaves 500 ms gaps, over the 250 ms limit
        assertThat(score(a, LipTracks.articulated(a, T0, 60, r), 2, r).status()).isEqualTo(SyncResult.Status.INSUFFICIENT_VIDEO);
    }

    @Test
    void noSpeechIsReported() {
        Random r = new Random(6);
        short[] a = Audio.withNoise(Audio.silence(4000), 0.003, r);
        assertThat(score(a, LipTracks.articulated(a, T0, 60, r), 25, r).status()).isEqualTo(SyncResult.Status.NO_SPEECH);
    }

    @Test
    void mouthTrackInterpolatesAndRespectsGaps() {
        MouthTrack m = new MouthTrack();
        m.add(0, 0.0);
        m.add(100_000_000L, 1.0);
        m.add(50_000_000L, 9.0); // out of order: dropped
        m.add(1_000_000_000L, 0.0);
        assertThat(m.size()).isEqualTo(3);
        assertThat(m.at(50_000_000L, 200_000_000L)).isEqualTo(0.5);
        assertThat(m.at(500_000_000L, 200_000_000L)).isNaN();
        assertThat(m.at(2_000_000_000L, 200_000_000L)).isNaN();
    }
}
