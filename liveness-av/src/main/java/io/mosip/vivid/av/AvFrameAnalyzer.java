package io.mosip.vivid.av;

import io.mosip.vivid.api.Challenge;
import io.mosip.vivid.api.FrameAnalysis;
import io.mosip.vivid.api.FrameAnalyzer;
import io.mosip.vivid.api.SignalScore;
import io.mosip.vivid.av.audio.AudioClip;
import io.mosip.vivid.av.audio.AudioSource;
import io.mosip.vivid.av.sync.MouthTrack;
import io.mosip.vivid.av.sync.SpeechActivity;
import io.mosip.vivid.core.session.TimeSource;
import io.mosip.vivid.device.FaceFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Per-session analyzer of {@link AvLivenessEngine}: runs at most one spoken-code round per attempt. Driven on the
 * orchestrator's feeding thread; not thread-safe.
 */
final class AvFrameAnalyzer implements FrameAnalyzer {
    private static final Logger LOG = LoggerFactory.getLogger(AvFrameAnalyzer.class);

    private final FrameAnalyzer inner;
    private final AvConfig config;
    private final SpokenCodeVerifier verifier;
    private final Supplier<AudioSource> audioFactory;
    private final TimeSource time;
    private final SecureRandom random;
    private final List<SpokenCodeListener> listeners;

    private Round round;
    private AvVerdict verdict;

    AvFrameAnalyzer(FrameAnalyzer inner, AvConfig config, SpokenCodeVerifier verifier, Supplier<AudioSource> audioFactory,
                    TimeSource time, SecureRandom random, List<SpokenCodeListener> listeners) {
        this.inner = inner; this.config = config; this.verifier = verifier; this.audioFactory = audioFactory;
        this.time = time; this.random = random; this.listeners = listeners;
    }

    private static final class Round {
        final SpokenCode code;
        final long issuedNanos;
        final AudioSource audio;
        final MouthTrack mouth = new MouthTrack();
        Round(SpokenCode code, long issuedNanos, AudioSource audio) { this.code = code; this.issuedNanos = issuedNanos; this.audio = audio; }
    }

    @Override
    public FrameAnalysis analyze(FaceFrame frame) {
        FrameAnalysis a = inner.analyze(frame);
        if (a == null) return null;
        long now = time.nanoTime();
        if (round != null) {
            Round r = round;
            a.face().ifPresent(f -> r.mouth.add(now, f.mouthAspect()));
            poll(now);
        }
        SignalScore s = signal();
        if (s == null) return a;
        List<SignalScore> signals = new ArrayList<>(a.signals());
        signals.add(s);
        return new FrameAnalysis(a.frameSequence(), a.frameTimestampNanos(), a.quality(), a.face().orElse(null), signals,
                a.embedding().orElse(null), a.processingNanos());
    }

    @Override
    public void onChallenge(Challenge challenge) {
        inner.onChallenge(challenge);
        long now = time.nanoTime();
        if (round != null) {
            // The carrier ended before the utterance did: decide now on what was captured (fail-closed).
            finish(now);
        }
        if (verdict == null && config.carriers().contains(challenge.type())) {
            begin(now);
        }
    }

    private void begin(long now) {
        SpokenCode code = SpokenCode.random(random, config.codeLength());
        AudioSource src;
        try {
            src = audioFactory.get();
            src.start(now);
        } catch (RuntimeException e) {
            LOG.warn("spoken-code round: microphone unavailable: {}", e.toString());
            if (config.failClosedWithoutMicrophone()) {
                decide(new AvVerdict(AvVerdict.Outcome.AUDIO_UNAVAILABLE, code, "", null, -1, 0));
            }
            return;
        }
        round = new Round(code, now, src);
        SpokenCodePrompt prompt = new SpokenCodePrompt(code, config.languageTag(), now, config.responseWindowMillis());
        for (SpokenCodeListener l : listeners) {
            try { l.onPrompt(prompt); } catch (RuntimeException e) { LOG.warn("prompt listener failed: {}", e.toString()); }
        }
    }

    private void poll(long now) {
        long elapsedMs = (now - round.issuedNanos) / 1_000_000L;
        if (elapsedMs >= config.responseWindowMillis()) {
            finish(now);
            return;
        }
        AudioClip clip = round.audio.snapshot();
        SpeechActivity act = SpeechActivity.of(clip);
        boolean ended = act.hasSpeech() && act.speechMillis() >= config.minSpeechMillis()
                && act.trailingSilenceMillis() >= config.endSilenceMillis();
        clip.wipe();
        if (ended) finish(now);
    }

    private void finish(long now) {
        Round r = round;
        round = null;
        r.audio.stop();
        AudioClip clip = r.audio.snapshot();
        try {
            decide(verifier.verify(r.code, clip, r.mouth, r.issuedNanos, now));
        } finally {
            clip.wipe();
            r.audio.close();
            r.mouth.clear();
        }
    }

    private void decide(AvVerdict v) {
        verdict = v;
        LOG.info("spoken-code verdict: {} (heard {} of {}, sync {})", v.outcome(), v.recognized().isEmpty() ? "-" : "digits",
                v.code() == null ? 0 : v.code().length(), v.sync() == null ? "-" : v.sync().status());
        for (SpokenCodeListener l : listeners) {
            try { l.onVerdict(v); } catch (RuntimeException e) { LOG.warn("verdict listener failed: {}", e.toString()); }
        }
    }

    private SignalScore signal() {
        if (verdict != null) {
            return verdict.passed() ? SignalScore.of(AvSignals.AV_SYNC, config.passAttackProbability(), 1.0)
                    : SignalScore.veto(AvSignals.AV_SYNC, verdict.attackClass());
        }
        return round != null ? SignalScore.of(AvSignals.AV_SYNC, 0.5, 0.0) : null;
    }

    /** Verdict of the current attempt, if decided. */
    AvVerdict verdict() { return verdict; }

    @Override
    public void reset() {
        abandon();
        verdict = null;
        inner.reset();
    }

    private void abandon() {
        if (round != null) {
            round.audio.close();
            round.mouth.clear();
            round = null;
        }
    }

    @Override
    public void close() {
        abandon();
        inner.close();
    }
}
