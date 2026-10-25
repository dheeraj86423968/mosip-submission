package io.mosip.vivid.av.testing;

import java.util.List;
import java.util.Random;
import java.util.function.LongToDoubleFunction;

/**
 * Builds one presentation (audio heard from the prompt onwards + the face's mouth opening over time) for a
 * bona fide speaker or an attack. All speech is synthetic (TTS or {@link Audio#syntheticSpeech}); all mouth tracks
 * are synthetic ({@link LipTracks}).
 */
public final class Scenarios {
    public enum Kind {
        /** Bona fide: the person reads the shown code; lips follow their own speech. */
        LIVE(false),
        /** Photo (or still mask) in front of the camera, correct code played from a loudspeaker. */
        STATIC_FACE(true),
        /** Replayed old video of the person reading a different code: audio and lips agree, the code is wrong. */
        REPLAY_WRONG_CODE(true),
        /** Correct code, lips from the same utterance but shifted 400-800 ms (dubbing / lagging deepfake). */
        DUBBED_SHIFTED(true),
        /** Correct code, lips from another utterance of the same voice (video of the person saying other digits). */
        DUBBED_OTHER_UTTERANCE(true),
        /** Nothing is said: background noise only, face still. */
        SILENCE(true),
        /** An accomplice off camera reads the code while the person on camera talks (other digits) at the same time. */
        TWO_SPEAKERS(true),
        /** Robustness, not an attack: the person reads the code while someone nearby says other digits more quietly. */
        BACKGROUND_TALKER(false);

        public final boolean attack;
        Kind(boolean attack) { this.attack = attack; }
    }

    public static final class Presentation {
        public final Kind kind;
        public final String shownCode;
        public final short[] audio;            // starts at the prompt
        public final LongToDoubleFunction mouth;
        Presentation(Kind kind, String shownCode, short[] audio, LongToDoubleFunction mouth) {
            this.kind = kind; this.shownCode = shownCode; this.audio = audio; this.mouth = mouth;
        }
    }

    private Scenarios() {}

    /**
     * @param shownCode  the code on screen
     * @param spoken     clean speech of shownCode by the target voice
     * @param otherSame  clean speech of a different code by the same voice
     * @param otherVoice clean speech of a different code by a different voice
     * @param promptNanos session time of the prompt (audio sample 0)
     */
    public static Presentation build(Kind kind, String shownCode, short[] spoken, short[] otherSame, short[] otherVoice,
                                     long promptNanos, Random rnd) {
        int lead = 400 + rnd.nextInt(500);
        int tail = 1500;
        double noise = 0.002;
        int lipLead = rnd.nextInt(150);
        short[] audio;
        LongToDoubleFunction mouth;
        switch (kind) {
            case LIVE: {
                audio = Audio.withNoise(Audio.pad(spoken, lead, tail), noise, rnd);
                mouth = LipTracks.articulated(audio, promptNanos, lipLead, rnd);
                break;
            }
            case STATIC_FACE: {
                audio = Audio.withNoise(Audio.pad(spoken, lead, tail), noise, rnd);
                mouth = LipTracks.still(0.004, rnd);
                break;
            }
            case REPLAY_WRONG_CODE: {
                audio = Audio.withNoise(Audio.pad(otherSame, lead, tail), noise, rnd);
                mouth = LipTracks.articulated(audio, promptNanos, lipLead, rnd);
                break;
            }
            case DUBBED_SHIFTED: {
                audio = Audio.withNoise(Audio.pad(spoken, lead, tail), noise, rnd);
                int shift = (400 + rnd.nextInt(400)) * (rnd.nextBoolean() ? 1 : -1);
                mouth = LipTracks.articulated(audio, promptNanos, shift, rnd);
                break;
            }
            case DUBBED_OTHER_UTTERANCE: {
                audio = Audio.withNoise(Audio.pad(spoken, lead, tail), noise, rnd);
                short[] lipsSource = Audio.pad(otherSame, lead, tail);
                mouth = LipTracks.articulated(lipsSource, promptNanos, lipLead, rnd);
                break;
            }
            case SILENCE: {
                audio = Audio.withNoise(Audio.silence(5000), 0.004, rnd);
                mouth = LipTracks.still(0.006, rnd);
                break;
            }
            case TWO_SPEAKERS: {
                short[] onCamera = Audio.pad(otherVoice, lead + rnd.nextInt(300), tail);
                audio = Audio.withNoise(Audio.mix(Audio.pad(spoken, lead, tail), onCamera, 0.8, 0), noise, rnd);
                mouth = LipTracks.articulated(onCamera, promptNanos, lipLead, rnd);
                break;
            }
            case BACKGROUND_TALKER: {
                short[] self = Audio.pad(spoken, lead, tail);
                audio = Audio.withNoise(Audio.mix(self, Audio.pad(otherVoice, lead + rnd.nextInt(300), tail), 0.25, 0), noise, rnd);
                mouth = LipTracks.articulated(self, promptNanos, lipLead, rnd);
                break;
            }
            default: throw new IllegalArgumentException(kind.toString());
        }
        return new Presentation(kind, shownCode, audio, mouth);
    }

    /** Picks an element different from {@code not} (by code), or any element when all codes match. */
    public static Corpus.Entry pickOther(List<Corpus.Entry> pool, Corpus.Entry not, Random rnd) {
        for (int i = 0; i < 50; i++) {
            Corpus.Entry e = pool.get(rnd.nextInt(pool.size()));
            if (!e.code.equals(not.code) && !e.file.equals(not.file)) return e;
        }
        return pool.get(rnd.nextInt(pool.size()));
    }
}
