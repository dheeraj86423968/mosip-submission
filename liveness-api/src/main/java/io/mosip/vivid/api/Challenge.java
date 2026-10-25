package io.mosip.vivid.api;

import java.util.Objects;

/** One issued active challenge. Index is 0-based position in the session's sequence. */
public final class Challenge {
    private final ChallengeType type;
    private final int index;
    private final int total;
    private final long issuedAtNanos;
    private final long timeoutMillis;

    public Challenge(ChallengeType type, int index, int total, long issuedAtNanos, long timeoutMillis) {
        this.type = Objects.requireNonNull(type); this.index = index; this.total = total;
        this.issuedAtNanos = issuedAtNanos; this.timeoutMillis = timeoutMillis;
    }
    public ChallengeType type() { return type; }
    public int index() { return index; }
    public int total() { return total; }
    public long issuedAtNanos() { return issuedAtNanos; }
    public long timeoutMillis() { return timeoutMillis; }
    @Override public String toString() { return type + "[" + (index + 1) + "/" + total + "]"; }
}
