package io.mosip.vivid.policy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.mosip.vivid.api.ChallengeType;
import io.mosip.vivid.api.LivenessPolicy;

import java.util.Collections;
import java.util.List;

/**
 * Active-challenge settings of one workflow. Any {@code null} field keeps the compiled-in default.
 *
 * @param mode                  when challenges are issued
 * @param minChallenges         minimum sequence length
 * @param maxChallenges         maximum sequence length (inclusive)
 * @param pool                  challenges the orchestrator may draw from
 * @param accessibilityPool     challenges used when the operator marks assisted capture
 * @param requireDepthRevealing guarantee at least one {@link ChallengeType#revealsDepth()} challenge per sequence
 * @param timeoutMs             time allowed per challenge
 * @param minActionLatencyMs    reaction faster than this is treated as replay / injection
 * @param maxActionLatencyMs    reaction slower than this is suspicious
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ActivePolicy {
    private final LivenessPolicy.ActiveMode mode;
    private final Integer minChallenges;
    private final Integer maxChallenges;
    private final List<ChallengeType> pool;
    private final List<ChallengeType> accessibilityPool;
    private final Boolean requireDepthRevealing;
    private final Long timeoutMs;
    private final Long minActionLatencyMs;
    private final Long maxActionLatencyMs;

    /** Canonical constructor; defensively copies the lists. */
    @JsonCreator
    public ActivePolicy(@JsonProperty("mode") LivenessPolicy.ActiveMode mode,
                        @JsonProperty("minChallenges") Integer minChallenges,
                        @JsonProperty("maxChallenges") Integer maxChallenges,
                        @JsonProperty("pool") List<ChallengeType> pool,
                        @JsonProperty("accessibilityPool") List<ChallengeType> accessibilityPool,
                        @JsonProperty("requireDepthRevealing") Boolean requireDepthRevealing,
                        @JsonProperty("timeoutMs") Long timeoutMs,
                        @JsonProperty("minActionLatencyMs") Long minActionLatencyMs,
                        @JsonProperty("maxActionLatencyMs") Long maxActionLatencyMs) {
        pool = pool == null ? null : List.copyOf(pool);
        accessibilityPool = accessibilityPool == null ? null : List.copyOf(accessibilityPool);
        this.mode = mode;
        this.minChallenges = minChallenges;
        this.maxChallenges = maxChallenges;
        this.pool = pool;
        this.accessibilityPool = accessibilityPool;
        this.requireDepthRevealing = requireDepthRevealing;
        this.timeoutMs = timeoutMs;
        this.minActionLatencyMs = minActionLatencyMs;
        this.maxActionLatencyMs = maxActionLatencyMs;
    }

    @JsonProperty public LivenessPolicy.ActiveMode mode() { return mode; }
    @JsonProperty public Integer minChallenges() { return minChallenges; }
    @JsonProperty public Integer maxChallenges() { return maxChallenges; }
    @JsonProperty public List<ChallengeType> pool() { return pool; }
    @JsonProperty public List<ChallengeType> accessibilityPool() { return accessibilityPool; }
    @JsonProperty public Boolean requireDepthRevealing() { return requireDepthRevealing; }
    @JsonProperty public Long timeoutMs() { return timeoutMs; }
    @JsonProperty public Long minActionLatencyMs() { return minActionLatencyMs; }
    @JsonProperty public Long maxActionLatencyMs() { return maxActionLatencyMs; }

    /** An active block that overrides nothing. */
    public static ActivePolicy empty() {
        return new ActivePolicy(null, null, null, null, null, null, null, null, null);
    }

    /** Convenience view of {@link #pool()} that is never {@code null}. */
    public List<ChallengeType> poolOrEmpty() {
        return pool == null ? Collections.emptyList() : pool;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ActivePolicy)) return false;
        ActivePolicy other = (ActivePolicy) o;
        return java.util.Objects.equals(mode, other.mode)
                && java.util.Objects.equals(minChallenges, other.minChallenges)
                && java.util.Objects.equals(maxChallenges, other.maxChallenges)
                && java.util.Objects.equals(pool, other.pool)
                && java.util.Objects.equals(accessibilityPool, other.accessibilityPool)
                && java.util.Objects.equals(requireDepthRevealing, other.requireDepthRevealing)
                && java.util.Objects.equals(timeoutMs, other.timeoutMs)
                && java.util.Objects.equals(minActionLatencyMs, other.minActionLatencyMs)
                && java.util.Objects.equals(maxActionLatencyMs, other.maxActionLatencyMs);
    }

    @Override
    public int hashCode() {
        int h = 0;
        h = 31 * h + java.util.Objects.hashCode(mode);
        h = 31 * h + java.util.Objects.hashCode(minChallenges);
        h = 31 * h + java.util.Objects.hashCode(maxChallenges);
        h = 31 * h + java.util.Objects.hashCode(pool);
        h = 31 * h + java.util.Objects.hashCode(accessibilityPool);
        h = 31 * h + java.util.Objects.hashCode(requireDepthRevealing);
        h = 31 * h + java.util.Objects.hashCode(timeoutMs);
        h = 31 * h + java.util.Objects.hashCode(minActionLatencyMs);
        h = 31 * h + java.util.Objects.hashCode(maxActionLatencyMs);
        return h;
    }

    @Override
    public String toString() {
        return "ActivePolicy[mode=" + mode + ", minChallenges=" + minChallenges + ", maxChallenges=" + maxChallenges + ", pool=" + pool + ", accessibilityPool=" + accessibilityPool + ", requireDepthRevealing=" + requireDepthRevealing + ", timeoutMs=" + timeoutMs + ", minActionLatencyMs=" + minActionLatencyMs + ", maxActionLatencyMs=" + maxActionLatencyMs + "]";
    }
}
