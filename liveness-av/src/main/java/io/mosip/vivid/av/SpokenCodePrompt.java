package io.mosip.vivid.av;

/** What the UI must show when a spoken-code round starts. */
public final class SpokenCodePrompt {
    private final SpokenCode code;
    private final String languageTag;
    private final long issuedNanos;
    private final long responseWindowMillis;

    public SpokenCodePrompt(SpokenCode code, String languageTag, long issuedNanos, long responseWindowMillis) {
        this.code = code; this.languageTag = languageTag; this.issuedNanos = issuedNanos; this.responseWindowMillis = responseWindowMillis;
    }

    public SpokenCode code() { return code; }
    /** Language the recogniser expects; the UI should ask for the digits in this language. */
    public String languageTag() { return languageTag; }
    public long issuedNanos() { return issuedNanos; }
    public long responseWindowMillis() { return responseWindowMillis; }
    /**
     * Message key for the prompt, with the spaced digits as argument {0} (Desktop) / {code} (Flutter):
     * {@code liveness.challenge.speak_code} and {@code liveness_challenge_speak_code}.
     */
    public static final String MESSAGE_KEY = "liveness.challenge.speak_code";
}
