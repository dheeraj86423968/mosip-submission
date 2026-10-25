package io.mosip.vivid.device.sbi;

import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.StreamOptions;
import io.mosip.vivid.device.mock.sbi.MockSbiServer;
import io.mosip.vivid.device.mock.sbi.MockSbiServer.PreviewTamper;
import io.mosip.vivid.device.mock.scenario.Scenarios;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The signed preview-stream extension end to end over HTTP: the mock device signs (device-mock's independent
 * implementation), the adapter verifies, and every tampering mode leaves frames without a signature, which the core
 * then treats as injection (covered by {@code FrameSourceBridgeTest}).
 */
class SignedPreviewTest {
    private MockSbiServer server;
    private SbiStreamFrameSource source;

    @AfterEach void tearDown() { if (source != null) source.stop(); if (server != null) server.close(); }

    private void start(boolean deviceSigns, PreviewTamper tamper, SbiStreamConfig.SignedPreview mode, String pinnedKey) {
        server = MockSbiServer.builder(Scenarios.bundled("live-pass")).port(0).fps(20).signedPreview(deviceSigns).build().start();
        server.tamper(tamper);
        SbiStreamConfig.Builder b = SbiStreamConfig.builder().endpoint(SbiEndpoint.local(server.port())).signedPreview(mode);
        if (pinnedKey != null) b.trustedDeviceKey(pinnedKey);
        source = new SbiStreamFrameSource(b.build());
    }

    private List<FaceFrame> frames(int n) throws InterruptedException {
        SbiStreamFrameSourceTest.RecordingSink sink = new SbiStreamFrameSourceTest.RecordingSink();
        source.start(sink, new StreamOptions(15, 8, 3000, 2));
        assertThat(sink.awaitFrames(n, 15_000)).as("frames received").isTrue();
        return sink.frames.subList(0, n);
    }

    private static long signed(List<FaceFrame> fs) { return fs.stream().filter(f -> f.deviceSignature().isPresent()).count(); }

    @Test
    void signingDeviceFramesAllVerify() throws Exception {
        start(true, PreviewTamper.NONE, SbiStreamConfig.SignedPreview.IF_SUPPORTED, null);
        assertThat(source.capabilities().signsFrames()).isTrue();
        List<FaceFrame> fs = frames(10);
        assertThat(signed(fs)).isEqualTo(10);
    }

    @Test
    void pinnedKeyMatchingTheDeviceVerifies() throws Exception {
        server = MockSbiServer.builder(Scenarios.bundled("live-pass")).port(0).fps(20).signedPreview(true).build().start();
        String key = server.devicePublicKey();
        server.close();
        // a fresh server has a fresh key: pinning the old one must reject everything
        start(true, PreviewTamper.NONE, SbiStreamConfig.SignedPreview.REQUIRED, key);
        assertThat(source.capabilities().signsFrames()).isTrue();
        assertThat(signed(frames(6))).isZero();
        source.stop();
        source = new SbiStreamFrameSource(SbiStreamConfig.builder().endpoint(SbiEndpoint.local(server.port()))
                .signedPreview(SbiStreamConfig.SignedPreview.REQUIRED).trustedDeviceKey(server.devicePublicKey()).build());
        assertThat(signed(frames(6))).isEqualTo(6);
    }

    @ParameterizedTest
    @EnumSource(value = PreviewTamper.class, names = {"MODIFY_FRAME", "STRIP_SIGNATURE", "IMPOSTOR_DEVICE"})
    void tamperedStreamsCarryNoValidSignature(PreviewTamper tamper) throws Exception {
        start(true, tamper, SbiStreamConfig.SignedPreview.IF_SUPPORTED, null);
        assertThat(source.capabilities().signsFrames()).isTrue();
        assertThat(signed(frames(8))).as(tamper.name()).isZero();
    }

    @Test
    void replayedFramesAreRejectedButOriginalsVerify() throws Exception {
        start(true, PreviewTamper.REPLAY_FRAME, SbiStreamConfig.SignedPreview.IF_SUPPORTED, null);
        List<FaceFrame> fs = frames(10);
        // each frame arrives twice with the same sequence number: originals verify, copies do not
        String pattern = fs.stream().map(f -> f.deviceSignature().isPresent() ? "S" : "-").collect(Collectors.joining());
        assertThat(pattern).isEqualTo("S-S-S-S-S-");
    }

    @Test
    void requiredButUnsupportedMarksTheStreamAsSigningSoEveryFrameCountsAsInjected() throws Exception {
        start(false, PreviewTamper.NONE, SbiStreamConfig.SignedPreview.REQUIRED, null);
        assertThat(source.capabilities().signsFrames()).isTrue();
        assertThat(signed(frames(5))).isZero();
    }

    @Test
    void offOrUnsupportedBehavesLikeSbi095() throws Exception {
        start(false, PreviewTamper.NONE, SbiStreamConfig.SignedPreview.IF_SUPPORTED, null);
        assertThat(source.capabilities().signsFrames()).isFalse();
        assertThat(signed(frames(5))).isZero();
    }

    @Test
    void verifierRejectsGarbageInitAndKeys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        SignedPreviewVerifier v = new SignedPreviewVerifier(Base64.getEncoder().encodeToString(g.generateKeyPair().getPublic().getEncoded()));
        assertThat(v.requestFields().path("clientNonce").asText()).isNotEmpty();
        assertThat(v.acceptInit("not json".getBytes())).isFalse();
        assertThat(v.acceptInit("{\"alg\":\"vivid-sp-v1\",\"sessionId\":\"x\",\"clientNonce\":\"wrong\"}".getBytes())).isFalse();
        assertThat(v.established()).isFalse();
        assertThat(v.verify(java.util.Map.of(), new byte[3])).isEmpty();
        assertThat(v.rejected()).isEqualTo(1);
    }
}
