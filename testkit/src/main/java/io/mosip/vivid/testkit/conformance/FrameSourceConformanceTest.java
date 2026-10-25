package io.mosip.vivid.testkit.conformance;

import io.mosip.vivid.device.DeviceCapabilities;
import io.mosip.vivid.device.DeviceHealth;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.FaceFrameSource;
import io.mosip.vivid.device.FrameSink;
import io.mosip.vivid.device.StreamOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test for any {@link FaceFrameSource}. Extend it, implement {@link #createSource()} and run with
 * JUnit 5; every adapter (SBI stream, Android camera, vendor SDK, mock) must pass unchanged. It proves:
 * <ol>
 *   <li>{@code capabilities()} is non-null, declares at least one format, and is consistent with delivered
 *       frames: every frame's format is declared and, once the source reports a resolution, frames match it;</li>
 *   <li>frames carry strictly increasing sequence numbers and non-decreasing timestamps;</li>
 *   <li>{@code start}/{@code stop} are idempotent; {@code onEnd} fires exactly once per start;</li>
 *   <li>health transitions follow the allowed state graph and the source is not usable after stop;</li>
 *   <li>stopping while frames are being delivered neither throws nor delivers after {@code onEnd}.</li>
 * </ol>
 * Override the protected hooks to adapt timeouts for slow devices. The source must be able to deliver at
 * least {@link #minFrames()} frames within {@link #frameTimeoutMillis()} of {@code start}.
 */
public abstract class FrameSourceConformanceTest {
    /** Allowed health transitions; any other is a conformance failure. */
    private static final Map<DeviceHealth.State, EnumSet<DeviceHealth.State>> ALLOWED = new EnumMap<>(DeviceHealth.State.class);
    static {
        ALLOWED.put(DeviceHealth.State.CONNECTING, EnumSet.of(DeviceHealth.State.CONNECTED, DeviceHealth.State.RECONNECTING, DeviceHealth.State.DISCONNECTED, DeviceHealth.State.FAILED));
        ALLOWED.put(DeviceHealth.State.CONNECTED, EnumSet.of(DeviceHealth.State.DEGRADED, DeviceHealth.State.RECONNECTING, DeviceHealth.State.DISCONNECTED, DeviceHealth.State.FAILED));
        ALLOWED.put(DeviceHealth.State.DEGRADED, EnumSet.of(DeviceHealth.State.CONNECTED, DeviceHealth.State.RECONNECTING, DeviceHealth.State.DISCONNECTED, DeviceHealth.State.FAILED));
        ALLOWED.put(DeviceHealth.State.RECONNECTING, EnumSet.of(DeviceHealth.State.CONNECTED, DeviceHealth.State.RECONNECTING, DeviceHealth.State.DISCONNECTED, DeviceHealth.State.FAILED));
        ALLOWED.put(DeviceHealth.State.DISCONNECTED, EnumSet.of(DeviceHealth.State.CONNECTING, DeviceHealth.State.RECONNECTING, DeviceHealth.State.FAILED));
        ALLOWED.put(DeviceHealth.State.FAILED, EnumSet.noneOf(DeviceHealth.State.class));
    }

    private FaceFrameSource current;

    /** A fresh, not-yet-started source. Called once per test. */
    protected abstract FaceFrameSource createSource();

    /** Options passed to {@code start}. */
    protected StreamOptions streamOptions() { return StreamOptions.defaults(); }

    /** How many frames the source must deliver for the frame-based checks. */
    protected int minFrames() { return 5; }

    /** How long to wait for {@link #minFrames()} frames. */
    protected long frameTimeoutMillis() { return 15_000; }

    /** How long {@code onEnd} may take after {@code stop()}. */
    protected long endTimeoutMillis() { return 10_000; }

    @AfterEach
    void closeSource() { if (current != null) { try { current.close(); } catch (RuntimeException ignored) { } current = null; } }

    private FaceFrameSource source() { current = createSource(); assertNotNull(current, "createSource() returned null"); return current; }

    /** Records sink callbacks and detects calls after onEnd. */
    protected static final class RecordingSink implements FrameSink {
        public final List<FaceFrame> frames = new CopyOnWriteArrayList<>();
        public final List<DeviceHealth> health = new CopyOnWriteArrayList<>();
        public final AtomicInteger ends = new AtomicInteger();
        public final AtomicBoolean afterEnd = new AtomicBoolean();
        public final CountDownLatch ended = new CountDownLatch(1);
        private final CountDownLatch enough;
        private final long sleepPerFrameMillis;
        public RecordingSink(int wanted) { this(wanted, 0); }
        public RecordingSink(int wanted, long sleepPerFrameMillis) { enough = new CountDownLatch(wanted); this.sleepPerFrameMillis = sleepPerFrameMillis; }
        @Override public void onFrame(FaceFrame f) {
            if (ends.get() > 0) afterEnd.set(true);
            frames.add(f); enough.countDown();
            if (sleepPerFrameMillis > 0) try { Thread.sleep(sleepPerFrameMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        @Override public void onHealth(DeviceHealth h) { if (ends.get() > 0) afterEnd.set(true); health.add(h); }
        @Override public void onEnd() { ends.incrementAndGet(); ended.countDown(); }
        public boolean awaitFrames(long ms) throws InterruptedException { return enough.await(ms, TimeUnit.MILLISECONDS); }
        public boolean awaitEnd(long ms) throws InterruptedException { return ended.await(ms, TimeUnit.MILLISECONDS); }
    }

    @Test
    public void capabilitiesAreNonNullAndConsistentWithFrames() throws Exception {
        FaceFrameSource src = source();
        assertNotNull(src.adapterId(), "adapterId");
        assertFalse(src.adapterId().isBlank(), "adapterId blank");
        DeviceCapabilities caps = src.capabilities();
        assertNotNull(caps, "capabilities() returned null");
        assertFalse(caps.formats().isEmpty(), "capabilities must declare at least one pixel format");
        assertTrue(caps.nominalFps() >= 0, "nominalFps negative");
        RecordingSink sink = new RecordingSink(minFrames());
        src.start(sink, streamOptions());
        assertTrue(sink.awaitFrames(frameTimeoutMillis()), "expected " + minFrames() + " frames within " + frameTimeoutMillis() + " ms, got " + sink.frames.size());
        DeviceCapabilities after = src.capabilities();   // sources may refine resolution once streaming
        assertEquals(caps.formats(), after.formats(), "declared formats changed while streaming");
        for (FaceFrame f : sink.frames) {
            assertTrue(after.formats().contains(f.format()), "frame format " + f.format() + " not in declared " + after.formats());
            assertTrue(f.width() > 0 && f.height() > 0, "frame has non-positive dimensions");
            if (after.width() > 0 && after.height() > 0) {
                assertEquals(after.width(), f.width(), "frame width differs from capabilities");
                assertEquals(after.height(), f.height(), "frame height differs from capabilities");
            }
            assertTrue(f.pixels().remaining() > 0, "frame has no pixel data");
        }
        src.stop();
    }

    @Test
    public void framesHaveMonotonicSequenceAndTimestamps() throws Exception {
        FaceFrameSource src = source();
        RecordingSink sink = new RecordingSink(minFrames());
        src.start(sink, streamOptions());
        assertTrue(sink.awaitFrames(frameTimeoutMillis()), "not enough frames");
        src.stop();
        List<FaceFrame> frames = List.copyOf(sink.frames);
        for (int i = 1; i < frames.size(); i++) {
            assertTrue(frames.get(i).sequence() > frames.get(i - 1).sequence(), "sequence not strictly increasing at index " + i);
            assertTrue(frames.get(i).timestampNanos() >= frames.get(i - 1).timestampNanos(), "timestamp decreased at index " + i);
        }
    }

    @Test
    public void startAndStopAreIdempotentAndOnEndFiresOncePerStart() throws Exception {
        FaceFrameSource src = source();
        RecordingSink sink = new RecordingSink(1);
        src.start(sink, streamOptions());
        assertDoesNotThrow(() -> src.start(sink, streamOptions()), "second start() must not throw");
        assertTrue(sink.awaitFrames(frameTimeoutMillis()), "no frame after start");
        src.stop();
        assertTrue(sink.awaitEnd(endTimeoutMillis()), "onEnd not called after stop");
        assertDoesNotThrow(src::stop, "second stop() must not throw");
        assertEquals(1, sink.ends.get(), "onEnd must fire exactly once per start");
        // a second start/stop cycle gets its own single onEnd
        RecordingSink sink2 = new RecordingSink(1);
        src.start(sink2, streamOptions());
        assertTrue(sink2.awaitFrames(frameTimeoutMillis()), "no frame after restart");
        src.stop();
        assertTrue(sink2.awaitEnd(endTimeoutMillis()), "onEnd not called after second stop");
        assertEquals(1, sink2.ends.get());
        assertEquals(1, sink.ends.get(), "first sink must not receive the second session's onEnd");
    }

    @Test
    public void healthTransitionsAreSane() throws Exception {
        FaceFrameSource src = source();
        RecordingSink sink = new RecordingSink(minFrames());
        src.start(sink, streamOptions());
        assertTrue(sink.awaitFrames(frameTimeoutMillis()), "not enough frames");
        assertTrue(src.health().isUsable(), "health must be usable while frames flow, was " + src.health());
        src.stop();
        assertTrue(sink.awaitEnd(endTimeoutMillis()));
        DeviceHealth.State last = src.health().state();
        assertTrue(last == DeviceHealth.State.DISCONNECTED || last == DeviceHealth.State.FAILED, "after stop health must be DISCONNECTED or FAILED, was " + src.health());
        List<DeviceHealth> h = List.copyOf(sink.health);
        assertFalse(h.isEmpty(), "source never reported health");
        DeviceHealth.State first = h.get(0).state();
        assertTrue(first == DeviceHealth.State.CONNECTING || first == DeviceHealth.State.CONNECTED, "first health must be CONNECTING or CONNECTED, was " + first);
        for (int i = 1; i < h.size(); i++) {
            DeviceHealth.State from = h.get(i - 1).state(), to = h.get(i).state();
            if (from == to) continue;
            assertTrue(ALLOWED.get(from).contains(to), "illegal health transition " + from + " -> " + to + " in " + h);
        }
        for (DeviceHealth d : h) {
            if (d.state() != DeviceHealth.State.CONNECTED && d.state() != DeviceHealth.State.CONNECTING) assertNotNull(d.reason(), "non-connected health needs a reason");
        }
    }

    @Test
    public void stopWhileDeliveringDoesNotThrowOrDeliverAfterEnd() throws Exception {
        FaceFrameSource src = source();
        RecordingSink sink = new RecordingSink(2, 30);
        src.start(sink, streamOptions());
        assertTrue(sink.awaitFrames(frameTimeoutMillis()), "not enough frames");
        assertDoesNotThrow(src::stop, "stop() during delivery threw");
        assertTrue(sink.awaitEnd(endTimeoutMillis()), "onEnd not called");
        Thread.sleep(100);
        assertFalse(sink.afterEnd.get(), "sink received callbacks after onEnd");
        assertEquals(1, sink.ends.get());
        assertFalse(src.health().isUsable(), "health still usable after stop: " + src.health());
    }

    @Test
    public void closeBehavesLikeStop() throws Exception {
        FaceFrameSource src = source();
        RecordingSink sink = new RecordingSink(1);
        src.start(sink, streamOptions());
        assertTrue(sink.awaitFrames(frameTimeoutMillis()));
        assertDoesNotThrow(src::close);
        assertTrue(sink.awaitEnd(endTimeoutMillis()));
        assertEquals(1, sink.ends.get());
    }
}
