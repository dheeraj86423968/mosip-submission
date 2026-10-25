package io.mosip.vivid.av.asr;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Result of digit recognition: the digit string plus per-token timing. Holds no audio. */
public final class Recognition {
    private final String digits;
    private final List<Token> tokens;
    private final int unknownTokens;

    public Recognition(String digits, List<Token> tokens, int unknownTokens) {
        this.digits = Objects.requireNonNull(digits, "digits");
        this.tokens = Collections.unmodifiableList(List.copyOf(tokens));
        this.unknownTokens = unknownTokens;
    }

    public static Recognition empty() { return new Recognition("", List.of(), 0); }

    /** Recognised digits, e.g. {@code "4817"}. */
    public String digits() { return digits; }
    public List<Token> tokens() { return tokens; }
    /** Tokens the grammar could not map to a digit ({@code [unk]}). */
    public int unknownTokens() { return unknownTokens; }

    @Override public String toString() { return "Recognition{" + digits + ", unk=" + unknownTokens + "}"; }

    /** One recognised word: its text, the digit it denotes (-1 for none), times relative to clip start, confidence. */
    public static final class Token {
        public final String word;
        public final int digit;
        public final double startSec;
        public final double endSec;
        public final double confidence;
        public Token(String word, int digit, double startSec, double endSec, double confidence) {
            this.word = word; this.digit = digit; this.startSec = startSec; this.endSec = endSec; this.confidence = confidence;
        }
    }
}
