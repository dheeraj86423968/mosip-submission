package io.mosip.vivid.core.bridge;

import io.mosip.vivid.api.FailureReason;
import io.mosip.vivid.api.SessionState.Phase;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.core.session.LivenessOrchestrator;
import io.mosip.vivid.core.testing.FakeFrameAnalyzer;
import io.mosip.vivid.core.testing.FakeTime;
import io.mosip.vivid.core.testing.Frames;
import io.mosip.vivid.core.testing.Scenarios;
import io.mosip.vivid.device.DeviceCapabilities;
import io.mosip.vivid.device.DeviceException;
import io.mosip.vivid.device.DeviceHealth;
import io.mosip.vivid.device.FaceFrameSource;
import io.mosip.vivid.device.FrameSink;
import io.mosip.vivid.device.StreamOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class FrameSourceBridgeTest {

    /** Source that hands the sink to the test so frames are delivered synchronously from the test thread. */
    static final class ScriptedSource implements FaceFrameSource {
        FrameSink sink;
        StreamOptions options;
        DeviceHealth health = DeviceHealth.connected();
        RuntimeException startFailure;
        int stops;
        boolean signsFrames;

        @Override public String adapterId() { return "scripted"; }
        @Override public DeviceCapabilities capabilities() { return DeviceCapabilities.builder().signsFrames(signsFrames).build(); }
        @Override public void start(FrameSink s, StreamOptions o) {
            if (startFailure != null) {
                throw startFailure;
            }
            sink = s;
            options = o;
        }
        @Override public synchronized void stop() { stops++; }
        @Override public DeviceHealth health() { return health; }
    }

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final FakeTime time = new FakeTime();
    private final FakeFrameAnalyzer analyzer = new FakeFrameAnalyzer();
    private final ScriptedSource source = new ScriptedSource();

    @AfterEach
    void shutdown() {
        scheduler.shutdownNow();
    }

    private LivenessOrchestrator session(boolean enabled) {
        return LivenessOrchestrator.builder(analyzer, Scenarios.policy(Workflow.RESIDENT).enabled(enabled).build(),
                Scenarios.context(Workflow.RESIDENT, false)).timeSource(time).build();
    }

    private void awaitStops(int n) throws InterruptedException {
        for (int i = 0; i < 100 && source.stops < n; i++) {
            Thread.sleep(10);
        }
    }

    @Test
    void framesFlowToSessionAndSourceStopsOnTerminalState() throws Exception {
        LivenessOrchestrator s = session(true);
        FrameSourceBridge bridge = new FrameSourceBridge(source, s, null, scheduler);
        StreamOptions opts = new StreamOptions(20, 2, 1000, 1);
        bridge.start(opts);
        bridge.start();
        assertThat(source.options).isSameAs(opts);
        assertThat(s.state().phase()).isEqualTo(Phase.FACE_ACQUIRING);
        assertThat(bridge.session()).isSameAs(s);
        analyzer.fallback(Scenarios::live);
        for (int i = 0; i < 10 && !s.state().isTerminal(); i++) {
            time.advance(100);
            source.sink.onFrame(Frames.frame());
        }
        assertThat(s.state().phase()).isEqualTo(Phase.PASSED);
        awaitStops(1);
        assertThat(source.stops).isEqualTo(1);
        source.sink.onFrame(Frames.frame()); // after stop: ignored
        source.sink.onHealth(DeviceHealth.failed("late"));
        source.sink.onEnd();
        bridge.close();
        assertThat(source.stops).isEqualTo(1);
    }

    @Test
    void unsignedFrameFromSigningDeviceFailsAsInjection() {
        source.signsFrames = true;
        LivenessOrchestrator s = session(true);
        FrameSourceBridge bridge = new FrameSourceBridge(source, s, null, scheduler);
        bridge.start();
        analyzer.fallback(Scenarios::live);
        time.advance(100);
        source.sink.onFrame(io.mosip.vivid.device.FaceFrame.builder().sequence(1).timestampNanos(time.nanoTime())
                .width(4).height(4).format(io.mosip.vivid.device.PixelFormat.RGB888).pixels(new byte[48])
                .deviceSignature(new byte[] {1, 2, 3}).build());
        assertThat(s.state().isTerminal()).isFalse();
        assertThat(s.state().failure()).isEmpty();
        time.advance(100);
        source.sink.onFrame(Frames.frame());    // no signature: injected on the preview path
        assertThat(s.state().failure()).contains(FailureReason.PAD_DETECTED);
        io.mosip.vivid.api.LivenessResult r = s.result();
        assertThat(r.decision()).isEqualTo(io.mosip.vivid.api.LivenessResult.Decision.ATTACK);
        assertThat(r.attackClass()).contains("INJECTION");
        bridge.close();
    }

    @Test
    void healthEventsMapToHintsAndFailures() {
        LivenessOrchestrator s = session(true);
        source.health = DeviceHealth.connecting();
        FrameSourceBridge bridge = new FrameSourceBridge(source, s);
        bridge.start();
        assertThat(s.state().phase()).isEqualTo(Phase.DEVICE_CONNECTING);
        source.sink.onHealth(null);
        source.sink.onHealth(DeviceHealth.connecting());
        source.sink.onHealth(DeviceHealth.connected());
        assertThat(s.state().phase()).isEqualTo(Phase.FACE_ACQUIRING);
        source.sink.onHealth(DeviceHealth.degraded("low fps"));
        assertThat(s.state().hint().name()).isEqualTo("DEVICE_RECONNECTING");
        source.sink.onHealth(DeviceHealth.reconnecting("usb"));
        source.sink.onHealth(DeviceHealth.connected());
        assertThat(s.state().hint().name()).isEqualTo("NONE");
        source.sink.onHealth(DeviceHealth.disconnected("unplugged"));
        assertThat(s.state().failure()).contains(FailureReason.DEVICE_CONNECTION_FAILURE);
        assertThat(s.retry()).isTrue();
        source.sink.onHealth(DeviceHealth.failed("dead"));
        assertThat(s.state().failure()).contains(FailureReason.DEVICE_UNAVAILABLE);
        assertThat(s.state().phase()).isEqualTo(Phase.LOCKED);
        bridge.close();
    }

    @Test
    void streamEndFailsLiveAttempt() {
        LivenessOrchestrator s = session(true);
        FrameSourceBridge bridge = new FrameSourceBridge(source, s, StreamOptions.defaults());
        bridge.start();
        source.sink.onEnd();
        assertThat(s.state().failure()).contains(FailureReason.DEVICE_UNAVAILABLE);
        source.sink.onEnd(); // already failed: no second fault
        bridge.close();
    }

    @Test
    void deviceExceptionsOnStartFailTheAttempt() {
        source.startFailure = new DeviceException(DeviceException.Kind.CAMERA_UNAVAILABLE, "busy");
        LivenessOrchestrator s = session(true);
        try (FrameSourceBridge bridge = new FrameSourceBridge(source, s)) {
            bridge.start();
            assertThat(s.state().failure()).contains(FailureReason.CAMERA_UNAVAILABLE);
        }
        ScriptedSource other = new ScriptedSource();
        other.startFailure = new IllegalStateException("driver crash");
        LivenessOrchestrator s2 = session(true);
        try (FrameSourceBridge bridge = new FrameSourceBridge(other, s2)) {
            bridge.start();
            assertThat(s2.state().failure()).contains(FailureReason.DEVICE_UNAVAILABLE);
        }
    }

    @Test
    void tickFiresTimeoutsWithoutFrames() throws Exception {
        LivenessOrchestrator s = session(true);
        FrameSourceBridge bridge = new FrameSourceBridge(source, s, null, scheduler);
        bridge.start();
        time.advance(60_000);
        for (int i = 0; i < 100 && s.state().phase() == Phase.FACE_ACQUIRING; i++) {
            Thread.sleep(20);
        }
        assertThat(s.state().failure()).contains(FailureReason.FACE_NOT_DETECTED);
        bridge.stop();
        bridge.stop();
        assertThat(source.stops).isEqualTo(1);
    }

    @Test
    void disabledPolicyNeverStartsTheSource() throws Exception {
        LivenessOrchestrator s = session(false);
        FrameSourceBridge bridge = new FrameSourceBridge(source, s, null, scheduler);
        bridge.start();
        assertThat(source.sink).isNull();
        assertThat(s.state().phase()).isEqualTo(Phase.PASSED);
        scheduler.shutdown();
        assertThat(scheduler.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void deviceExceptionKindMapping() {
        assertThat(FrameSourceBridge.map(null)).isEqualTo(FailureReason.DEVICE_UNAVAILABLE);
        assertThat(FrameSourceBridge.map(DeviceException.Kind.UNAVAILABLE)).isEqualTo(FailureReason.DEVICE_UNAVAILABLE);
        assertThat(FrameSourceBridge.map(DeviceException.Kind.CONNECTION_FAILED)).isEqualTo(FailureReason.DEVICE_CONNECTION_FAILURE);
        assertThat(FrameSourceBridge.map(DeviceException.Kind.PROTOCOL_ERROR)).isEqualTo(FailureReason.DEVICE_CONNECTION_FAILURE);
        assertThat(FrameSourceBridge.map(DeviceException.Kind.CAMERA_UNAVAILABLE)).isEqualTo(FailureReason.CAMERA_UNAVAILABLE);
        assertThat(FrameSourceBridge.map(DeviceException.Kind.INVALID_FRAME)).isEqualTo(FailureReason.INVALID_FRAME_DATA);
    }
}
