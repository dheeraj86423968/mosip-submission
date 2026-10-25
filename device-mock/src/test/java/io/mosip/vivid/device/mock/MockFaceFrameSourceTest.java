package io.mosip.vivid.device.mock;

import io.mosip.vivid.device.DeviceHealth;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.FrameSink;
import io.mosip.vivid.device.PixelFormat;
import io.mosip.vivid.device.StreamOptions;
import io.mosip.vivid.device.mock.scenario.Fault;
import io.mosip.vivid.device.mock.scenario.FaultKind;
import io.mosip.vivid.device.mock.scenario.Scenario;
import io.mosip.vivid.device.mock.scenario.Scenarios;
import io.mosip.vivid.device.mock.scenario.Segment;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MockFaceFrameSourceTest {

    /** Collects everything the source emits. */
    static final class RecordingSink implements FrameSink {
        final List<FaceFrame> frames = new CopyOnWriteArrayList<>();
        final List<DeviceHealth.State> health = new CopyOnWriteArrayList<>();
        final AtomicInteger ends = new AtomicInteger();
        final CountDownLatch ended = new CountDownLatch(1);
        @Override public void onFrame(FaceFrame f) { frames.add(f); }
        @Override public void onHealth(DeviceHealth h) { health.add(h.state()); }
        @Override public void onEnd() { ends.incrementAndGet(); ended.countDown(); }
    }

    private static Scenario shortScenario(Segment.Builder seg) {
        return Scenario.builder("t").size(160, 120).segment(seg.build()).build();
    }

    @Test
    void deliversAllFramesOfANonLoopingScenarioThenEnds() throws Exception {
        Scenario s = shortScenario(Segment.builder().durationMs(1000).fps(10));
        MockFaceFrameSource src = MockFaceFrameSource.builder(s).pacing(Pacing.unthrottled()).build();
        RecordingSink sink = new RecordingSink();
        src.start(sink, StreamOptions.defaults());
        assertThat(sink.ended.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(sink.frames).hasSize(10);
        for (int i = 1; i < sink.frames.size(); i++) {
            assertThat(sink.frames.get(i).sequence()).isGreaterThan(sink.frames.get(i - 1).sequence());
            assertThat(sink.frames.get(i).timestampNanos() - sink.frames.get(i - 1).timestampNanos()).isEqualTo(100_000_000L);
        }
        FaceFrame f = sink.frames.get(0);
        assertThat(f.format()).isEqualTo(PixelFormat.RGB888);
        assertThat(f.pixels().remaining()).isEqualTo(160 * 120 * 3);
        assertThat(sink.health).containsExactly(DeviceHealth.State.CONNECTING, DeviceHealth.State.CONNECTED, DeviceHealth.State.DISCONNECTED);
        assertThat(sink.ends.get()).isEqualTo(1);
        src.stop(); src.stop();   // idempotent, no second onEnd
        assertThat(sink.ends.get()).isEqualTo(1);
        assertThat(src.capabilities().formats()).containsExactly(PixelFormat.RGB888);
        assertThat(src.capabilities().width()).isEqualTo(160);
    }

    @Test
    void mjpegFramesAreDecodableJpegs() throws Exception {
        Scenario s = shortScenario(Segment.builder().durationMs(200).fps(10));
        MockFaceFrameSource src = MockFaceFrameSource.builder(s).format(PixelFormat.MJPEG).pacing(Pacing.unthrottled()).build();
        RecordingSink sink = new RecordingSink();
        src.start(sink, StreamOptions.defaults());
        assertThat(sink.ended.await(10, TimeUnit.SECONDS)).isTrue();
        byte[] bytes = new byte[sink.frames.get(0).pixels().remaining()];
        sink.frames.get(0).pixels().get(bytes);
        var img = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(img).isNotNull();
        assertThat(img.getWidth()).isEqualTo(160);
    }

    @Test
    void stallAndDisconnectFaultsBecomeHealthTransitions() throws Exception {
        Scenario s = shortScenario(Segment.builder().durationMs(1000).fps(10)
                .fault(new Fault(250, FaultKind.STALL, Map.of("durationMs", 200)))
                .fault(new Fault(600, FaultKind.DISCONNECT, null))
                .fault(new Fault(800, FaultKind.RECONNECT, null)));
        MockFaceFrameSource src = MockFaceFrameSource.builder(s).pacing(Pacing.unthrottled()).build();
        RecordingSink sink = new RecordingSink();
        src.start(sink, StreamOptions.defaults());
        assertThat(sink.ended.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(sink.health).containsExactly(
                DeviceHealth.State.CONNECTING, DeviceHealth.State.CONNECTED,
                DeviceHealth.State.DEGRADED, DeviceHealth.State.CONNECTED,          // stall
                DeviceHealth.State.DISCONNECTED, DeviceHealth.State.RECONNECTING, DeviceHealth.State.CONNECTED, // unplug/replug
                DeviceHealth.State.DISCONNECTED);                                    // scenario end
        // frames at 0..200 (3), stall covers 250..450 so 300,400 are skipped; 500 delivered; 600,700 lost; 800,900 delivered
        assertThat(sink.frames.stream().map(f -> (f.timestampNanos() - sink.frames.get(0).timestampNanos()) / 1_000_000L).toList())
                .containsExactly(0L, 100L, 200L, 500L, 800L, 900L);
    }

    @Test
    void disconnectWithoutReconnectEndsDelivery() throws Exception {
        Scenario s = shortScenario(Segment.builder().durationMs(1000).fps(10).fault(new Fault(300, FaultKind.DISCONNECT, null)));
        MockFaceFrameSource src = MockFaceFrameSource.builder(s).pacing(Pacing.unthrottled()).build();
        RecordingSink sink = new RecordingSink();
        src.start(sink, StreamOptions.defaults());
        assertThat(sink.ended.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(sink.frames).hasSize(3);
        assertThat(src.health().state()).isEqualTo(DeviceHealth.State.DISCONNECTED);
        assertThat(sink.ends.get()).isEqualTo(1);
    }

    @Test
    void corruptFrameTruncatesPixels() throws Exception {
        Scenario s = shortScenario(Segment.builder().durationMs(300).fps(10).fault(new Fault(100, FaultKind.CORRUPT_FRAME, Map.of("count", 1, "fraction", 0.25))));
        MockFaceFrameSource src = MockFaceFrameSource.builder(s).pacing(Pacing.unthrottled()).build();
        RecordingSink sink = new RecordingSink();
        src.start(sink, StreamOptions.defaults());
        assertThat(sink.ended.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(sink.frames).hasSize(3);
        assertThat(sink.frames.get(1).pixels().remaining()).isEqualTo(160 * 120 * 3 / 4);
        assertThat(sink.frames.get(2).pixels().remaining()).isEqualTo(160 * 120 * 3);
    }

    @Test
    void stopDuringRealTimeDeliveryEndsOnce() throws Exception {
        MockFaceFrameSource src = MockFaceFrameSource.builder(Scenarios.bundled("live-pass")).pacing(Pacing.realTime()).build();
        RecordingSink sink = new RecordingSink();
        src.start(sink, StreamOptions.defaults());
        Thread.sleep(400);
        src.stop();
        assertThat(sink.ended.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sink.frames.size()).isBetween(2, 12);
        assertThat(sink.ends.get()).isEqualTo(1);
        assertThat(src.health().state()).isEqualTo(DeviceHealth.State.DISCONNECTED);
        // restart works and ends again exactly once
        RecordingSink sink2 = new RecordingSink();
        src.start(sink2, StreamOptions.defaults());
        src.inject(Fault.of(FaultKind.DISCONNECT, "permanent", true));
        assertThat(sink2.ended.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(src.health().state()).isEqualTo(DeviceHealth.State.FAILED);
    }
}
