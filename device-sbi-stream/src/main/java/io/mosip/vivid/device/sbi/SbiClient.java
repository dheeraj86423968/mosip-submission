package io.mosip.vivid.device.sbi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosip.vivid.device.DeviceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Reusable client for one SBI service: discovery, device info, stream and capture. Stateless and thread-safe;
 * every call opens its own connection. Timeouts are per instance.
 *
 * <pre>{@code
 * SbiClient client = new SbiClient(1000, 2000);
 * List<SbiDeviceInfo> devices = client.discover(SbiEndpoint.local(4501), "Face");
 * try (SbiClient.Stream s = client.openStream(devices.get(0), 0, 60_000, 3000)) {
 *     MultipartStreamReader.Part part;
 *     while ((part = s.reader().next()) != null) { ... part.body() is a JPEG ... }
 * }
 * }</pre>
 */
public final class SbiClient {
    private static final Logger LOG = LoggerFactory.getLogger(SbiClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    /**
     * @param connectTimeoutMs TCP connect timeout for every request
     * @param readTimeoutMs    read timeout for request/response calls (discover, info, capture)
     */
    public SbiClient(int connectTimeoutMs, int readTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs; this.readTimeoutMs = readTimeoutMs;
    }

    /** An open STREAM response: the multipart reader plus the raw response for closing. */
    public static final class Stream implements Closeable {
        private final SbiHttp.Response response;
        private final MultipartStreamReader reader;
        Stream(SbiHttp.Response response, MultipartStreamReader reader) { this.response = response; this.reader = reader; }
        public MultipartStreamReader reader() { return reader; }
        public String contentType() { return response.contentType(); }
        /** Change the per-read (stall) timeout while streaming. */
        public void setReadTimeout(int millis) throws IOException { response.setReadTimeout(millis); }
        @Override public void close() { response.close(); }
    }

    /** {@code MOSIPDISC /device} with {@code {"type": type}}. Returns the parsed entries (possibly empty). */
    public List<SbiDeviceInfo> discover(SbiEndpoint ep, String type) throws IOException {
        ObjectNode body = MAPPER.createObjectNode().put("type", type);
        JsonNode root = json(ep, SbiVerbs.DISCOVER, SbiVerbs.PATH_DEVICE, body);
        List<SbiDeviceInfo> out = new ArrayList<>();
        if (root.isArray()) root.forEach(n -> out.add(SbiDeviceInfo.parse(ep, n)));
        else if (root.isObject()) out.add(SbiDeviceInfo.parse(ep, root));
        return out;
    }

    /**
     * {@code MOSIPDINFO /info}. Each entry's {@code deviceInfo} is a JWT (registered devices) or base64url
     * JSON (unregistered); the payload is decoded <b>without signature verification</b> and parsed.
     */
    public List<SbiDeviceInfo> info(SbiEndpoint ep) throws IOException {
        JsonNode root = json(ep, SbiVerbs.INFO, SbiVerbs.PATH_INFO, null);
        List<SbiDeviceInfo> out = new ArrayList<>();
        if (!root.isArray()) return out;
        for (JsonNode n : root) {
            JsonNode di = n.path("deviceInfo");
            JsonNode payload = di.isObject() ? di : decodeJwtPayload(di.asText(""));
            if (payload == null) { LOG.warn("undecodable deviceInfo from {}", ep); continue; }
            if (payload instanceof ObjectNode && n.has("error")) ((ObjectNode) payload).set("error", n.get("error"));
            out.add(SbiDeviceInfo.parse(ep, payload));
        }
        return out;
    }

    /**
     * Open {@code STREAM /stream}. The response must be {@code multipart/x-mixed-replace}; anything else is a
     * {@link DeviceException} carrying the SBI error if one was returned.
     *
     * @param streamTimeoutMs the {@code timeout} field of the request (how long the device keeps streaming)
     * @param readTimeoutMs   socket read timeout while streaming; a {@link java.net.SocketTimeoutException}
     *                        from the reader means no bytes arrived for this long (stall)
     */
    public Stream openStream(SbiDeviceInfo device, int deviceSubId, long streamTimeoutMs, int readTimeoutMs) throws IOException {
        return openStream(device.endpoint(), device.deviceId(), deviceSubId, streamTimeoutMs, readTimeoutMs);
    }

    /** See {@link #openStream(SbiDeviceInfo, int, long, int)}. */
    public Stream openStream(SbiEndpoint ep, String deviceId, int deviceSubId, long streamTimeoutMs, int readTimeoutMs) throws IOException {
        return openStream(ep, deviceId, deviceSubId, streamTimeoutMs, readTimeoutMs, null);
    }

    /** As above; {@code signedPreview}, when not null, requests the signed preview extension with these fields. */
    public Stream openStream(SbiEndpoint ep, String deviceId, int deviceSubId, long streamTimeoutMs, int readTimeoutMs, ObjectNode signedPreview) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("deviceId", deviceId == null ? "" : deviceId);
        body.put("deviceSubId", deviceSubId);
        body.put("timeout", streamTimeoutMs);
        if (signedPreview != null) body.set("signedPreview", signedPreview);
        SbiHttp.Response r = SbiHttp.request(ep, SbiVerbs.STREAM, SbiVerbs.PATH_STREAM, bytes(body), "application/json", connectTimeoutMs, readTimeoutMs);
        boolean ok = false;
        try {
            String ct = r.contentType().toLowerCase(Locale.ROOT);
            if (r.status != 200 || !ct.contains("multipart/")) {
                // MockMDS (and SBI services modelled on it) refuse a stream with HTTP 200 and a JSON error body,
                // e.g. {"errorCode":"111","errorInfo":"Device is busy"}; classify by the SBI code, not the status.
                String text = safeText(r);
                String code = sbiErrorCode(text);
                DeviceException.Kind kind = code != null ? kindOfStreamError(code)
                        : r.status == 200 ? DeviceException.Kind.PROTOCOL_ERROR : DeviceException.Kind.CONNECTION_FAILED;
                throw new DeviceException(kind, "STREAM " + ep + " answered " + r.status + " " + r.reason + " (" + r.contentType() + ")" + sbiError(text));
            }
            Stream s = new Stream(r, new MultipartStreamReader(r.body, r.contentType()));
            ok = true;
            return s;
        } finally {
            if (!ok) r.close();
        }
    }

    /**
     * {@code RCAPTURE /capture} (or {@code CAPTURE}). The request is passed through as built by the caller;
     * the response is returned as JSON for the caller to verify (signature checks are out of this adapter's scope).
     */
    public JsonNode capture(SbiEndpoint ep, String verb, JsonNode request, int captureReadTimeoutMs) throws IOException {
        SbiHttp.Response r = SbiHttp.request(ep, verb, SbiVerbs.PATH_CAPTURE, bytes(request), "application/json", connectTimeoutMs, captureReadTimeoutMs);
        try (r) {
            String text = r.readAllText();
            if (r.status != 200) throw new DeviceException(DeviceException.Kind.CONNECTION_FAILED, verb + " " + ep + " answered " + r.status + sbiError(text));
            return MAPPER.readTree(text);
        }
    }

    private JsonNode json(SbiEndpoint ep, String verb, String path, JsonNode body) throws IOException {
        SbiHttp.Response r = SbiHttp.request(ep, verb, path, body == null ? null : bytes(body), "application/json", connectTimeoutMs, readTimeoutMs);
        try (r) {
            String text = r.readAllText();
            if (r.status != 200) throw new DeviceException(DeviceException.Kind.PROTOCOL_ERROR, verb + " " + ep + " answered " + r.status + " " + r.reason + sbiError(text));
            try { return MAPPER.readTree(text); }
            catch (IOException e) { throw new DeviceException(DeviceException.Kind.PROTOCOL_ERROR, verb + " " + ep + " returned non-JSON body", e); }
        }
    }

    /** Decode the payload segment of a JWT (or a bare base64url JSON) without verifying anything. */
    static JsonNode decodeJwtPayload(String token) {
        if (token == null || token.isBlank()) return null;
        String[] segs = token.split("\\.", -1);
        String payload = segs.length >= 2 ? segs[1] : segs[0];
        try {
            byte[] raw;
            try { raw = Base64.getUrlDecoder().decode(payload); } catch (IllegalArgumentException e) { raw = Base64.getDecoder().decode(payload); }
            JsonNode n = MAPPER.readTree(new String(raw, StandardCharsets.UTF_8));
            return n.isObject() ? n : null;
        } catch (IllegalArgumentException | IOException e) {
            return null;
        }
    }

    /**
     * Maps an SBI error code returned for {@code STREAM} onto a device-api failure kind. Codes are from the SBI
     * 0.9.5 table and MockMDS v1.2.0.2 ({@code application.properties}, {@code mds_ERROR_*}):
     * <ul>
     *   <li>{@code 110} not ready, {@code 111} busy, {@code 607} not ready or busy: {@code CAMERA_UNAVAILABLE}
     *       (transient; another consumer may hold the stream);</li>
     *   <li>{@code 100} not registered, {@code 106} not found, {@code 601} auth device, {@code 604} no deviceId,
     *       {@code 605} unknown deviceId, {@code 606} not a registration device: {@code UNAVAILABLE} (the cached
     *       device no longer matches what the service exposes; rediscover);</li>
     *   <li>anything else ({@code 608}, {@code 609}, {@code 610}, ...): {@code PROTOCOL_ERROR}.</li>
     * </ul>
     */
    static DeviceException.Kind kindOfStreamError(String code) {
        switch (code.trim()) {
            case "110": case "111": case "607":
                return DeviceException.Kind.CAMERA_UNAVAILABLE;
            case "100": case "106": case "601": case "604": case "605": case "606":
                return DeviceException.Kind.UNAVAILABLE;
            default:
                return DeviceException.Kind.PROTOCOL_ERROR;
        }
    }

    /** The {@code errorCode} of an SBI JSON error body (top level or under {@code error}), or null. */
    static String sbiErrorCode(String text) {
        if (text == null || text.isBlank()) return null;
        int brace = text.indexOf('{');
        if (brace < 0) return null;
        try {
            JsonNode n = MAPPER.readTree(text.substring(brace));
            JsonNode err = n.has("error") ? n.get("error") : n;
            return err.hasNonNull("errorCode") ? err.get("errorCode").asText() : null;
        } catch (IOException e) {
            return null;
        }
    }

    static String sbiError(String text) {
        if (text == null || text.isBlank()) return "";
        int brace = text.indexOf('{');
        if (brace >= 0) {
            try {
                JsonNode n = MAPPER.readTree(text.substring(brace));
                JsonNode err = n.has("error") ? n.get("error") : n;
                if (err.has("errorCode")) return ": SBI error " + err.path("errorCode").asText() + " " + err.path("errorInfo").asText();
            } catch (IOException ignored) { }
        }
        return ": " + (text.length() > 200 ? text.substring(0, 200) + "..." : text);
    }

    private static String safeText(SbiHttp.Response r) {
        try { r.setReadTimeout(1000); return r.readAllText(); } catch (IOException e) { return ""; }
    }

    private static byte[] bytes(JsonNode n) { return n.toString().getBytes(StandardCharsets.UTF_8); }
}
