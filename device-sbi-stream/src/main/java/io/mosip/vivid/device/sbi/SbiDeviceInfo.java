package io.mosip.vivid.device.sbi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosip.vivid.device.DeviceCapabilities;
import io.mosip.vivid.device.PixelFormat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One device entry as returned by {@code MOSIPDISC /device} or (decoded) {@code MOSIPDINFO /info}, plus the
 * endpoint that answered. All fields are optional in the wild; absent ones are empty strings or lists.
 */
public final class SbiDeviceInfo {
    private final SbiEndpoint endpoint;
    private final String deviceId, deviceCode, deviceStatus, certification, serviceVersion, callbackId, purpose, env, errorCode, errorInfo;
    private final List<Integer> deviceSubIds;
    private final List<String> specVersions;
    private final DigitalId digitalId;
    private final JsonNode raw;

    private SbiDeviceInfo(SbiEndpoint endpoint, JsonNode n) {
        this.endpoint = Objects.requireNonNull(endpoint);
        this.raw = n;
        deviceId = n.path("deviceId").asText(""); deviceCode = n.path("deviceCode").asText("");
        deviceStatus = n.path("deviceStatus").asText(""); certification = n.path("certification").asText("");
        serviceVersion = n.path("serviceVersion").asText(""); callbackId = n.path("callbackId").asText("");
        purpose = n.path("purpose").asText(""); env = n.path("env").asText("");
        errorCode = n.path("error").path("errorCode").asText(""); errorInfo = n.path("error").path("errorInfo").asText("");
        deviceSubIds = Collections.unmodifiableList(ints(n.path("deviceSubId")));
        specVersions = Collections.unmodifiableList(strings(n.path("specVersion")));
        digitalId = DigitalId.decode(n.path("digitalId").asText(""));
    }

    /** Parse one discovery entry (or a decoded deviceInfo payload). */
    public static SbiDeviceInfo parse(SbiEndpoint endpoint, JsonNode entry) { return new SbiDeviceInfo(endpoint, entry); }

    private static List<Integer> ints(JsonNode n) {
        List<Integer> out = new ArrayList<>();
        if (n.isArray()) n.forEach(x -> { if (x.canConvertToInt()) out.add(x.asInt()); else try { out.add(Integer.parseInt(x.asText().trim())); } catch (NumberFormatException ignored) { } });
        else if (n.canConvertToInt()) out.add(n.asInt());
        else if (n.isTextual()) try { out.add(Integer.parseInt(n.asText().trim())); } catch (NumberFormatException ignored) { }
        return out;
    }

    private static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n.isArray()) n.forEach(x -> out.add(x.asText()));
        else if (n.isTextual()) out.add(n.asText());
        return out;
    }

    public SbiEndpoint endpoint() { return endpoint; }
    public String deviceId() { return deviceId; }
    public String deviceCode() { return deviceCode; }
    /** "Ready", "Busy", "Not Ready" or "Not Registered". */
    public String deviceStatus() { return deviceStatus; }
    /** "L0" or "L1". */
    public String certification() { return certification; }
    public String serviceVersion() { return serviceVersion; }
    public String callbackId() { return callbackId; }
    /** "Registration" or "Auth" (empty for unregistered devices). */
    public String purpose() { return purpose; }
    public String env() { return env; }
    public String errorCode() { return errorCode; }
    public String errorInfo() { return errorInfo; }
    public List<Integer> deviceSubIds() { return deviceSubIds; }
    public List<String> specVersions() { return specVersions; }
    public DigitalId digitalId() { return digitalId; }
    public JsonNode raw() { return raw; }

    public boolean isReady() { return "Ready".equalsIgnoreCase(deviceStatus); }
    public boolean isRegistration() { return "Registration".equalsIgnoreCase(purpose); }
    /** Face device: by digitalId type, or (if the digitalId could not be decoded) assumed when the query was for Face. */
    public boolean isFace() { return digitalId.isFace() || !digitalId.decoded(); }
    /** The highest spec version advertised, or empty. */
    public String bestSpecVersion() { return specVersions.isEmpty() ? "" : specVersions.get(specVersions.size() - 1); }
    public int firstSubId() { return deviceSubIds.isEmpty() ? 0 : deviceSubIds.get(0); }

    public DeviceCapabilities.CertificationLevel certificationLevel() {
        if ("L1".equalsIgnoreCase(certification)) return DeviceCapabilities.CertificationLevel.L1;
        if ("L0".equalsIgnoreCase(certification)) return DeviceCapabilities.CertificationLevel.L0;
        return DeviceCapabilities.CertificationLevel.UNKNOWN;
    }

    /**
     * Map onto the device-api capability model. SBI advertises neither resolution nor fps, so callers pass what
     * they have observed (0 = not yet known). The stream is never signed ({@code signsFrames=false}).
     */
    public DeviceCapabilities toCapabilities(int width, int height, double fps) {
        return DeviceCapabilities.builder()
                .vendor(digitalId.decoded() ? digitalId.make() : "unknown")
                .model(digitalId.decoded() ? digitalId.model() : "unknown")
                .deviceId(deviceId.isEmpty() ? deviceCode : deviceId)
                .sbiSpecVersion(bestSpecVersion().isEmpty() ? serviceVersion : bestSpecVersion())
                .certificationLevel(certificationLevel())
                .resolution(width, height).nominalFps(fps)
                .formats(Set.of(PixelFormat.MJPEG))
                .hasDepth(false).hasIr(false).signsFrames(false).build();
    }

    @Override public String toString() {
        return "SbiDeviceInfo{" + endpoint + " id=" + deviceId + " status=" + deviceStatus + " cert=" + certification + " purpose=" + purpose + " spec=" + specVersions + " " + digitalId + "}";
    }
}
