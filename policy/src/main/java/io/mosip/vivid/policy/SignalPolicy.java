package io.mosip.vivid.policy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Fusion settings for one anti-spoofing signal.
 *
 * @param enabled whether the signal participates in fusion; {@code false} forces a weight of zero
 * @param weight  multiplicative weight of the signal's log-odds in fusion; {@code null} means 1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class SignalPolicy {
    private final Boolean enabled;
    private final Double weight;

    @JsonCreator
    public SignalPolicy(@JsonProperty("enabled") Boolean enabled, @JsonProperty("weight") Double weight) {
        this.enabled = enabled;
        this.weight = weight;
    }

    @JsonProperty public Boolean enabled() { return enabled; }
    @JsonProperty public Double weight() { return weight; }

    /** The effective fusion weight: zero when disabled, otherwise the configured weight or 1.0. */
    public double effectiveWeight() {
        if (enabled != null && !enabled) {
            return 0.0;
        }
        return weight == null ? 1.0 : weight;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SignalPolicy)) return false;
        SignalPolicy other = (SignalPolicy) o;
        return java.util.Objects.equals(enabled, other.enabled)
                && java.util.Objects.equals(weight, other.weight);
    }

    @Override
    public int hashCode() {
        int h = 0;
        h = 31 * h + java.util.Objects.hashCode(enabled);
        h = 31 * h + java.util.Objects.hashCode(weight);
        return h;
    }

    @Override
    public String toString() {
        return "SignalPolicy[enabled=" + enabled + ", weight=" + weight + "]";
    }
}
