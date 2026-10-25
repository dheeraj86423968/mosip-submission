package io.mosip.vivid.device.mock;

import io.mosip.vivid.device.DeviceCapabilities;
import io.mosip.vivid.device.DeviceHealth;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.FaceFrameSource;
import io.mosip.vivid.device.FrameSink;
import io.mosip.vivid.device.PixelFormat;
import io.mosip.vivid.device.StreamOptions;
import io.mosip.vivid.device.mock.scenario.Fault;
import io.mosip.vivid.device.mock.scenario.FaultKind;
import io.mosip.vivid.device.mock.scenario.PresentationLabel;
import io.mosip.vivid.device.mock.scenario.Scenario;
import io.mosip.vivid.device.mock.scenario.ScenarioTimeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-process mock device: replays a {@link Scenario} as a {@link FaceFrameSource}. Frames are rendered on a
 * dedicated delivery thread, encoded as RGB888 (default) or MJPEG, and pushed to the sink at the scenario's
 * fps according to the chosen {@link Pacing}. Scenario faults (and faults injected via {@link #inject})
 * become {@link DeviceHealth} transitions exactly as a real adapter would report them.
 *
 * <p>Thread-safety: {@code start}, {@code stop} and {@code inject} may be called from any thread.
 * {@code onEnd} is delivered exactly once per successful {@code start}, after the delivery thread exits.
 *
 * <pre>{@code
 * FaceFrameSource src = MockFaceFrameSource.builder(Scenarios.bundled("live-pass")).pacing(Pacing.unthrottled()).build();
 * src.start(frame -> session.feed(frame), StreamOptions.defaults());
 * }</pre>
 */
public final class MockFaceFrameSource implements FaceFrameSource {
    private static final Logger LOG = LoggerFactory.getLogger(MockFaceFrameSource.class);
    /** Adapter identifier reported by {@link #adapterId()}. */
    public static final String ADAPTER_ID = "mock";

    private final Scenario scenario;
    private final ScenarioTimeline timeline;
    private final PixelFormat format;
    private final Pacing pacing;
    private final float jpegQuality;
    private final DeviceCapabilities capabilities;

    private final AtomicReference<DeviceHealth> health = new AtomicReference<>(DeviceHealth.disconnected("not started"));
    private final ConcurrentLinkedQueue<Fault> injected = new ConcurrentLinkedQueue<>();
    private final AtomicLong framesDelivered = new AtomicLong();
    private final AtomicReference<PresentationLabel> currentLabel = new AtomicReference<>(PresentationLabel.NONE);
    private final Object lifecycle = new Object();
    private Delivery delivery;   // guarded by lifecycle

    private MockFaceFrameSource(Builder b) {
        scenario = Objects.requireNonNull(b.scenario, "scenario");
        timeline = new ScenarioTimeline(scenario);
        format = b.format; pacing = b.pacing; jpegQuality = b.jpegQuality;
        capabilities = DeviceCapabilities.builder()
                .vendor(b.vendor).model(b.model).deviceId(b.deviceId)
                .sbiSpecVersion(b.specVersion).certificationLevel(b.certification)
                .resolution(scenario.width(), scenario.height()).nominalFps(scenario.maxFps())
                .formats(Set.of(format)).signsFrames(false)
                .hasDepth(scenario.sensors().map(s -> s.depth()).orElse(false))
                .hasIr(scenario.sensors().map(s -> s.ir()).orElse(false)).build();
    }

    public static Builder builder(Scenario scenario) { return new Builder(scenario); }

    @Override public String adapterId() { return ADAPTER_ID; }
    @Override public DeviceCapabilities capabilities() { return capabilities; }
    @Override public DeviceHealth health() { return health.get(); }

    public Scenario scenario() { return scenario; }
    public PixelFormat format() { return format; }
    /** Total frames delivered since construction (all starts). */
    public long framesDelivered() { return framesDelivered.get(); }
    /** Ground-truth label of the segment whose frame was delivered most recently. */
    public PresentationLabel currentLabel() { return currentLabel.get(); }

    /** Queue a fault to be applied before the next frame, regardless of scenario time. Ignored when not running. */
    public void inject(Fault fault) { injected.add(Objects.requireNonNull(fault)); }

    @Override public void start(FrameSink sink, StreamOptions options) {
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(options, "options");
        synchronized (lifecycle) {
            if (delivery != null && delivery.isAlive()) {
                LOG.warn("start() called while already running; ignored");
                return;
            }
            injected.clear();
            delivery = new Delivery(sink);
            setHealth(sink, DeviceHealth.connecting());
            delivery.start();
        }
    }

    @Override public void stop() {
        Delivery d;
        synchronized (lifecycle) { d = delivery; delivery = null; }
        if (d == null) return;
        d.stopRequested.set(true);
        d.interrupt();
        if (Thread.currentThread() != d) {
            try { d.join(10_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private void setHealth(FrameSink sink, DeviceHealth h) {
        DeviceHealth prev = health.getAndSet(h);
        if (prev.state() != h.state() || !prev.reason().equals(h.reason())) {
            LOG.debug("mock health {} -> {}", prev, h);
            try { sink.onHealth(h); } catch (RuntimeException e) { LOG.warn("sink.onHealth threw", e); }
        }
    }

    /** The delivery thread: walks scenario time, applies faults, renders and pushes frames. */
    private final class Delivery extends Thread {
        private final FrameSink sink;
        private final AtomicBoolean stopRequested = new AtomicBoolean();
        private final AtomicBoolean ended = new AtomicBoolean();
        private final long startNanos = System.nanoTime();
        private long sequence;
        private boolean disconnected;
        private long stallUntilMs = -1;
        private int corruptRemaining;
        private double corruptFraction = 0.5;

        Delivery(FrameSink sink) { super("mock-face-source-" + scenario.name()); this.sink = sink; setDaemon(true); }

        @Override public void run() {
            DeviceHealth finalHealth = DeviceHealth.disconnected("scenario ended");
            try {
                setHealth(sink, DeviceHealth.connected());
                long prevMs = -1;
                while (!stopRequested.get()) {
                    long nextMs = timeline.nextFrameTimeAtOrAfter(prevMs + 1);
                    if (nextMs < 0) break;
                    // faults scheduled before this frame are applied in time order, pausing at each one
                    List<Fault> due = new ArrayList<>();
                    for (ScenarioTimeline.FaultOccurrence fo : timeline.faultsBetween(prevMs, nextMs)) {
                        sleepUntil(fo.atScenarioMs());
                        if (!applyFault(fo.fault(), fo.atScenarioMs())) { finalHealth = health.get(); return; }
                    }
                    Fault f;
                    while ((f = injected.poll()) != null) due.add(f);
                    for (Fault inj : due) if (!applyFault(inj, nextMs)) { finalHealth = health.get(); return; }
                    sleepUntil(nextMs);
                    if (stopRequested.get()) break;
                    if (!disconnected && nextMs >= stallUntilMs) deliver(nextMs);
                    prevMs = nextMs;
                }
                if (stopRequested.get()) finalHealth = DeviceHealth.disconnected("stopped");
            } catch (InterruptedException e) {
                finalHealth = DeviceHealth.disconnected("stopped");
            } catch (RuntimeException e) {
                LOG.error("mock delivery failed", e);
                finalHealth = DeviceHealth.failed("delivery error: " + e.getMessage());
            } finally {
                setHealth(sink, finalHealth);
                if (ended.compareAndSet(false, true)) {
                    try { sink.onEnd(); } catch (RuntimeException e) { LOG.warn("sink.onEnd threw", e); }
                }
            }
        }

        /** Returns false when the fault ends the session. */
        private boolean applyFault(Fault fault, long atMs) throws InterruptedException {
            LOG.info("mock fault {} at {} ms", fault, atMs);
            switch (fault.kind()) {
                case DISCONNECT -> {
                    disconnected = true;
                    boolean permanent = fault.boolParam("permanent", false);
                    setHealth(sink, DeviceHealth.disconnected(permanent ? "device removed" : "device disconnected"));
                    if (permanent) { health.set(DeviceHealth.failed("device removed")); return false; }
                    if (!reconnectScheduledAfter(atMs)) {
                        LOG.info("no RECONNECT scheduled after {} ms; ending delivery", atMs);
                        return false;
                    }
                }
                case RECONNECT -> {
                    if (disconnected) {
                        setHealth(sink, DeviceHealth.reconnecting("device reattached"));
                        disconnected = false;
                        setHealth(sink, DeviceHealth.connected());
                    }
                }
                case STALL -> {
                    long d = fault.longParam("durationMs", 2000);
                    stallUntilMs = atMs + d;
                    if (!disconnected) setHealth(sink, DeviceHealth.degraded("no frames for " + d + " ms"));
                    sleepUntil(stallUntilMs);
                    if (!disconnected) setHealth(sink, DeviceHealth.connected());
                }
                case CORRUPT_FRAME -> {
                    corruptRemaining += (int) fault.longParam("count", 1);
                    corruptFraction = fault.doubleParam("fraction", 0.5);
                }
                case LATENCY_SPIKE -> {
                    long d = fault.longParam("delayMs", 500);
                    long wall = pacing.wallMillis(d);
                    if (wall > 0) Thread.sleep(wall);
                }
            }
            return true;
        }

        /** True if a scenario RECONNECT fault (or an injected one, which we cannot foresee) may still come. */
        private boolean reconnectScheduledAfter(long atMs) {
            long horizon = scenario.loop() ? atMs + timeline.passDurationMs() : timeline.passDurationMs();
            for (ScenarioTimeline.FaultOccurrence fo : timeline.faultsBetween(atMs, horizon)) {
                if (fo.fault().kind() == FaultKind.RECONNECT) return true;
            }
            return !injected.isEmpty();
        }

        private void deliver(long scenarioMs) {
            Optional<ScenarioTimeline.Position> pos = timeline.positionAt(scenarioMs);
            if (pos.isEmpty()) return;
            BufferedImage img = timeline.render(pos.get());
            byte[] bytes = FrameEncoder.encode(img, format, jpegQuality);
            if (corruptRemaining > 0) {
                corruptRemaining--;
                int keep = Math.max(1, (int) (bytes.length * corruptFraction));
                bytes = java.util.Arrays.copyOf(bytes, keep);
                LOG.debug("delivering corrupted frame ({} of {} bytes)", keep, bytes.length);
            }
            var planes = timeline.renderPlanes(pos.get(), img).orElse(null);   // S8: L1 depth / IR, when the scenario has sensors
            FaceFrame frame = FrameEncoder.frame(sequence++, startNanos + scenarioMs * 1_000_000L,
                    scenario.width(), scenario.height(), format, bytes,
                    planes == null ? null : planes.depthBuffer(), planes == null ? null : planes.irBuffer());
            currentLabel.set(pos.get().segment().label());
            framesDelivered.incrementAndGet();
            try { sink.onFrame(frame); } catch (RuntimeException e) { LOG.warn("sink.onFrame threw", e); }
        }

        /** Sleep (per pacing) until scenario time reaches {@code targetMs}. */
        private void sleepUntil(long targetMs) throws InterruptedException {
            if (pacing.isUnthrottled()) return;
            long targetWallNanos = startNanos + pacing.wallMillis(targetMs) * 1_000_000L;
            long waitMs = (targetWallNanos - System.nanoTime()) / 1_000_000L;
            if (waitMs > 0) Thread.sleep(waitMs);
        }
    }

    /** Builder for {@link MockFaceFrameSource}. */
    public static final class Builder {
        private final Scenario scenario;
        private PixelFormat format = PixelFormat.RGB888;
        private Pacing pacing = Pacing.realTime();
        private float jpegQuality = 0.85f;
        private String vendor = "MOSIP Vivid", model = "mock-face-l0", deviceId = "MOCK-FACE-0001", specVersion = "0.9.5";
        private DeviceCapabilities.CertificationLevel certification = DeviceCapabilities.CertificationLevel.L0;

        private Builder(Scenario scenario) { this.scenario = scenario; }
        /** RGB888 (default) or MJPEG. */
        public Builder format(PixelFormat v) {
            if (v != PixelFormat.RGB888 && v != PixelFormat.MJPEG) throw new IllegalArgumentException("mock supports RGB888 or MJPEG");
            format = v; return this;
        }
        public Builder pacing(Pacing v) { pacing = Objects.requireNonNull(v); return this; }
        public Builder jpegQuality(float v) { jpegQuality = v; return this; }
        public Builder vendor(String v) { vendor = v; return this; }
        public Builder model(String v) { model = v; return this; }
        public Builder deviceId(String v) { deviceId = v; return this; }
        public Builder specVersion(String v) { specVersion = v; return this; }
        public Builder certification(DeviceCapabilities.CertificationLevel v) { certification = v; return this; }
        public MockFaceFrameSource build() { return new MockFaceFrameSource(this); }
    }
}
