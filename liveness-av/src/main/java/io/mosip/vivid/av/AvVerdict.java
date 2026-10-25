package io.mosip.vivid.av;

import io.mosip.vivid.api.Signals;
import io.mosip.vivid.av.sync.SyncResult;

import java.util.Locale;
import java.util.Objects;

/**
 * Decision of one spoken-code round. Holds the code, what was recognised and the synchrony statistics, never audio.
 * Both channels are always evaluated, so a failure reports whether the other channel would have passed.
 */
public final class AvVerdict {
    public enum Outcome {
        PASS(null),
        /** No voiced audio within the response window. */
        NO_SPEECH(Signals.ATTACK_UNKNOWN),
        /** Recognised digits differ from the code: replayed or pre-recorded answer, or a misreading. */
        CODE_MISMATCH(Signals.ATTACK_VIDEO),
        /** The mouth did not move while speech was heard: photo plus loudspeaker, or someone else speaking. */
        STATIC_MOUTH(Signals.ATTACK_PRINT),
        /** The mouth moved but not with the speech: dubbed or synthesised video. */
        OUT_OF_SYNC(Signals.ATTACK_DEEPFAKE),
        /** Too few face frames during the utterance to judge synchrony. */
        INSUFFICIENT_VIDEO(Signals.ATTACK_UNKNOWN),
        /** No microphone, with {@link AvConfig#failClosedWithoutMicrophone()} set. */
        AUDIO_UNAVAILABLE(Signals.ATTACK_UNKNOWN),
        /** The recogniser failed. */
        ENGINE_ERROR(Signals.ATTACK_UNKNOWN);

        private final String attackClass;
        Outcome(String attackClass) { this.attackClass = attackClass; }
        public String attackClass() { return attackClass; }
    }

    private final Outcome outcome;
    private final SpokenCode code;
    private final String recognized;
    private final SyncResult sync;
    private final long speechOnsetMillis;
    private final long decidedAfterMillis;

    public AvVerdict(Outcome outcome, SpokenCode code, String recognized, SyncResult sync, long speechOnsetMillis, long decidedAfterMillis) {
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.code = code;
        this.recognized = recognized == null ? "" : recognized;
        this.sync = sync;
        this.speechOnsetMillis = speechOnsetMillis;
        this.decidedAfterMillis = decidedAfterMillis;
    }

    public Outcome outcome() { return outcome; }
    public boolean passed() { return outcome == Outcome.PASS; }
    public SpokenCode code() { return code; }
    /** Digits the recogniser heard ("" when none). */
    public String recognized() { return recognized; }
    public boolean codeMatched() { return code != null && code.digits().equals(recognized); }
    /** Synchrony statistics; null when no speech or no audio. */
    public SyncResult sync() { return sync; }
    public boolean synced() { return sync != null && sync.synced(); }
    /** Prompt to first voiced audio, ms; -1 without speech. */
    public long speechOnsetMillis() { return speechOnsetMillis; }
    /** Prompt to verdict, ms. */
    public long decidedAfterMillis() { return decidedAfterMillis; }
    public String attackClass() { return outcome.attackClass(); }

    @Override public String toString() {
        return String.format(Locale.ROOT, "AvVerdict{%s code=%s heard=%s onset=%dms sync=%s}", outcome,
                code == null ? "-" : code.digits(), recognized, speechOnsetMillis, sync);
    }
}
