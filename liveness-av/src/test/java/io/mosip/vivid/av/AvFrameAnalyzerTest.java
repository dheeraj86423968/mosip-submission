package io.mosip.vivid.av;

import io.mosip.vivid.api.Challenge;
import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.FrameAnalysis;
import io.mosip.vivid.api.FrameAnalyzer;
import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.api.Signals;
import io.mosip.vivid.av.audio.AudioSource;
import io.mosip.vivid.av.testing.Audio;
import io.mosip.vivid.av.testing.FakeClock;
import io.mosip.vivid.av.testing.FakeRecognizer;
import io.mosip.vivid.av.testing.LipTracks;
import io.mosip.vivid.av.testing.ScriptedAudioSource;
import io.mosip.vivid.av.testing.ScriptedEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AvFrameAnalyzerTest {
    private FakeClock clock;
    private ScriptedEngine inner;
    private List<ScriptedAudioSource> mics;
    private AtomicReference<String> heard;
    private List<SpokenCodePrompt> prompts;
    private List<AvVerdict> verdicts;
    private AvLivenessEngine engine;
    private final Random rnd = new Random(11);

    @BeforeEach
    void setUp() {
        clock = new FakeClock();
        inner = new ScriptedEngine(clock);
        mics = new ArrayList<>();
        heard = new AtomicReference<>("");
        prompts = new ArrayList<>();
        verdicts = new ArrayList<>();
        engine = new AvLivenessEngine(inner, AvConfig.defaults(), new FakeRecognizer(c -> heard.get()), () -> {
            ScriptedAudioSource s = new ScriptedAudioSource(clock);
            mics.add(s);
            return s;
        }, clock, new SecureRandom());
        engine.addListener(new SpokenCodeListener() {
            @Override public void onPrompt(SpokenCodePrompt p) { prompts.add(p); }
            @Override public void onVerdict(AvVerdict v) { verdicts.add(v); }
        });
    }

    private Optional<SignalScore> av(FrameAnalysis a) {
        return a.signals().stream().filter(s -> s.signalId().equals(AvSignals.AV_SYNC)).findFirst();
    }

    private Optional<SignalScore> runFrames(FrameAnalyzer an, int ms) {
        Optional<SignalScore> last = Optional.empty();
        for (int t = 0; t < ms; t += 40) {
            clock.advanceMillis(40);
            last = av(an.analyze(ScriptedEngine.frame(t, clock.nanoTime())));
        }
        return last;
    }

    /** The subject reads whatever code was shown, with lips following the speech (or not). */
    private void respond(boolean lipsMove, boolean sayShownCode) {
        SpokenCodePrompt p = prompts.get(prompts.size() - 1);
        String said = sayShownCode ? p.code().digits() : "0000".equals(p.code().digits()) ? "1111" : "0000";
        short[] pcm = Audio.withNoise(Audio.pad(Audio.syntheticSpeech(said, rnd), 500, 1500), 0.002, rnd);
        mics.get(mics.size() - 1).play(pcm);
        heard.set(said);
        inner.mouth = lipsMove ? LipTracks.articulated(pcm, p.issuedNanos(), 60, rnd) : LipTracks.still(0.004, rnd);
    }

    private Challenge holdStill() { return new Challenge(ChallengeType.HOLD_STILL, 0, 1, clock.nanoTime(), 8000); }

    @Test
    void addsNoSignalOutsideACarrierChallenge() {
        FrameAnalyzer an = engine.newAnalyzer();
        assertThat(runFrames(an, 500)).isEmpty();
        an.onChallenge(new Challenge(ChallengeType.BLINK, 0, 1, clock.nanoTime(), 8000));
        assertThat(runFrames(an, 500)).isEmpty();
        assertThat(prompts).isEmpty();
        assertThat(engine.info().signalIds()).contains(AvSignals.AV_SYNC);
        assertThat(engine.info().engineId()).isEqualTo("scripted+av");
    }

    @Test
    void liveSpeakerPassesWithFullConfidenceAndAudioIsWiped() {
        FrameAnalyzer an = engine.newAnalyzer();
        an.onChallenge(holdStill());
        assertThat(prompts).hasSize(1);
        assertThat(prompts.get(0).code().digits()).matches("[0-9]{4}");
        respond(true, true);
        clock.advanceMillis(40);
        SignalScore pending = av(an.analyze(ScriptedEngine.frame(0, clock.nanoTime()))).orElseThrow();
        assertThat(pending.confidence()).isZero();
        SignalScore s = runFrames(an, 5000).orElseThrow();
        assertThat(verdicts).hasSize(1);
        assertThat(verdicts.get(0).outcome()).as(verdicts.get(0).toString()).isEqualTo(AvVerdict.Outcome.PASS);
        assertThat(s.hardVeto()).isFalse();
        assertThat(s.confidence()).isEqualTo(1.0);
        assertThat(s.attackProbability()).isLessThan(0.1);
        // decided at end of speech plus trailing silence, before the 6 s window
        assertThat(verdicts.get(0).decidedAfterMillis()).isLessThan(6000);
        assertThat(mics.get(0).wiped).isTrue();
    }

    @Test
    void photoWithLoudspeakerIsVetoed() {
        FrameAnalyzer an = engine.newAnalyzer();
        an.onChallenge(holdStill());
        respond(false, true);
        SignalScore s = runFrames(an, 6500).orElseThrow();
        assertThat(verdicts.get(0).outcome()).isEqualTo(AvVerdict.Outcome.STATIC_MOUTH);
        assertThat(s.hardVeto()).isTrue();
        assertThat(s.attackClass()).isEqualTo(Signals.ATTACK_PRINT);
    }

    @Test
    void wrongCodeIsVetoedAsReplay() {
        FrameAnalyzer an = engine.newAnalyzer();
        an.onChallenge(holdStill());
        respond(true, false);
        SignalScore s = runFrames(an, 6500).orElseThrow();
        assertThat(verdicts.get(0).outcome()).isEqualTo(AvVerdict.Outcome.CODE_MISMATCH);
        assertThat(s.attackClass()).isEqualTo(Signals.ATTACK_VIDEO);
    }

    @Test
    void silenceIsDecidedAtTheEndOfTheResponseWindow() {
        FrameAnalyzer an = engine.newAnalyzer();
        an.onChallenge(holdStill());
        assertThat(runFrames(an, 5800).orElseThrow().confidence()).isZero();
        SignalScore s = runFrames(an, 400).orElseThrow();
        assertThat(s.hardVeto()).isTrue();
        assertThat(verdicts.get(0).outcome()).isEqualTo(AvVerdict.Outcome.NO_SPEECH);
        assertThat(verdicts.get(0).decidedAfterMillis()).isBetween(6000L, 6100L);
    }

    @Test
    void carrierEndingEarlyForcesADecision() {
        FrameAnalyzer an = engine.newAnalyzer();
        an.onChallenge(holdStill());
        respond(true, true);
        runFrames(an, 800);
        an.onChallenge(new Challenge(ChallengeType.BLINK, 1, 2, clock.nanoTime(), 8000));
        assertThat(verdicts).hasSize(1);
        assertThat(verdicts.get(0).passed()).isFalse();
        assertThat(runFrames(an, 80).orElseThrow().hardVeto()).isTrue();
    }

    @Test
    void retryDrawsAFreshCode() {
        FrameAnalyzer an = engine.newAnalyzer();
        an.onChallenge(holdStill());
        respond(false, true);
        runFrames(an, 6500);
        an.reset();
        assertThat(runFrames(an, 200)).isEmpty();
        an.onChallenge(holdStill());
        assertThat(prompts).hasSize(2);
        assertThat(mics).hasSize(2);
        // a second carrier in the same attempt does not start another round
        respond(true, true);
        runFrames(an, 5000);
        an.onChallenge(holdStill());
        assertThat(prompts).hasSize(2);
    }

    @Test
    void missingMicrophoneFailsClosed() {
        AvLivenessEngine noMic = new AvLivenessEngine(inner, AvConfig.defaults(), new FakeRecognizer(c -> ""), () -> {
            throw new AudioSource.AudioUnavailableException("no microphone", null);
        }, clock, new SecureRandom());
        FrameAnalyzer an = noMic.newAnalyzer();
        an.onChallenge(holdStill());
        SignalScore s = runFrames(an, 80).orElseThrow();
        assertThat(s.hardVeto()).isTrue();

        AvLivenessEngine lenient = new AvLivenessEngine(inner, AvConfig.builder().failClosedWithoutMicrophone(false).build(),
                new FakeRecognizer(c -> ""), () -> { throw new AudioSource.AudioUnavailableException("no microphone", null); },
                clock, new SecureRandom());
        FrameAnalyzer an2 = lenient.newAnalyzer();
        an2.onChallenge(holdStill());
        assertThat(runFrames(an2, 80)).isEmpty();
    }
}
