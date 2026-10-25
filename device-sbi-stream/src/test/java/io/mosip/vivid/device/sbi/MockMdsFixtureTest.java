package io.mosip.vivid.device.sbi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosip.vivid.device.DeviceCapabilities;
import io.mosip.vivid.device.DeviceException;
import io.mosip.vivid.device.DeviceHealth;
import io.mosip.vivid.device.StreamOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests built from responses recorded against MOSIP's MockMDS v1.2.0.2 on 2026-10-24
 * ({@code src/test/resources/mockmds}, recorded by {@code tools/interop-mockmds.sh}; MockMDS is MPL-2.0).
 * Each test reproduces an interoperability defect found in that run, or pins a confirmed wire detail.
 */
class MockMdsFixtureTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private RecordedSbiServer server;
    private SbiStreamFrameSource source;

    @AfterEach void tearDown() throws Exception {
        if (source != null) source.stop();
        if (server != null) server.close();
    }

    private static String disc(String deviceId) {
        String s = new String(RecordedSbiServer.fixture("disc-face.http"), StandardCharsets.ISO_8859_1);
        return s.replace("\"deviceId\":\"3\"", "\"deviceId\":\"" + deviceId + "\"");
    }

    @Test
    void recordedDiscoveryAndInfoParse() throws Exception {
        SbiEndpoint ep = SbiEndpoint.local(4501);
        JsonNode disc = MAPPER.readTree(RecordedSbiServer.fixtureBody("disc-face.http"));
        SbiDeviceInfo d = SbiDeviceInfo.parse(ep, disc.get(0));
        assertThat(d.deviceId()).isEqualTo("3");
        assertThat(d.deviceSubIds()).containsExactly(0);       // MockMDS sends ["0"] (strings)
        assertThat(d.digitalId().decoded()).isTrue();          // unsigned base64url JSON in discovery
        DeviceCapabilities caps = d.toCapabilities(0, 0, 0);
        assertThat(caps.vendor()).isEqualTo("MOSIP");
        assertThat(caps.model()).isEqualTo("FACE01");
        assertThat(caps.sbiSpecVersion()).isEqualTo("0.9.5");

        JsonNode info = MAPPER.readTree(RecordedSbiServer.fixtureBody("info.http"));
        assertThat(info).hasSize(3);                           // finger, face, iris: MOSIPDINFO is not filtered by type
        JsonNode payload = SbiClient.decodeJwtPayload(info.get(1).path("deviceInfo").asText());
        assertThat(payload.path("deviceId").asText()).isEqualTo("3");
        assertThat(DigitalId.decode(payload.path("digitalId").asText()).model()).isEqualTo("FACE01");   // signed JWT inside
    }

    @Test
    void recordedStreamYieldsJpegPartsAndThe609Trailer() throws Exception {
        byte[] raw = RecordedSbiServer.fixture("stream-timeout.http");
        String all = new String(raw, StandardCharsets.ISO_8859_1);
        int bodyAt = all.indexOf("\r\n\r\n") + 4;
        assertThat(all).startsWith("HTTP/1.0 200 OK").contains("Content-Type: multipart/x-mixed-replace; boundary=--BoundaryString");
        MultipartStreamReader r = new MultipartStreamReader(new ByteArrayInputStream(raw, bodyAt, raw.length - bodyAt), "multipart/x-mixed-replace; boundary=--BoundaryString");
        MultipartStreamReader.Part p1 = r.next(), p2 = r.next();
        assertThat(p1.contentType()).isEqualTo("image/jpeg");
        assertThat(JpegDimensions.of(p1.body())).hasValueSatisfying(wh -> assertThat(wh).containsExactly(490, 360));
        assertThat(p2.body()).isEqualTo(p1.body());              // MockMDS repeats one still image
        assertThat(r.next()).isNull();
        String trailer = new String(r.trailer(), StandardCharsets.ISO_8859_1);
        assertThat(trailer).startsWith("HTTP/1.1 200 OK");
        assertThat(SbiClient.sbiErrorCode(trailer)).isEqualTo("609");
    }

    @Test
    void streamRefusalsInHttp200JsonBodiesAreClassifiedBySbiCode() throws Exception {
        server = new RecordedSbiServer(req -> RecordedSbiServer.fixture(req.body.contains("\"3\"") ? "stream-busy.http" : "stream-baddevice.http"));
        SbiClient client = new SbiClient(1000, 2000);
        SbiEndpoint ep = SbiEndpoint.local(server.port());
        // Before the fix both were PROTOCOL_ERROR because the HTTP status is 200.
        assertThatThrownBy(() -> client.openStream(ep, "3", 0, 5000, 2000))
                .isInstanceOfSatisfying(DeviceException.class, e -> assertThat(e.kind()).isEqualTo(DeviceException.Kind.CAMERA_UNAVAILABLE))
                .hasMessageContaining("SBI error 111 Device is busy");
        assertThatThrownBy(() -> client.openStream(ep, "99", 0, 5000, 2000))
                .isInstanceOfSatisfying(DeviceException.class, e -> assertThat(e.kind()).isEqualTo(DeviceException.Kind.UNAVAILABLE))
                .hasMessageContaining("SBI error 605");
    }

    @Test
    void pinnedEndpointRediscoversWhenTheServiceComesBackWithAnotherDeviceId() throws Exception {
        // MockMDS restarted (or a different SBI service took the port) and now exposes the face device as "7".
        // Before the fix a pinned endpoint kept the cached deviceId "3", got SBI 605 on every reconnect and FAILED.
        AtomicBoolean restarted = new AtomicBoolean();
        server = new RecordedSbiServer(req -> {
            String id = restarted.get() ? "7" : "3";
            switch (req.verb) {
                case "MOSIPDISC": return disc(id).getBytes(StandardCharsets.ISO_8859_1);
                case "STREAM":
                    if (!req.body.contains("\"deviceId\":\"" + id + "\"")) return RecordedSbiServer.fixture("stream-baddevice.http");
                    restarted.set(true);                       // the first stream ends (609) and the service "restarts"
                    return RecordedSbiServer.fixture("stream-timeout.http");
                default: return RecordedSbiServer.fixture("stream-baddevice.http");
            }
        });
        source = new SbiStreamFrameSource(SbiStreamConfig.builder().endpoint(SbiEndpoint.local(server.port())).backoff(20, 50).build());
        SbiStreamFrameSourceTest.RecordingSink sink = new SbiStreamFrameSourceTest.RecordingSink();
        source.start(sink, new StreamOptions(15, 8, 2000, 3));
        assertThat(sink.awaitFrames(4, 10_000)).as("frames from both service incarnations, health " + sink.health).isTrue();
        assertThat(server.requests.stream().filter(r -> r.verb.equals("STREAM")).map(r -> r.body).toList())
                .anySatisfy(b -> assertThat(b).contains("\"deviceId\":\"7\""));
        List<DeviceHealth> health = List.copyOf(sink.health);
        assertThat(health).anySatisfy(h -> assertThat(h.reason()).contains("stream ended by device: SBI error 609 Live Stream timeout"));
        assertThat(health).noneMatch(h -> h.state() == DeviceHealth.State.FAILED);
    }

    @Test
    void rfcDelimiterIsAcceptedWhenTheBoundaryItselfStartsWithDashes() throws Exception {
        // RFC 2046 writers put "--" before a boundary that already starts with "--"; MockMDS does not.
        // Before the fix only the MockMDS form was recognised and such a stream yielded no parts.
        String body = "----abc\r\nContent-Type: image/jpeg\r\nContent-Length: 3\r\n\r\nONE\r\n"
                + "----abc\r\nContent-Type: image/jpeg\r\n\r\nTWO\r\n----abc--\r\n";
        MultipartStreamReader r = new MultipartStreamReader(new ByteArrayInputStream(body.getBytes(StandardCharsets.ISO_8859_1)), "multipart/x-mixed-replace; boundary=--abc");
        assertThat(new String(r.next().body(), StandardCharsets.ISO_8859_1)).isEqualTo("ONE");
        assertThat(new String(r.next().body(), StandardCharsets.ISO_8859_1)).isEqualTo("TWO");
        assertThat(r.next()).isNull();
    }

    @Test
    void captureHashChainMatchesRecordedRcapture() throws Exception {
        JsonNode resp = MAPPER.readTree(RecordedSbiServer.fixtureBody("rcapture-face.http"));
        JsonNode b = resp.path("biometrics").get(0);
        JsonNode data = SbiClient.decodeJwtPayload(b.path("data").asText());
        byte[] bioValue = Base64.getUrlDecoder().decode(data.path("bioValue").asText());
        assertThat(SbiCaptureHash.chained("", bioValue)).isEqualTo(b.path("hash").asText());
        // the hex-string reading of the spec gives a different value
        String hexReading = SbiCaptureHash.hex(SbiCaptureHash.sha256(SbiCaptureHash.hex(SbiCaptureHash.sha256(bioValue)).getBytes(StandardCharsets.US_ASCII)));
        assertThat(hexReading).isNotEqualTo(b.path("hash").asText());
        // chaining a second biometric uses the first hash as raw bytes
        assertThat(SbiCaptureHash.chained(b.path("hash").asText(), bioValue)).hasSize(64).isNotEqualTo(b.path("hash").asText());
    }
}
