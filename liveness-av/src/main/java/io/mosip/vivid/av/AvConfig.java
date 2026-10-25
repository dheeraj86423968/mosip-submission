package io.mosip.vivid.av;

import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.av.sync.SyncConfig;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Configuration of the spoken-code round. */
public final class AvConfig {
    private final String languageTag;
    private final Set<ChallengeType> carriers;
    private final int codeLength;
    private final long responseWindowMillis;
    private final long endSilenceMillis;
    private final long minSpeechMillis;
    private final SyncConfig sync;
    private final double passAttackProbability;
    private final boolean failClosedWithoutMicrophone;

    private AvConfig(Builder b) {
        languageTag = b.languageTag; carriers = Collections.unmodifiableSet(EnumSet.copyOf(b.carriers)); codeLength = b.codeLength;
        responseWindowMillis = b.responseWindowMillis; endSilenceMillis = b.endSilenceMillis; minSpeechMillis = b.minSpeechMillis;
        sync = b.sync; passAttackProbability = b.passAttackProbability; failClosedWithoutMicrophone = b.failClosedWithoutMicrophone;
    }

    public static AvConfig defaults() { return builder().build(); }
    public static Builder builder() { return new Builder(); }

    /** Language the resident reads the digits in; selects the recogniser. */
    public String languageTag() { return languageTag; }
    /**
     * Existing challenge types that carry the spoken code (the frozen {@code ChallengeType} has no SPEAK_CODE).
     * Default {@code HOLD_STILL}: speaking does not move the face centre or pose, it requires no motor ability, and
     * it is in the default accessibility pool.
     */
    public Set<ChallengeType> carriers() { return carriers; }
    public int codeLength() { return codeLength; }
    /** Prompt to forced decision. The carrier's hold time must exceed this; see {@link AvSetup}. */
    public long responseWindowMillis() { return responseWindowMillis; }
    /** Trailing silence that ends the utterance early. */
    public long endSilenceMillis() { return endSilenceMillis; }
    /** Minimum voiced audio before an early end is allowed. */
    public long minSpeechMillis() { return minSpeechMillis; }
    public SyncConfig sync() { return sync; }
    /** Attack probability reported (confidence 1) after a pass. */
    public double passAttackProbability() { return passAttackProbability; }
    /** When the microphone cannot be opened, veto (true) rather than let the carrier pass without audio. */
    public boolean failClosedWithoutMicrophone() { return failClosedWithoutMicrophone; }

    public static final class Builder {
        private String languageTag = "en";
        private Set<ChallengeType> carriers = EnumSet.of(ChallengeType.HOLD_STILL);
        private int codeLength = 4;
        private long responseWindowMillis = 6000, endSilenceMillis = 700, minSpeechMillis = 500;
        private SyncConfig sync = SyncConfig.defaults();
        private double passAttackProbability = 0.05;
        private boolean failClosedWithoutMicrophone = true;

        public Builder languageTag(String v) { languageTag = Objects.requireNonNull(v); return this; }
        public Builder carriers(Set<ChallengeType> v) {
            if (v.isEmpty()) throw new IllegalArgumentException("at least one carrier challenge");
            for (ChallengeType t : v) if (t.requiresMotorAbility()) throw new IllegalArgumentException(t + " moves the head; speaking needs a still face");
            carriers = EnumSet.copyOf(v); return this;
        }
        public Builder codeLength(int v) { codeLength = v; return this; }
        public Builder responseWindowMillis(long v) { responseWindowMillis = v; return this; }
        public Builder endSilenceMillis(long v) { endSilenceMillis = v; return this; }
        public Builder minSpeechMillis(long v) { minSpeechMillis = v; return this; }
        public Builder sync(SyncConfig v) { sync = Objects.requireNonNull(v); return this; }
        public Builder passAttackProbability(double v) { passAttackProbability = v; return this; }
        public Builder failClosedWithoutMicrophone(boolean v) { failClosedWithoutMicrophone = v; return this; }
        public AvConfig build() {
            if (responseWindowMillis < 2000) throw new IllegalArgumentException("responseWindowMillis must be >= 2000");
            return new AvConfig(this);
        }
    }
}
