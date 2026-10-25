package io.mosip.vivid.device.sbi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosip.vivid.device.DeviceCapabilities;
import io.mosip.vivid.device.DeviceHealth;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.PixelFormat;
import io.mosip.vivid.device.StreamOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in interoperability run against MOSIP's reference MockMDS (mosip-mock-services, tag v1.2.0.2) started with
 * {@code purpose=Registration, biometric.type=Biometric Device}. Enabled with {@code -Dvivid.interop.mockmds=<port>};
 * {@code tools/interop-mockmds.sh} builds and starts MockMDS and runs this class plus
 * {@link MockMdsConformanceTest}. Findings are in {@code docs/testing/interop-mockmds.md}.
 */
@EnabledIfSystemProperty(named = MockMdsInteropTest.PROPERTY, matches = "\\d+")
class MockMdsInteropTest {
    static final String PROPERTY = "vivid.interop.mockmds";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static int port() { return Integer.parseInt(System.getProperty(PROPERTY)); }
    static SbiEndpoint endpoint() { return SbiEndpoint.local(port()); }

    private SbiStreamFrameSource source;

    @AfterEach void stop() throws InterruptedException {
        if (source != null) source.stop();
        Thread.sleep(300);   // MockMDS frees the device on its next frame write after we close the socket
    }

    @Test
    void discoveryFindsTheFaceDeviceByScanningTheStandardRange() {
        SbiClient client = new SbiClient(1000, 3000);
        List<SbiDeviceInfo> found = SbiDiscovery.standard(client).discoverFaceDevices();
        System.out.println("[interop] scan 4501-4600 found: " + found);
        Optional<SbiDeviceInfo> face = SbiDiscovery.select(found, null, true);
        assertThat(face).isPresent();
        SbiDeviceInfo d = face.get();
        assertThat(d.endpoint().port()).isEqualTo(port());
        assertThat(d.digitalId().isFace()).isTrue();
        assertThat(d.digitalId().make()).isEqualTo("MOSIP");
        assertThat(d.digitalId().model()).isEqualTo("FACE01");
        assertThat(d.purpose()).isEqualTo("Registration");
        assertThat(d.specVersions()).contains("0.9.5");
        assertThat(d.deviceSubIds()).containsExactly(0);
        assertThat(d.certificationLevel()).isEqualTo(DeviceCapabilities.CertificationLevel.L0);
    }

    @Test
    void discoveryOfAllDevicesReturnsOnlyTheFaceOneToTheAdapter() throws Exception {
        SbiClient client = new SbiClient(1000, 3000);
        List<SbiDeviceInfo> all = client.discover(endpoint(), "Biometric Device");
        System.out.println("[interop] MOSIPDISC 'Biometric Device': " + all);
        assertThat(all).hasSizeGreaterThanOrEqualTo(3);
        assertThat(new SbiDiscovery(client, SbiVerbs.HOST, port(), port(), 1).probe(endpoint()))
                .singleElement().satisfies(d -> assertThat(d.digitalId().type()).isEqualTo("Face"));
    }

    @Test
    void deviceInfoIsSignedAndDecodes() throws Exception {
        List<SbiDeviceInfo> infos = new SbiClient(1000, 3000).info(endpoint());
        System.out.println("[interop] MOSIPDINFO: " + infos);
        SbiDeviceInfo face = infos.stream().filter(i -> i.digitalId().isFace()).findFirst().orElseThrow();
        assertThat(face.deviceId()).isNotEmpty();
        assertThat(face.digitalId().decoded()).isTrue();
        assertThat(face.digitalId().model()).isEqualTo("FACE01");
        assertThat(face.raw().path("firmware").asText()).startsWith("MOSIP.FACE");
    }

    @Test
    void streamDeliversMjpegFramesThroughTheAdapter() throws Exception {
        source = new SbiStreamFrameSource(SbiStreamConfig.builder().endpoint(endpoint()).build());
        DeviceCapabilities caps = source.capabilities();
        System.out.println("[interop] capabilities before stream: " + caps);
        SbiStreamFrameSourceTest.RecordingSink sink = new SbiStreamFrameSourceTest.RecordingSink();
        source.start(sink, new StreamOptions(15, 4, 3000, 3));
        assertThat(sink.awaitFrames(30, 15_000)).as("frames from MockMDS").isTrue();
        DeviceCapabilities after = source.capabilities();
        System.out.println("[interop] capabilities while streaming: " + after + " fps~" + after.nominalFps()
                + " received=" + source.framesReceived() + " dropped=" + source.framesDropped());
        FaceFrame f = sink.frames.get(0);
        assertThat(f.format()).isEqualTo(PixelFormat.MJPEG);
        assertThat(f.width()).isEqualTo(after.width());
        assertThat(f.height()).isEqualTo(after.height());
        ByteBuffer px = f.pixels();
        assertThat(px.get(0) & 0xff).isEqualTo(0xFF);
        assertThat(px.get(1) & 0xff).isEqualTo(0xD8);
        assertThat(sink.states()).startsWith(DeviceHealth.State.CONNECTING, DeviceHealth.State.CONNECTED);
        assertThat(sink.states()).doesNotContain(DeviceHealth.State.RECONNECTING);
    }

    @Test
    void streamTimeoutEndIsReportedAndRecovered() throws Exception {
        // MockMDS honours the request's timeout, then appends an HTTP JSON response (error 609) to the stream.
        source = new SbiStreamFrameSource(SbiStreamConfig.builder().endpoint(endpoint()).streamRequestTimeoutMs(1500).backoff(100, 400).build());
        SbiStreamFrameSourceTest.RecordingSink sink = new SbiStreamFrameSourceTest.RecordingSink();
        source.start(sink, new StreamOptions(15, 4, 3000, 5));
        assertThat(sink.awaitState(DeviceHealth.State.RECONNECTING, 10_000)).isTrue();
        int before = sink.frames.size();
        assertThat(sink.awaitFrames(before + 10, 10_000)).as("frames after the stream was re-opened").isTrue();
        System.out.println("[interop] health after timeout: " + sink.health);
    }

    @Test
    void busyDeviceIsRetriedNotFatal() throws Exception {
        // A second consumer while the first streams gets SBI error 111 "Device is busy" in an HTTP 200 JSON body.
        SbiClient client = new SbiClient(1000, 3000);
        try (SbiClient.Stream first = client.openStream(endpoint(), "3", 0, 5000, 3000)) {
            assertThat(first.reader().next()).isNotNull();
            try {
                client.openStream(endpoint(), "3", 0, 5000, 3000).close();
                throw new AssertionError("second STREAM should have been refused");
            } catch (io.mosip.vivid.device.DeviceException e) {
                System.out.println("[interop] second STREAM: " + e.kind() + " " + e.getMessage());
                assertThat(e.getMessage()).contains("111");
            }
        }
    }

    @Test
    void registrationCaptureReturnsSignedFaceWithChainedHash() throws Exception {
        SbiClient client = new SbiClient(1000, 3000);
        ObjectNode req = MAPPER.createObjectNode();
        req.put("env", "Staging").put("purpose", "Registration").put("specVersion", "0.9.5").put("timeout", 10000)
                .put("captureTime", Instant.now().toString()).put("transactionId", "vivid-interop-1");
        ObjectNode bio = req.putArray("bio").addObject();
        bio.put("type", "Face").put("count", 1).put("requestedScore", 40).put("deviceId", "3").put("deviceSubId", 0).put("previousHash", "");
        bio.putArray("bioSubType"); bio.putArray("exception");
        req.putNull("customOpts");
        JsonNode resp = client.capture(endpoint(), SbiVerbs.RCAPTURE, req, 15_000);
        JsonNode b = resp.path("biometrics").get(0);
        assertThat(b.path("error").path("errorCode").asText()).isEqualTo("0");
        String[] jws = b.path("data").asText().split("\\.");
        assertThat(jws).hasSize(3);
        JsonNode data = SbiClient.decodeJwtPayload(b.path("data").asText());
        assertThat(data.path("bioType").asText()).isEqualTo("Face");
        byte[] bioValue = Base64.getUrlDecoder().decode(data.path("bioValue").asText());
        assertThat(new String(bioValue, 0, 3, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("FAC");   // ISO 19794-5
        assertThat(SbiCaptureHash.chained("", bioValue)).isEqualTo(b.path("hash").asText());
        System.out.println("[interop] RCAPTURE ok: bioValue " + bioValue.length + " bytes, qualityScore " + data.path("qualityScore").asText());
    }
}
