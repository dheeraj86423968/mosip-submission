package io.mosip.vivid.device.mock.sbi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mosip.vivid.device.mock.scenario.Fault;
import io.mosip.vivid.device.mock.scenario.FaultKind;
import io.mosip.vivid.device.mock.scenario.Scenario;
import io.mosip.vivid.device.mock.scenario.Scenarios;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A mock MOSIP SBI (MDS 0.9.5-style) face device service built on {@code com.sun.net.httpserver}. It binds to
 * 127.0.0.1 on a configurable port (0 = ephemeral, see {@link #port()}) and implements:
 * <ul>
 *   <li>{@code MOSIPDISC /device} (also {@code POST /device}): discovery; body {@code {"type":"Face"}}</li>
 *   <li>{@code MOSIPDINFO /info} (also {@code MOSIPINFO}, {@code POST /info}): device info with an unsigned JWT</li>
 *   <li>{@code STREAM /stream}: {@code multipart/x-mixed-replace} MJPEG from the running {@link Scenario}</li>
 *   <li>{@code RCAPTURE /capture} and {@code CAPTURE /capture}: an unsigned, clearly marked mock capture of the last frame</li>
 *   <li>{@code POST /mock/fault} ({@code {"kind":"STALL","params":{"durationMs":3000}}}): fault injection</li>
 *   <li>{@code POST /mock/scenario} ({@code {"name":"print-attack"}} or {@code {"path":"..."}}): switch scenario</li>
 *   <li>{@code GET /mock/status}: simulator state for tests and dashboards</li>
 * </ul>
 * Multipart framing can mimic the reference MockMDS ({@link FramingStyle#MOCK_MDS}: {@code boundary=--BoundaryString}
 * and header lines without spaces) or standard RFC 2046 framing, so stream consumers can be tested against both.
 */
public final class MockSbiServer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(MockSbiServer.class);

    /** How the MJPEG multipart stream is framed on the wire. */
    public enum FramingStyle {
        /** {@code Content-Type: multipart/x-mixed-replace; boundary=frame}, parts delimited by {@code --frame}. */
        STANDARD,
        /** As MOSIP's MockMDS writes it: {@code boundary=--BoundaryString} and parts delimited by {@code --BoundaryString}. */
        MOCK_MDS
    }

    private final HttpServer server;
    private final ExecutorService executor;
    private final DeviceSimulator device;
    private final SbiMessages.Identity identity;
    private final FramingStyle framing;
    private final String boundary;
    private final long maxStreamMillis;
    private final AtomicInteger activeStreams = new AtomicInteger();
    private final AtomicLong framesSent = new AtomicLong();
    private final AtomicLong streamsOpened = new AtomicLong();
    private final SignedPreviewDevice signer;
    private volatile PreviewTamper tamper = PreviewTamper.NONE;

    /** Attacks on a signed preview stream, for testing the verifier (see {@link #tamper(PreviewTamper)}). */
    public enum PreviewTamper {
        NONE,
        /** Flip a byte of each JPEG after it was signed: a frame replaced in transit. */
        MODIFY_FRAME,
        /** Send each signed frame twice with the same sequence number: a replayed frame. */
        REPLAY_FRAME,
        /** Drop the signature headers: an unsigned frame injected into a signed stream. */
        STRIP_SIGNATURE,
        /** Sign the stream-init with a different key than the advertised device key: an impostor device. */
        IMPOSTOR_DEVICE
    }

    private MockSbiServer(Builder b) throws IOException {
        identity = new SbiMessages.Identity(b.deviceId, b.serialNo, b.make, b.model, b.deviceProvider, b.deviceProviderId,
                b.certification, b.purpose, b.env, b.specVersion, b.serviceVersion, b.firmware);
        framing = b.framing;
        boundary = b.boundary != null ? b.boundary : (framing == FramingStyle.MOCK_MDS ? "--BoundaryString" : "mosipvividframe");
        maxStreamMillis = b.maxStreamMillis;
        device = new DeviceSimulator(b.scenario, b.fps, b.jpegQuality, b.anchorClockOnStart);
        signer = b.signedPreview ? new SignedPreviewDevice() : null;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", b.port), 16);
        executor = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "mock-sbi-http"); t.setDaemon(true); return t; });
        server.setExecutor(executor);
        server.createContext("/", this::dispatch);
    }

    public static Builder builder(Scenario scenario) { return new Builder(scenario); }

    /** Bind and start serving. */
    public MockSbiServer start() {
        device.start();
        server.start();
        LOG.info("mock SBI '{}' listening on http://127.0.0.1:{} scenario='{}' framing={}", identity.deviceId(), port(), device.scenario().name(), framing);
        return this;
    }

    /** Stop serving; open streams are closed. */
    @Override public void close() {
        device.close();
        server.stop(0);
        executor.shutdownNow();
    }

    /** Actual bound port. */
    public int port() { return server.getAddress().getPort(); }
    public String baseUrl() { return "http://127.0.0.1:" + port(); }
    public String deviceId() { return identity.deviceId(); }
    public Scenario scenario() { return device.scenario(); }
    public long framesSent() { return framesSent.get(); }
    public int activeStreams() { return activeStreams.get(); }

    /** Inject a fault programmatically (same effect as {@code POST /mock/fault}). */
    public void inject(Fault fault) { device.inject(fault); }

    /** Tamper with the signed preview stream from the next STREAM request on. */
    public void tamper(PreviewTamper t) { tamper = Objects.requireNonNull(t); }

    /** Base64 SPKI of the device key when the signed preview extension is enabled, else null. */
    public String devicePublicKey() { return signer == null ? null : signer.publicKeyBase64(); }

    /** Switch the running scenario (same effect as {@code POST /mock/scenario}). */
    public void switchScenario(Scenario s) { device.switchScenario(s); }

    // ---------------------------------------------------------------- dispatch

    private void dispatch(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
        String path = ex.getRequestURI().getPath();
        try {
            if ("OPTIONS".equals(method)) { cors(ex); ex.sendResponseHeaders(204, -1); return; }
            switch (path) {
                case "/device" -> { if (isVerb(method, "MOSIPDISC", "POST", "GET")) { discover(ex); return; } }
                case "/info" -> { if (isVerb(method, "MOSIPDINFO", "MOSIPINFO", "POST", "GET")) { info(ex); return; } }
                case "/stream" -> { if (isVerb(method, "STREAM", "POST")) { stream(ex); return; } }
                case "/capture" -> { if (isVerb(method, "RCAPTURE", "CAPTURE", "POST")) { capture(ex, method); return; } }
                case "/mock/fault" -> { if ("POST".equals(method)) { fault(ex); return; } }
                case "/mock/scenario" -> { if ("POST".equals(method)) { switchScenario(ex); return; } }
                case "/mock/status" -> { status(ex); return; }
                default -> { }
            }
            sendJson(ex, 404, SbiMessages.error("500", "Unsupported verb/path " + method + " " + path));
        } catch (RuntimeException e) {
            LOG.warn("request {} {} failed", method, path, e);
            try { sendJson(ex, 500, SbiMessages.error("500", "Mock failure: " + e.getMessage())); } catch (IOException ignored) { }
        } finally {
            ex.close();
        }
    }

    private static boolean isVerb(String method, String... allowed) {
        for (String a : allowed) if (a.equals(method)) return true;
        return false;
    }

    private void discover(HttpExchange ex) throws IOException {
        JsonNode body = readJson(ex);
        String type = body.path("type").asText("");
        if (!type.isEmpty() && !"Face".equalsIgnoreCase(type) && !"Biometric Device".equalsIgnoreCase(type)) {
            // valid request for a type this device does not provide: an empty list is what a face-only service returns
            sendJson(ex, 200, SbiMessages.array());
            return;
        }
        String[] st = statusAndError();
        sendJson(ex, 200, SbiMessages.array(SbiMessages.discoverEntry(identity, baseUrl() + "/", st[0], st[1], st[2])));
    }

    private void info(HttpExchange ex) throws IOException {
        readJson(ex);
        String[] st = statusAndError();
        ObjectNode streamSigning = null;
        if (signer != null) {
            streamSigning = SbiMessages.MAPPER.createObjectNode().put("supported", true).put("alg", SignedPreviewDevice.ALG)
                    .put("deviceKey", signer.publicKeyBase64());
        }
        sendJson(ex, 200, SbiMessages.array(SbiMessages.infoEntry(identity, baseUrl() + "/", st[0], st[1], st[2], streamSigning)));
    }

    /** deviceStatus plus SBI error code/info for the current simulator state. */
    private String[] statusAndError() {
        if (!device.isPlugged()) return new String[] {"Not Ready", "110", "Device is not ready"};
        return new String[] {"Ready", "0", "Success"};
    }

    private void stream(HttpExchange ex) throws IOException {
        JsonNode body = readJson(ex);
        String reqDevice = body.path("deviceId").asText("");
        if (!reqDevice.isEmpty() && !reqDevice.equals(identity.deviceId())) {
            sendJson(ex, 404, SbiMessages.error("106", "Device not found"));
            return;
        }
        if (!device.isPlugged()) {
            sendJson(ex, 503, SbiMessages.error("202", "No Device Connected."));
            return;
        }
        long timeout = body.path("timeout").asLong(maxStreamMillis);
        if (timeout <= 0) timeout = maxStreamMillis;
        SignedPreviewDevice.Session sp = null;
        JsonNode spReq = body.path("signedPreview");
        if (signer != null && spReq.isObject()) {
            try {
                SignedPreviewDevice impostor = tamper == PreviewTamper.IMPOSTOR_DEVICE ? new SignedPreviewDevice() : signer;
                sp = impostor.new Session(spReq.path("clientKey").asText(""), spReq.path("clientNonce").asText(""));
            } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
                sendJson(ex, 400, SbiMessages.error("500", "signedPreview: " + e.getMessage()));
                return;
            }
        }
        device.ensureAnchored();
        streamsOpened.incrementAndGet();
        activeStreams.incrementAndGet();
        long generation = device.generation();
        cors(ex);
        ex.getResponseHeaders().set("Content-Type", "multipart/x-mixed-replace; boundary=" + boundary);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "close");
        ex.sendResponseHeaders(200, 0);
        String delimiter = boundary.startsWith("--") ? boundary : "--" + boundary;
        long deadline = System.nanoTime() + timeout * 1_000_000L;
        long lastSeq = -1;
        PreviewTamper attack = tamper;
        try (OutputStream out = ex.getResponseBody()) {
            if (sp != null) {
                byte[] init = sp.initJson().getBytes(StandardCharsets.UTF_8);
                out.write((delimiter + "\r\nContent-Type: application/json\r\nX-SBI-Part: stream-init\r\nContent-Length: "
                        + init.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(init);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
            while (System.nanoTime() < deadline) {
                Optional<DeviceSimulator.Published> p = device.awaitFrame(lastSeq, generation, 200);
                if (p.isEmpty()) {
                    if (!device.isPlugged() || device.generation() != generation) { LOG.info("stream: device disconnected, closing"); break; }
                    if (device.isEnded()) { LOG.info("stream: scenario ended, closing"); break; }
                    continue;   // stall or waiting for the first frame: keep the socket open and quiet
                }
                DeviceSimulator.Published f = p.get();
                lastSeq = f.sequence();
                byte[] jpeg = f.jpeg();
                String[] sig = sp == null ? null : sp.sign(jpeg, System.currentTimeMillis());
                if (sig != null && attack == PreviewTamper.MODIFY_FRAME) {
                    jpeg = jpeg.clone();
                    jpeg[jpeg.length / 2] ^= 0x5A;
                }
                if (attack == PreviewTamper.STRIP_SIGNATURE) sig = null;
                int copies = sig != null && attack == PreviewTamper.REPLAY_FRAME ? 2 : 1;
                for (int c = 0; c < copies; c++) {
                    out.write(partHeader(delimiter, jpeg.length, sig));
                    out.write(jpeg);
                    out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                }
                out.flush();
                framesSent.incrementAndGet();
            }
            out.write((delimiter + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            LOG.debug("stream client went away: {}", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            activeStreams.decrementAndGet();
        }
    }

    private byte[] partHeader(String delimiter, int length, String[] sig) {
        String signed = sig == null ? "" : "X-SBI-Frame-Seq: " + sig[0] + "\r\nX-SBI-Frame-Time: " + sig[1] + "\r\nX-SBI-Frame-Signature: " + sig[2] + "\r\n";
        String h = framing == FramingStyle.MOCK_MDS
                ? delimiter + "\r\nAccess-Control-Allow-Origin:*\r\nContent-Type:image/jpeg\r\nContent-Length:" + length + "\r\n" + signed + "\r\n"
                : delimiter + "\r\nContent-Type: image/jpeg\r\nContent-Length: " + length + "\r\n" + signed + "\r\n";
        return h.getBytes(StandardCharsets.US_ASCII);
    }

    private void capture(HttpExchange ex, String method) throws IOException {
        JsonNode body = readJson(ex);
        if (!device.isPlugged()) { sendJson(ex, 503, SbiMessages.error("202", "No Device Connected.")); return; }
        device.ensureAnchored();
        Optional<DeviceSimulator.Published> frame = device.latest();
        if (frame.isEmpty()) {
            // no frame rendered yet: wait briefly for the simulator to produce one
            try { frame = device.awaitFrame(-1, device.generation(), 2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (frame.isEmpty()) { sendJson(ex, 500, SbiMessages.error("101", "Unable to detect a biometric object")); return; }
        JsonNode bio = body.path("bio").isArray() && !body.path("bio").isEmpty() ? body.path("bio").get(0) : SbiMessages.MAPPER.createObjectNode();
        String purpose = "RCAPTURE".equals(method) ? "Registration" : body.path("purpose").asText(identity.purpose());
        ObjectNode resp = SbiMessages.captureResponse(identity, frame.get().jpeg(), body.path("transactionId").asText(""),
                bio.path("previousHash").asText(""), bio.path("requestedScore").asInt(0), body.path("env").asText(identity.env()), purpose);
        sendJson(ex, 200, resp);
    }

    private void fault(HttpExchange ex) throws IOException {
        JsonNode body = readJson(ex);
        String kindText = body.path("kind").asText("");
        FaultKind kind;
        try { kind = FaultKind.valueOf(kindText.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { sendJson(ex, 400, SbiMessages.error("400", "unknown fault kind '" + kindText + "'")); return; }
        Map<String, Object> params = new LinkedHashMap<>();
        JsonNode p = body.path("params");
        if (p.isObject()) p.fields().forEachRemaining(en -> params.put(en.getKey(),
                en.getValue().isNumber() ? en.getValue().numberValue() : en.getValue().isBoolean() ? en.getValue().asBoolean() : en.getValue().asText()));
        device.inject(new Fault(0, kind, params));
        sendJson(ex, 200, SbiMessages.MAPPER.createObjectNode().put("accepted", true).put("kind", kind.name()));
    }

    private void switchScenario(HttpExchange ex) throws IOException {
        JsonNode body = readJson(ex);
        try {
            Scenario s = body.has("path") ? Scenarios.load(java.nio.file.Path.of(body.get("path").asText()))
                    : Scenarios.bundled(body.path("name").asText(""));
            device.switchScenario(s);
            sendJson(ex, 200, SbiMessages.MAPPER.createObjectNode().put("scenario", s.name()));
        } catch (IllegalArgumentException | java.io.UncheckedIOException e) {
            sendJson(ex, 400, SbiMessages.error("400", e.getMessage()));
        }
    }

    private void status(HttpExchange ex) throws IOException {
        ObjectNode n = SbiMessages.MAPPER.createObjectNode();
        n.put("deviceId", identity.deviceId()).put("scenario", device.scenario().name()).put("plugged", device.isPlugged())
         .put("ended", device.isEnded()).put("scenarioMs", device.scenarioMs()).put("activeStreams", activeStreams.get())
         .put("streamsOpened", streamsOpened.get()).put("framesSent", framesSent.get()).put("framing", framing.name()).put("boundary", boundary);
        device.latest().ifPresent(f -> n.put("lastFrameSequence", f.sequence()).put("lastFrameLabel", f.label().name()).put("lastFrameCorrupted", f.corrupted()));
        sendJson(ex, 200, n);
    }

    // ---------------------------------------------------------------- helpers

    private static JsonNode readJson(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readAllBytes();
            if (bytes.length == 0) return SbiMessages.MAPPER.createObjectNode();
            try { return SbiMessages.MAPPER.readTree(bytes); }
            catch (IOException e) { return SbiMessages.MAPPER.createObjectNode(); }
        }
    }

    private static void cors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "MOSIPDISC, MOSIPDINFO, STREAM, RCAPTURE, CAPTURE, POST, GET, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void sendJson(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        cors(ex);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }

    /** Builder for {@link MockSbiServer}. */
    public static final class Builder {
        private final Scenario scenario;
        private int port = 4501;
        private double fps;
        private float jpegQuality = 0.85f;
        private FramingStyle framing = FramingStyle.STANDARD;
        private String boundary;
        private long maxStreamMillis = 5 * 60 * 1000L;
        private boolean anchorClockOnStart;
        private boolean signedPreview;
        private String deviceId = "MOCK-FACE-0001", serialNo = "MOCKSN0001", make = "MOSIP Vivid", model = "mock-face-l0",
                deviceProvider = "MOSIP Vivid (mock)", deviceProviderId = "mosip-vivid-mock", certification = "L0",
                purpose = "Registration", env = "Developer", specVersion = "0.9.5", serviceVersion = "0.9.5", firmware = "mock-1.0";

        private Builder(Scenario scenario) { this.scenario = Objects.requireNonNull(scenario); }
        /** TCP port on 127.0.0.1; 0 picks a free port. SBI services live in 4501..4600. */
        public Builder port(int v) { port = v; return this; }
        /** Override the scenario fps (0 = use the scenario's). */
        public Builder fps(double v) { fps = v; return this; }
        public Builder jpegQuality(float v) { jpegQuality = v; return this; }
        public Builder framing(FramingStyle v) { framing = Objects.requireNonNull(v); return this; }
        /** Explicit boundary token; default depends on framing style. */
        public Builder boundary(String v) { boundary = v; return this; }
        /** Default STREAM timeout when the request has none (spec default: 5 minutes). */
        public Builder maxStreamMillis(long v) { maxStreamMillis = v; return this; }
        /** Start scenario time at server start instead of at the first STREAM request. */
        public Builder anchorClockOnStart(boolean v) { anchorClockOnStart = v; return this; }
        /** Implement the signed preview-stream extension (docs/design/02-frame-streaming.md §6). */
        public Builder signedPreview(boolean v) { signedPreview = v; return this; }
        public Builder deviceId(String v) { deviceId = v; return this; }
        public Builder serialNo(String v) { serialNo = v; return this; }
        public Builder make(String v) { make = v; return this; }
        public Builder model(String v) { model = v; return this; }
        /** "L0" or "L1". */
        public Builder certification(String v) { certification = v; return this; }
        /** "Registration" or "Auth". */
        public Builder purpose(String v) { purpose = v; return this; }
        public Builder env(String v) { env = v; return this; }
        public Builder specVersion(String v) { specVersion = v; return this; }
        public Builder serviceVersion(String v) { serviceVersion = v; return this; }
        /** Build without starting; call {@link MockSbiServer#start()}. */
        public MockSbiServer build() {
            try { return new MockSbiServer(this); }
            catch (IOException e) { throw new java.io.UncheckedIOException("cannot bind mock SBI on 127.0.0.1:" + port, e); }
        }
    }
}
