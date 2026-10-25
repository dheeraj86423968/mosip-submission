package io.mosip.vivid.device.mock.sbi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosip.vivid.device.mock.scenario.Scenarios;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class MockSbiServerTest {
    static MockSbiServer server;
    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll static void start() { server = MockSbiServer.builder(Scenarios.bundled("live-pass")).port(0).build().start(); }
    @AfterAll static void stop() { server.close(); }

    private static HttpResponse<String> call(String verb, String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
                .method(verb, HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json").build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void discoveryAnswersCustomVerbWithSpecShapedArray() throws Exception {
        HttpResponse<String> r = call("MOSIPDISC", "/device", "{\"type\":\"Face\"}");
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode arr = JSON.readTree(r.body());
        assertThat(arr.isArray()).isTrue();
        JsonNode d = arr.get(0);
        assertThat(d.get("deviceId").asText()).isEqualTo(server.deviceId());
        assertThat(d.get("deviceStatus").asText()).isEqualTo("Ready");
        assertThat(d.get("certification").asText()).isEqualTo("L0");
        assertThat(d.get("purpose").asText()).isEqualTo("Registration");
        assertThat(d.get("specVersion").get(0).asText()).isEqualTo("0.9.5");
        assertThat(d.get("error").get("errorCode").asText()).isEqualTo("0");
        JsonNode digital = JSON.readTree(Base64.getUrlDecoder().decode(d.get("digitalId").asText()));
        assertThat(digital.get("type").asText()).isEqualTo("Face");
        assertThat(digital.get("mock").asBoolean()).isTrue();
        // a finger-only query gets an empty list from a face device
        assertThat(JSON.readTree(call("MOSIPDISC", "/device", "{\"type\":\"Finger\"}").body())).isEmpty();
    }

    @Test
    void infoReturnsUnsignedJwt() throws Exception {
        HttpResponse<String> r = call("MOSIPDINFO", "/info", "");
        assertThat(r.statusCode()).isEqualTo(200);
        String jwt = JSON.readTree(r.body()).get(0).get("deviceInfo").asText();
        String[] parts = jwt.split("\\.", -1);
        assertThat(parts).hasSize(3);
        assertThat(parts[2]).isEmpty();   // alg none
        JsonNode payload = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(payload.get("deviceId").asText()).isEqualTo(server.deviceId());
        assertThat(payload.get("mock").asBoolean()).isTrue();
        assertThat(call("MOSIPINFO", "/info", "").statusCode()).isEqualTo(200);   // alias accepted
    }

    @Test
    void streamDeliversMultipartMjpeg() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(server.baseUrl() + "/stream"))
                .method("STREAM", HttpRequest.BodyPublishers.ofString("{\"deviceId\":\"" + server.deviceId() + "\",\"deviceSubId\":0,\"timeout\":2000}")).build();
        HttpResponse<InputStream> r = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("multipart/x-mixed-replace; boundary=");
        byte[] all;
        try (InputStream in = r.body()) { all = in.readAllBytes(); }
        String text = new String(all, StandardCharsets.ISO_8859_1);
        assertThat(text).contains("--mosipvividframe\r\nContent-Type: image/jpeg\r\nContent-Length: ");
        int firstJpeg = text.indexOf("\r\n\r\n") + 4;
        int lenStart = text.indexOf("Content-Length: ") + 16;
        int len = Integer.parseInt(text.substring(lenStart, text.indexOf("\r\n", lenStart)));
        var img = ImageIO.read(new ByteArrayInputStream(all, firstJpeg, len));
        assertThat(img).isNotNull();
        assertThat(img.getWidth()).isEqualTo(640);
        assertThat(text).endsWith("--mosipvividframe--\r\n");
        assertThat(server.framesSent()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void captureReturnsMarkedMockBiometric() throws Exception {
        call("STREAM", "/stream", "{\"timeout\":300}");   // make sure a frame exists
        HttpResponse<String> r = call("RCAPTURE", "/capture",
                "{\"env\":\"Developer\",\"purpose\":\"Registration\",\"specVersion\":\"0.9.5\",\"timeout\":10000,\"transactionId\":\"tx-1\",\"bio\":[{\"type\":\"Face\",\"count\":1,\"requestedScore\":40,\"deviceId\":\"" + server.deviceId() + "\",\"deviceSubId\":0,\"previousHash\":\"\"}]}");
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode bio = JSON.readTree(r.body()).get("biometrics").get(0);
        assertThat(bio.get("error").get("errorCode").asText()).isEqualTo("0");
        assertThat(bio.get("hash").asText()).hasSize(64);
        JsonNode data = JSON.readTree(Base64.getUrlDecoder().decode(bio.get("data").asText().split("\\.")[1]));
        assertThat(data.get("bioType").asText()).isEqualTo("Face");
        assertThat(data.get("transactionId").asText()).isEqualTo("tx-1");
        assertThat(data.get("mock").asBoolean()).isTrue();
        byte[] jpeg = Base64.getUrlDecoder().decode(data.get("bioValue").asText());
        assertThat(ImageIO.read(new ByteArrayInputStream(jpeg))).isNotNull();
    }

    @Test
    void faultEndpointAndStatus() throws Exception {
        HttpResponse<String> r = call("POST", "/mock/fault", "{\"kind\":\"corrupt_frame\",\"params\":{\"count\":1}}");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(call("POST", "/mock/fault", "{\"kind\":\"nope\"}").statusCode()).isEqualTo(400);
        JsonNode status = JSON.readTree(call("GET", "/mock/status", "").body());
        assertThat(status.get("scenario").asText()).isEqualTo("live-pass");
        assertThat(status.get("plugged").asBoolean()).isTrue();
        assertThat(call("GET", "/nope", "").statusCode()).isEqualTo(404);
    }
}
