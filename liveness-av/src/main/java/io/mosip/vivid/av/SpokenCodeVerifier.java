package io.mosip.vivid.av;

import io.mosip.vivid.av.asr.DigitRecognizer;
import io.mosip.vivid.av.asr.Recognition;
import io.mosip.vivid.av.audio.AudioClip;
import io.mosip.vivid.av.sync.LipSyncScorer;
import io.mosip.vivid.av.sync.MouthTrack;
import io.mosip.vivid.av.sync.SpeechActivity;
import io.mosip.vivid.av.sync.SyncResult;

import java.util.Objects;

/**
 * Combines the two channels. Liveness passes only if (a) the recognised digits equal the code exactly and
 * (b) the mouth track is synchronised with the speech envelope ({@link LipSyncScorer}). Usable on its own, without
 * the orchestrator, for evaluation and for clients that run the spoken code as a separate step.
 */
public final class SpokenCodeVerifier {
    private final DigitRecognizer recognizer;
    private final LipSyncScorer scorer;

    public SpokenCodeVerifier(DigitRecognizer recognizer, LipSyncScorer scorer) {
        this.recognizer = Objects.requireNonNull(recognizer, "recognizer");
        this.scorer = Objects.requireNonNull(scorer, "scorer");
    }

    /**
     * @param code        the code that was shown
     * @param audio       audio captured from the prompt onwards ({@code startNanos} = capture start)
     * @param mouth       mouth-opening samples on the same clock
     * @param promptNanos session-clock time the code was shown
     * @param nowNanos    session-clock time of the decision
     */
    public AvVerdict verify(SpokenCode code, AudioClip audio, MouthTrack mouth, long promptNanos, long nowNanos) {
        SpeechActivity act = SpeechActivity.of(audio);
        return verify(code, audio, act, mouth, promptNanos, nowNanos);
    }

    AvVerdict verify(SpokenCode code, AudioClip audio, SpeechActivity act, MouthTrack mouth, long promptNanos, long nowNanos) {
        long decided = (nowNanos - promptNanos) / 1_000_000L;
        if (!act.hasSpeech()) {
            return new AvVerdict(AvVerdict.Outcome.NO_SPEECH, code, "", null, -1, decided);
        }
        long onset = (act.frameNanos(act.firstVoiced()) - promptNanos) / 1_000_000L;
        Recognition rec;
        try {
            rec = recognizer.recognize(audio);
        } catch (RuntimeException e) {
            return new AvVerdict(AvVerdict.Outcome.ENGINE_ERROR, code, "", null, onset, decided);
        }
        SyncResult sync = scorer.score(act, mouth);
        AvVerdict.Outcome outcome;
        if (!code.digits().equals(rec.digits())) {
            outcome = AvVerdict.Outcome.CODE_MISMATCH;
        } else {
            switch (sync.status()) {
                case SYNCED: outcome = AvVerdict.Outcome.PASS; break;
                case STATIC_MOUTH: outcome = AvVerdict.Outcome.STATIC_MOUTH; break;
                case INSUFFICIENT_VIDEO: outcome = AvVerdict.Outcome.INSUFFICIENT_VIDEO; break;
                case NO_SPEECH: outcome = AvVerdict.Outcome.NO_SPEECH; break;
                default: outcome = AvVerdict.Outcome.OUT_OF_SYNC; break;
            }
        }
        return new AvVerdict(outcome, code, rec.digits(), sync, onset, decided);
    }
}
