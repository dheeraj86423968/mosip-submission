package io.mosip.vivid.policy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Device-level settings shared by every workflow.
 *
 * @param minFps            frame rate below which the device is reported as degraded
 * @param reconnectAttempts how many times the frame source may reconnect before the session fails
 * @param stallTimeoutMs    time without frames after which the source is considered stalled
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class DevicePolicy {
    private final Integer minFps;
    private final Integer reconnectAttempts;
    private final Long stallTimeoutMs;

    @JsonCreator
    public DevicePolicy(@JsonProperty("minFps") Integer minFps,
                        @JsonProperty("reconnectAttempts") Integer reconnectAttempts,
                        @JsonProperty("stallTimeoutMs") Long stallTimeoutMs) {
        this.minFps = minFps;
        this.reconnectAttempts = reconnectAttempts;
        this.stallTimeoutMs = stallTimeoutMs;
    }

    @JsonProperty public Integer minFps() { return minFps; }
    @JsonProperty public Integer reconnectAttempts() { return reconnectAttempts; }
    @JsonProperty public Long stallTimeoutMs() { return stallTimeoutMs; }

    /** Compiled-in device defaults. */
    public static DevicePolicy defaults() {
        return new DevicePolicy(10, 5, 3000L);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DevicePolicy)) return false;
        DevicePolicy other = (DevicePolicy) o;
        return java.util.Objects.equals(minFps, other.minFps)
                && java.util.Objects.equals(reconnectAttempts, other.reconnectAttempts)
                && java.util.Objects.equals(stallTimeoutMs, other.stallTimeoutMs);
    }

    @Override
    public int hashCode() {
        int h = 0;
        h = 31 * h + java.util.Objects.hashCode(minFps);
        h = 31 * h + java.util.Objects.hashCode(reconnectAttempts);
        h = 31 * h + java.util.Objects.hashCode(stallTimeoutMs);
        return h;
    }

    @Override
    public String toString() {
        return "DevicePolicy[minFps=" + minFps + ", reconnectAttempts=" + reconnectAttempts + ", stallTimeoutMs=" + stallTimeoutMs + "]";
    }
}
