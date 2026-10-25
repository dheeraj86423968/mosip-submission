package io.mosip.vivid.api;

import java.util.Objects;

/** Audit record of one challenge. */
public final class ChallengeOutcome {
    public enum Result { PASSED, FAILED, TIMEOUT, ABORTED }
    private final Challenge challenge;
    private final Result result;
    private final long latencyMillis;   // prompt to detected onset
    private final long durationMillis;  // prompt to completion
    public ChallengeOutcome(Challenge challenge, Result result, long latencyMillis, long durationMillis) {
        this.challenge = Objects.requireNonNull(challenge); this.result = Objects.requireNonNull(result);
        this.latencyMillis = latencyMillis; this.durationMillis = durationMillis;
    }
    public Challenge challenge() { return challenge; }
    public Result result() { return result; }
    public long latencyMillis() { return latencyMillis; }
    public long durationMillis() { return durationMillis; }
}
