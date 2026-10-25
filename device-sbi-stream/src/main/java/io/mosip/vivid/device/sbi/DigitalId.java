package io.mosip.vivid.device.sbi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * The SBI Digital ID block: the device's identity as embedded (base64url, unsigned) in discovery responses
 * and (as a signed JWT) in device-info and capture responses. {@link #decode} accepts both encodings and never
 * throws; an undecodable value yields an instance with only {@link #raw()} set.
 */
public final class DigitalId {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String serialNo, make, model, type, deviceSubType, deviceProvider, deviceProviderId, dateTime, raw;
    private final boolean decoded;

    private DigitalId(JsonNode n, String raw) {
        this.raw = raw;
        this.decoded = n != null;
        serialNo = text(n, "serialNo"); make = text(n, "make"); model = text(n, "model"); type = text(n, "type");
        deviceSubType = text(n, "deviceSubType"); deviceProvider = text(n, "deviceProvider");
        deviceProviderId = text(n, "deviceProviderId"); dateTime = text(n, "dateTime");
    }

    private static String text(JsonNode n, String f) { return n == null ? "" : n.path(f).asText(""); }

    /** Decode a base64url JSON, a JWT (payload segment), or a plain JSON string. */
    public static DigitalId decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return new DigitalId(null, encoded == null ? "" : encoded);
        String s = encoded.trim();
        Optional<JsonNode> json = parseJson(s);
        if (json.isEmpty()) {
            String[] segs = s.split("\\.", -1);
            String payload = segs.length >= 2 ? segs[1] : s;      // JWT -> payload; otherwise raw base64
            json = decodeBase64(payload).flatMap(DigitalId::parseJson);
        }
        return new DigitalId(json.orElse(null), s);
    }

    private static Optional<JsonNode> parseJson(String s) {
        if (!s.startsWith("{")) return Optional.empty();
        try { JsonNode n = MAPPER.readTree(s); return n.isObject() ? Optional.of(n) : Optional.empty(); }
        catch (java.io.IOException e) { return Optional.empty(); }
    }

    private static Optional<String> decodeBase64(String s) {
        try { return Optional.of(new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8)); }
        catch (IllegalArgumentException e1) {
            try { return Optional.of(new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8)); }
            catch (IllegalArgumentException e2) { return Optional.empty(); }
        }
    }

    /** True if the JSON payload was parsed. */
    public boolean decoded() { return decoded; }
    public String serialNo() { return serialNo; }
    public String make() { return make; }
    public String model() { return model; }
    /** "Face", "Finger" or "Iris". */
    public String type() { return type; }
    public String deviceSubType() { return deviceSubType; }
    public String deviceProvider() { return deviceProvider; }
    public String deviceProviderId() { return deviceProviderId; }
    public String dateTime() { return dateTime; }
    /** The value as received. */
    public String raw() { return raw; }
    public boolean isFace() { return "Face".equalsIgnoreCase(type); }

    @Override public String toString() { return decoded ? make + " " + model + " (" + serialNo + ", " + type + ")" : "<undecoded digitalId>"; }
}
