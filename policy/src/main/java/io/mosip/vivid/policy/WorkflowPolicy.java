package io.mosip.vivid.policy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.mosip.vivid.api.LivenessPolicy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Policy overrides for one {@link io.mosip.vivid.api.Workflow}. Mirrors every field of
 * {@link io.mosip.vivid.api.LivenessPolicy}; a {@code null} field means "keep the compiled-in default".
 *
 * @param enabled           whether liveness is enforced for the workflow at all
 * @param passiveThreshold  fused liveness score in [0,1] needed to pass passively
 * @param padHardThreshold  attack probability in [0,1] above which a single signal vetoes the session
 * @param passiveWindowMs   minimum observation time before the passive decision
 * @param passiveMinFrames  minimum number of good-quality frames before the passive decision
 * @param active            active-challenge settings
 * @param maxRetries        total number of attempts before the session locks
 * @param onMaxRetries      what the Registration Client should do once the session locks
 * @param sessionTimeoutMs  wall-clock budget per attempt
 * @param minFaceQuality    minimum {@link io.mosip.vivid.api.FaceQuality#overall()} for a frame to be scored
 * @param identity          identity-continuity settings
 * @param signals           per-signal enable flag and fusion weight, keyed by signal id
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class WorkflowPolicy {
    private final Boolean enabled;
    private final Double passiveThreshold;
    private final Double padHardThreshold;
    private final Long passiveWindowMs;
    private final Integer passiveMinFrames;
    private final ActivePolicy active;
    private final Integer maxRetries;
    private final LivenessPolicy.OnMaxRetries onMaxRetries;
    private final Long sessionTimeoutMs;
    private final Double minFaceQuality;
    private final IdentityPolicy identity;
    private final Map<String, SignalPolicy> signals;

    /** Canonical constructor; defensively copies the signal map preserving insertion order. */
    @JsonCreator
    public WorkflowPolicy(@JsonProperty("enabled") Boolean enabled,
                          @JsonProperty("passiveThreshold") Double passiveThreshold,
                          @JsonProperty("padHardThreshold") Double padHardThreshold,
                          @JsonProperty("passiveWindowMs") Long passiveWindowMs,
                          @JsonProperty("passiveMinFrames") Integer passiveMinFrames,
                          @JsonProperty("active") ActivePolicy active,
                          @JsonProperty("maxRetries") Integer maxRetries,
                          @JsonProperty("onMaxRetries") LivenessPolicy.OnMaxRetries onMaxRetries,
                          @JsonProperty("sessionTimeoutMs") Long sessionTimeoutMs,
                          @JsonProperty("minFaceQuality") Double minFaceQuality,
                          @JsonProperty("identity") IdentityPolicy identity,
                          @JsonProperty("signals") Map<String, SignalPolicy> signals) {
        signals = signals == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(signals));
        this.enabled = enabled;
        this.passiveThreshold = passiveThreshold;
        this.padHardThreshold = padHardThreshold;
        this.passiveWindowMs = passiveWindowMs;
        this.passiveMinFrames = passiveMinFrames;
        this.active = active;
        this.maxRetries = maxRetries;
        this.onMaxRetries = onMaxRetries;
        this.sessionTimeoutMs = sessionTimeoutMs;
        this.minFaceQuality = minFaceQuality;
        this.identity = identity;
        this.signals = signals;
    }

    @JsonProperty public Boolean enabled() { return enabled; }
    @JsonProperty public Double passiveThreshold() { return passiveThreshold; }
    @JsonProperty public Double padHardThreshold() { return padHardThreshold; }
    @JsonProperty public Long passiveWindowMs() { return passiveWindowMs; }
    @JsonProperty public Integer passiveMinFrames() { return passiveMinFrames; }
    @JsonProperty public ActivePolicy active() { return active; }
    @JsonProperty public Integer maxRetries() { return maxRetries; }
    @JsonProperty public LivenessPolicy.OnMaxRetries onMaxRetries() { return onMaxRetries; }
    @JsonProperty public Long sessionTimeoutMs() { return sessionTimeoutMs; }
    @JsonProperty public Double minFaceQuality() { return minFaceQuality; }
    @JsonProperty public IdentityPolicy identity() { return identity; }
    @JsonProperty public Map<String, SignalPolicy> signals() { return signals; }

    /** A workflow block that overrides nothing. */
    public static WorkflowPolicy empty() {
        return new WorkflowPolicy(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** Fluent builder for tests and programmatic construction. */
    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder producing an immutable {@link WorkflowPolicy}. */
    public static final class Builder {
        private Boolean enabled;
        private Double passiveThreshold;
        private Double padHardThreshold;
        private Long passiveWindowMs;
        private Integer passiveMinFrames;
        private ActivePolicy active;
        private Integer maxRetries;
        private LivenessPolicy.OnMaxRetries onMaxRetries;
        private Long sessionTimeoutMs;
        private Double minFaceQuality;
        private IdentityPolicy identity;
        private Map<String, SignalPolicy> signals;

        private Builder() {}

        public Builder enabled(Boolean v) { enabled = v; return this; }
        public Builder passiveThreshold(Double v) { passiveThreshold = v; return this; }
        public Builder padHardThreshold(Double v) { padHardThreshold = v; return this; }
        public Builder passiveWindowMs(Long v) { passiveWindowMs = v; return this; }
        public Builder passiveMinFrames(Integer v) { passiveMinFrames = v; return this; }
        public Builder active(ActivePolicy v) { active = v; return this; }
        public Builder maxRetries(Integer v) { maxRetries = v; return this; }
        public Builder onMaxRetries(LivenessPolicy.OnMaxRetries v) { onMaxRetries = v; return this; }
        public Builder sessionTimeoutMs(Long v) { sessionTimeoutMs = v; return this; }
        public Builder minFaceQuality(Double v) { minFaceQuality = v; return this; }
        public Builder identity(IdentityPolicy v) { identity = v; return this; }
        public Builder signals(Map<String, SignalPolicy> v) { signals = v; return this; }

        public WorkflowPolicy build() {
            return new WorkflowPolicy(enabled, passiveThreshold, padHardThreshold, passiveWindowMs, passiveMinFrames, active,
                    maxRetries, onMaxRetries, sessionTimeoutMs, minFaceQuality, identity, signals);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorkflowPolicy)) return false;
        WorkflowPolicy other = (WorkflowPolicy) o;
        return java.util.Objects.equals(enabled, other.enabled)
                && java.util.Objects.equals(passiveThreshold, other.passiveThreshold)
                && java.util.Objects.equals(padHardThreshold, other.padHardThreshold)
                && java.util.Objects.equals(passiveWindowMs, other.passiveWindowMs)
                && java.util.Objects.equals(passiveMinFrames, other.passiveMinFrames)
                && java.util.Objects.equals(active, other.active)
                && java.util.Objects.equals(maxRetries, other.maxRetries)
                && java.util.Objects.equals(onMaxRetries, other.onMaxRetries)
                && java.util.Objects.equals(sessionTimeoutMs, other.sessionTimeoutMs)
                && java.util.Objects.equals(minFaceQuality, other.minFaceQuality)
                && java.util.Objects.equals(identity, other.identity)
                && java.util.Objects.equals(signals, other.signals);
    }

    @Override
    public int hashCode() {
        int h = 0;
        h = 31 * h + java.util.Objects.hashCode(enabled);
        h = 31 * h + java.util.Objects.hashCode(passiveThreshold);
        h = 31 * h + java.util.Objects.hashCode(padHardThreshold);
        h = 31 * h + java.util.Objects.hashCode(passiveWindowMs);
        h = 31 * h + java.util.Objects.hashCode(passiveMinFrames);
        h = 31 * h + java.util.Objects.hashCode(active);
        h = 31 * h + java.util.Objects.hashCode(maxRetries);
        h = 31 * h + java.util.Objects.hashCode(onMaxRetries);
        h = 31 * h + java.util.Objects.hashCode(sessionTimeoutMs);
        h = 31 * h + java.util.Objects.hashCode(minFaceQuality);
        h = 31 * h + java.util.Objects.hashCode(identity);
        h = 31 * h + java.util.Objects.hashCode(signals);
        return h;
    }

    @Override
    public String toString() {
        return "WorkflowPolicy[enabled=" + enabled + ", passiveThreshold=" + passiveThreshold + ", padHardThreshold=" + padHardThreshold + ", passiveWindowMs=" + passiveWindowMs + ", passiveMinFrames=" + passiveMinFrames + ", active=" + active + ", maxRetries=" + maxRetries + ", onMaxRetries=" + onMaxRetries + ", sessionTimeoutMs=" + sessionTimeoutMs + ", minFaceQuality=" + minFaceQuality + ", identity=" + identity + ", signals=" + signals + "]";
    }
}
