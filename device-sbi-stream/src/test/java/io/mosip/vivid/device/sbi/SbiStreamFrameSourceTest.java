package io.mosip.vivid.device.sbi;

import io.mosip.vivid.device.DeviceCapabilities;
import io.mosip.vivid.device.DeviceException;
import io.mosip.vivid.device.DeviceHealth;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.FrameSink;
import io.mosip.vivid.device.PixelFormat;
import io.mosip.vivid.device.StreamOptions;
import io.mosip.vivid.device.mock.sbi.MockSbiServer;
import io.mosip.vivid.device.mock.scenario.Fault;
import io.mosip.vivid.device.mock.scenario.FaultKind;
import io.mosip.vivid.device.mock.scenario.Scenarios;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Integration tests against the mock SBI service from device-mock. */
class SbiStreamFrameSourceTest {
    static final class RecordingSink implements FrameSink {
        final List<FaceFrame> frames = new CopyOnWriteArrayList<>();
        final List<DeviceHealth> health = new CopyOnWriteArrayList<>();
        final AtomicInteger ends = new AtomicInteger();
        final CountDownLatch ended = new CountDownLatch(1);
        @Override public void onFrame(FaceFrame f) { frames.add(f); }
        @Override public void onHealth(DeviceHealth h) { health.add(h); }
        @Override public void onEnd() { ends.incrementAndGet(); ended.countDown(); }
        List<DeviceHealth.State> states() { return health.stream().map(DeviceHealth::state).toList(); }
        boolean awaitFrames(int n, long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            while (frames.size() < n && System.currentTimeMillis() < end) Thread.sleep(20);
            return frames.size() >= n;
        }
        boolean awaitState(DeviceHealth.State s, long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            while (!states().contains(s) && System.currentTimeMillis() < end) Thread.sleep(20);
            return states().contains(s);
        }
    }

    private MockSbiServer server;
    private SbiStreamFrameSource source;

    @AfterEach void tearDown() { if (source != null) source.stop(); if (server != null) server.close(); }

    private MockSbiServer newServer(MockSbiServer.FramingStyle framing) {
        server = MockSbiServer.builder(Scenarios.bundled("live-pass")).port(0).fps(20).framing(framing).build().start();
        return server;
    }

    private static StreamOptions options(long stallMs, int attempts) { return new StreamOptions(15, 4, stallMs, attempts); }

    @Test
    void happyPathDeliversMjpegFramesWithCapabilitiesFromDiscovery() throws Exception {
        newServer(MockSbiServer.FramingStyle.STANDARD);
        source = new SbiStreamFrameSource(SbiStreamConfig.builder().endpoint(SbiEndpoint.local(server.port())).build());
        DeviceCapabilities caps = source.capabilities();
        assertThat(caps.vendor()).isEqualTo("MOSIP Vivid");
        assertThat(caps.model()).isEqualTo("mock-face-l0");
        assertThat(caps.deviceId()).isEqualTo(server.deviceId());
        assertThat(caps.certificationLevel()).isEqualTo(DeviceCapabilities.CertificationLevel.L0);
        assertThat(caps.sbiSpecVersion()).isEqualTo("0.9.5");
        assertThat(caps.signsFrames()).isFalse();
        assertThat(caps.formats()).containsExactly(PixelFormat.MJPEG);
        assertThat(caps.width()).isZero();   // unknown until the first frame

        RecordingSink sink = new RecordingSink();
        source.start(sink, options(3000, 3));
        assertThat(sink.awaitFrames(8, 10_000)).isTrue();
        for (int i = 1; i < sink.frames.size(); i++) {
            assertThat(sink.frames.get(i).sequence()).isGreaterThan(sink.frames.get(i - 1).sequence());
            assertThat(sink.frames.get(i).timestampNanos()).isGreaterThanOrEqualTo(sink.frames.get(i - 1).timestampNanos());
        }
        FaceFrame f = sink.frames.get(0);
        assertThat(f.format()).isEqualTo(PixelFormat.MJPEG);
        assertThat(f.width()).isEqualTo(640);
        assertThat(f.height()).isEqualTo(480);
        assertThat(f.pixels().get(0) & 0xff).isEqualTo(0xFF);
        assertThat(source.capabilities().width()).isEqualTo(640);
        assertThat(source.capabilities().nominalFps()).isBetween(5.0, 60.0);
        assertThat(sink.states()).startsWith(DeviceHealth.State.CONNECTING, DeviceHealth.State.CONNECTED);
        source.stop();
        assertThat(sink.ended.await(5, TimeUnit.SECONDS)).isTrue();
        source.stop();
        assertThat(sink.ends.get()).isEqualTo(1);
        assertThat(source.health().state()).isEqualTo(DeviceHealth.State.DISCONNECTED);
        assertThat(sink.health.get(sink.health.size() - 1).reason()).isEqualTo("stopped");
    }

    @Test
    void parsesMockMdsFramingStyle() throws Exception {
        newServer(MockSbiServer.FramingStyle.MOCK_MDS);
        source = new SbiStreamFrameSource(SbiStreamConfig.builder().endpoint(SbiEndpoint.local(server.port())).build());
        RecordingSink sink = new RecordingSink();
        source.start(sink, options(3000, 3));
        assertThat(sink.awaitFrames(5, 10_000)).isTrue();
        assertThat(sink.frames.get(0).width()).isEqualTo(640);
    }

    @Test
    void discoveryScansPortRangeAndSelectsByDeviceId() throws Exception {
        newServer(MockSbiServer.FramingStyle.STANDARD);
        int p = server.port();
        SbiDiscovery disc = new SbiDiscovery(new SbiClient(300, 1000), "127.0.0.1", Math.max(1, p - 3), p + 3, 8);
        List<SbiDeviceInfo> found = disc.discoverFaceDevices();
        assertThat(found).hasSize(1);
        SbiDeviceInfo d = found.get(0);
        assertThat(d.endpoint().port()).isEqualTo(p);
        assertThat(d.isReady()).isTrue();
        assertThat(d.isRegistration()).isTrue();
        assertThat(d.digitalId().isFace()).isTrue();
        assertThat(d.digitalId().serialNo()).isEqualTo("MOCKSN0001");
        assertThat(SbiDiscovery.select(found, "nope", true)).isEmpty();
        assertThat(SbiDiscovery.select(found, server.deviceId(), true)).isPresent();
        // /info decodes the JWT payload
        List<SbiDeviceInfo> info = new SbiClient(300, 1000).info(d.endpoint());
        assertThat(info).hasSize(1);
        assertThat(info.get(0).deviceId()).isEqualTo(server.deviceId());
        assertThat(info.get(0).certification()).isEqualTo("L0");

        source = new SbiStreamFrameSource(SbiStreamConfig.builder().portRange(Math.max(1, p - 3), p + 3).deviceId(server.deviceId()).build());
        assertThat(source.capabilities().deviceId()).isEqualTo(server.deviceId());
    }

    @Test
    void stallIsDetectedThenReconnectsAndRecovers() throws Exception {
        newServer(MockSbiServer.FramingStyle.STANDARD);
        source = new SbiStreamFrameSource(SbiStreamConfig.builder().endpoint(SbiEndpoint.local(server.port())).backoff(50, 200).build());
        RecordingSink sink = new RecordingSink();
        source.start(sink, options(400, 5));
        assertThat(sink.awaitFrames(3, 10_000)).isTrue();
        server.inject(Fault.of(FaultKind.STALL, "durationMs", 1500));
        assertThat(sink.awaitState(DeviceHealth.State.DEGRADED, 5000)).isTrue();
        assertThat(sink.awaitState(DeviceHealth.State.RECONNECTING, 5000)).isTrue();
        int before = sink.frames.size();
        long deadline = System.currentTimeMillis() + 10_000;
        while (sink.frames.size() < before + 5 && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertThat(sink.frames.size()).isGreaterThanOrEqualTo(before + 5);
        List<DeviceHealth.State> st = sink.states();
        int degraded = st.indexOf(DeviceHealth.State.DEGRADED);
        assertThat(st.subList(degraded, st.size())).containsSubsequence(DeviceHealth.State.DEGRADED, DeviceHealth.State.RECONNECTING, DeviceHealth.State.CONNECTED);
        assertThat(source.reconnects()).isGreaterThanOrEqualTo(1);
        assertThat(source.health().state()).isEqualTo(DeviceHealth.State.CONNECTED);
    }

    @Test
    void permanentDisconnectEndsInFailedAfterRetries() throws Exception {
        newServer(MockSbiServer.FramingStyle.STANDARD);
        source = new SbiStreamFrameSource(SbiStreamConfig.builder().endpoint(SbiEndpoint.local(server.port())).backoff(30, 100).build());
        RecordingSink sink = new RecordingSink();
        source.start(sink, options(2000, 2));
        assertThat(sink.awaitFrames(2, 10_000)).isTrue();
        server.inject(new Fault(0, FaultKind.DISCONNECT, Map.of("permanent", true)));
        assertThat(sink.ended.await(15, TimeUnit.SECONDS)).isTrue();
        assertThat(sink.ends.get()).isEqualTo(1);
        assertThat(source.health().state()).isEqualTo(DeviceHealth.State.FAILED);
        assertThat(source.health().reason()).contains("2 reconnect attempts");
        List<DeviceHealth.State> st = sink.states();
        assertThat(st).containsSubsequence(DeviceHealth.State.CONNECTED, DeviceHealth.State.RECONNECTING, DeviceHealth.State.RECONNECTING, DeviceHealth.State.FAILED);
        assertThat(st.stream().filter(s -> s == DeviceHealth.State.RECONNECTING).count()).isEqualTo(2);
        source.stop();   // no second onEnd
        assertThat(sink.ends.get()).isEqualTo(1);
    }

    @Test
    void deviceAbsentIsReportedAsUnavailableAndFails() throws Exception {
        int closedPort;
        try (ServerSocket ss = new ServerSocket(0)) { closedPort = ss.getLocalPort(); }
        source = new SbiStreamFrameSource(SbiStreamConfig.builder().endpoint(SbiEndpoint.local(closedPort)).backoff(20, 50).build());
        assertThatThrownBy(source::capabilities).isInstanceOf(DeviceException.class)
                .satisfies(e -> assertThat(((DeviceException) e).kind()).isEqualTo(DeviceException.Kind.UNAVAILABLE));
        RecordingSink sink = new RecordingSink();
        source.start(sink, options(1000, 1));
        assertThat(sink.ended.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(source.health().state()).isEqualTo(DeviceHealth.State.FAILED);
        assertThat(sink.frames).isEmpty();
        assertThat(sink.states()).containsExactly(DeviceHealth.State.CONNECTING, DeviceHealth.State.RECONNECTING, DeviceHealth.State.FAILED);
    }
}
