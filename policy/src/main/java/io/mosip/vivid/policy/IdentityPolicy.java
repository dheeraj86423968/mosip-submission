package io.mosip.vivid.policy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Identity-continuity (signal S7) settings.
 *
 * @param required       fail the session when the face identity drifts from the session anchor
 * @param driftThreshold cosine distance above which a frame is considered a different identity
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class IdentityPolicy {
    private final Boolean required;
    private final Double driftThreshold;

    @JsonCreator
    public IdentityPolicy(@JsonProperty("required") Boolean required,
                          @JsonProperty("driftThreshold") Double driftThreshold) {
        this.required = required;
        this.driftThreshold = driftThreshold;
    }

    @JsonProperty public Boolean required() { return required; }
    @JsonProperty public Double driftThreshold() { return driftThreshold; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IdentityPolicy)) return false;
        IdentityPolicy other = (IdentityPolicy) o;
        return java.util.Objects.equals(required, other.required)
                && java.util.Objects.equals(driftThreshold, other.driftThreshold);
    }

    @Override
    public int hashCode() {
        int h = 0;
        h = 31 * h + java.util.Objects.hashCode(required);
        h = 31 * h + java.util.Objects.hashCode(driftThreshold);
        return h;
    }

    @Override
    public String toString() {
        return "IdentityPolicy[required=" + required + ", driftThreshold=" + driftThreshold + "]";
    }
}
