package io.mosip.vivid.device.mock.sbi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Builds SBI 0.9.5-shaped JSON for the mock. Shapes follow the MOSIP MDS/SBI specification; everything that a
 * real device would sign is emitted as an <b>unsigned</b> JWT ({@code alg: none}) and the payloads carry a
 * {@code "mock": true} marker so nothing produced here can be mistaken for certified device output.
 */
final class SbiMessages {
    static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private SbiMessages() {}

    /** Static identity of the mock device, configurable from the server builder. */
    record Identity(String deviceId, String serialNo, String make, String model, String deviceProvider, String deviceProviderId,
                    String certification, String purpose, String env, String specVersion, String serviceVersion, String firmware) {}

    static String timestamp() { return ZonedDateTime.now(ZoneOffset.UTC).format(ISO); }

    static ObjectNode digitalId(Identity id) {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("serialNo", id.serialNo()).put("make", id.make()).put("model", id.model())
         .put("type", "Face").put("deviceSubType", "Full face")
         .put("deviceProvider", id.deviceProvider()).put("deviceProviderId", id.deviceProviderId())
         .put("dateTime", timestamp()).put("mock", true);
        return d;
    }

    /** Discovery entry as returned by {@code MOSIPDISC /device}. */
    static ObjectNode discoverEntry(Identity id, String callbackUrl, String deviceStatus, String errorCode, String errorInfo) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("deviceId", id.deviceId());
        n.put("deviceStatus", deviceStatus);
        n.put("certification", id.certification());
        n.put("serviceVersion", id.serviceVersion());
        n.putArray("deviceSubId").add(0);
        n.put("callbackId", callbackUrl);
        n.put("digitalId", base64Url(digitalId(id).toString()));
        n.put("deviceCode", id.serialNo());
        n.putArray("specVersion").add(id.specVersion());
        n.put("purpose", id.purpose());
        n.set("error", error(errorCode, errorInfo));
        return n;
    }

    /** Device-info entry as returned by {@code MOSIPDINFO /info}: {@code deviceInfo} is an unsigned JWT. */
    static ObjectNode infoEntry(Identity id, String callbackUrl, String deviceStatus, String errorCode, String errorInfo) {
        return infoEntry(id, callbackUrl, deviceStatus, errorCode, errorInfo, null);
    }

    /** As above; {@code streamSigning}, when not null, advertises the signed preview extension inside {@code deviceInfo}. */
    static ObjectNode infoEntry(Identity id, String callbackUrl, String deviceStatus, String errorCode, String errorInfo, ObjectNode streamSigning) {
        ObjectNode info = MAPPER.createObjectNode();
        info.put("deviceStatus", deviceStatus).put("deviceId", id.deviceId()).put("firmware", id.firmware())
            .put("certification", id.certification()).put("serviceVersion", id.serviceVersion());
        info.putArray("deviceSubId").add(0);
        info.put("callbackId", callbackUrl);
        info.put("digitalId", unsignedJwt(digitalId(id).toString()));
        info.put("deviceCode", id.serialNo()).put("env", id.env()).put("purpose", id.purpose());
        info.putArray("specVersion").add(id.specVersion());
        info.put("mock", true);
        if (streamSigning != null) info.set("streamSigning", streamSigning);
        ObjectNode n = MAPPER.createObjectNode();
        n.put("deviceInfo", unsignedJwt(info.toString()));
        n.set("error", error(errorCode, errorInfo));
        return n;
    }

    /** Capture response for one face biometric. {@code previousHash} may be empty. */
    static ObjectNode captureResponse(Identity id, byte[] jpeg, String transactionId, String previousHash, int requestedScore, String env, String purpose) {
        String bioValue = base64Url(jpeg);
        ObjectNode data = MAPPER.createObjectNode();
        data.put("digitalId", unsignedJwt(digitalId(id).toString()));
        data.put("deviceCode", id.serialNo());
        data.put("deviceServiceVersion", id.serviceVersion());
        data.put("bioType", "Face");
        data.put("bioSubType", "");
        data.put("purpose", purpose);
        data.put("env", env);
        data.put("bioValue", bioValue);
        data.put("transactionId", transactionId);
        data.put("timestamp", timestamp());
        data.put("requestedScore", requestedScore);
        data.put("qualityScore", 85);
        data.put("mock", true);
        data.put("mockNote", "bioValue is a plain JPEG, not an ISO 19794-5 record; data is an unsigned JWT");

        ObjectNode bio = MAPPER.createObjectNode();
        bio.put("specVersion", id.specVersion());
        bio.put("data", unsignedJwt(data.toString()));
        bio.put("hash", chainedHash(previousHash, jpeg));
        bio.set("error", error("0", "Success"));

        ObjectNode resp = MAPPER.createObjectNode();
        resp.putArray("biometrics").add(bio);
        return resp;
    }

    static ObjectNode error(String code, String info) {
        return MAPPER.createObjectNode().put("errorCode", code).put("errorInfo", info);
    }

    static ArrayNode array(JsonNode... items) {
        ArrayNode a = MAPPER.createArrayNode();
        for (JsonNode i : items) a.add(i);
        return a;
    }

    /**
     * {@code hash = SHA-256(prev || SHA-256(bioValue))} over raw digest bytes, uppercase hex, where {@code prev} is the
     * hex-decoded previous hash or {@code SHA-256(empty)} for the first entry. This is how MOSIP's MockMDS and the
     * Registration Client compute it (see {@code docs/testing/interop-mockmds.md}); hashing the hex strings, as the
     * spec text reads, gives a different value.
     */
    static String chainedHash(String previousHash, byte[] bioValue) {
        byte[] prev = previousHash == null || previousHash.isBlank()
                ? sha256(new byte[0]) : HexFormat.of().parseHex(previousHash.trim());
        byte[] inner = sha256(bioValue);
        byte[] joined = new byte[prev.length + inner.length];
        System.arraycopy(prev, 0, joined, 0, prev.length);
        System.arraycopy(inner, 0, joined, prev.length, inner.length);
        return HexFormat.of().withUpperCase().formatHex(sha256(joined));
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static String base64Url(String s) { return base64Url(s.getBytes(StandardCharsets.UTF_8)); }
    static String base64Url(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }

    /** {@code header.payload.} with {@code alg: none}: structurally a JWT, verifiably not signed. */
    static String unsignedJwt(String payloadJson) {
        return base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "." + base64Url(payloadJson) + ".";
    }
}
