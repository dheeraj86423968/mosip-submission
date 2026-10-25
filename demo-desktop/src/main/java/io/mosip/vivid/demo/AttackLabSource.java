package io.mosip.vivid.demo;

import io.mosip.vivid.api.Challenge;
import io.mosip.vivid.device.DeviceCapabilities;
import io.mosip.vivid.device.DeviceHealth;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.FaceFrameSource;
import io.mosip.vivid.device.FrameSink;
import io.mosip.vivid.device.StreamOptions;
import io.mosip.vivid.device.mock.MockFaceFrameSource;
import io.mosip.vivid.device.mock.Pacing;
import io.mosip.vivid.device.mock.scenario.Scenarios;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The attack lab's camera: one {@link FaceFrameSource} whose subject can be switched while a session runs.
 * <ul>
 *   <li>Live person and injected stream: {@link ResponsiveSubjectSource}, which answers prompts.</li>
 *   <li>Printed photo, video replay and screen replay: device-mock's own {@link MockFaceFrameSource} playing the
 *       bundled {@code print-attack}, {@code video-replay} and {@code screen-replay} scenarios.</li>
 * </ul>
 * Switching stops the current inner source and starts the next one. Frames are renumbered onto one continuous
 * timeline, the {@link DemoClock} is advanced to each frame before the session sees it, and lifecycle noise from the
 * replaced source (its "stopped" health and end-of-stream) is swallowed so the session sees one uninterrupted device.
 * After each frame has been fed, {@code afterFrame} receives it so the UI and the recorder can observe the result.
 */
public final class AttackLabSource implements FaceFrameSource {
    private static final Logger LOG = LoggerFactory.getLogger(AttackLabSource.class);
    private static final long FRAME_NANOS = Math.round(1e9 / ResponsiveSubjectSource.FPS);

    private final Supplier<Optional<Challenge>> challengeProbe;
    private final Pacing pacing;
    private final DemoClock clock;
    private final Consumer<FaceFrame> afterFrame;
    private final Object lock = new Object();

    private volatile Subject subject;
    private FrameSink downstream;            // guarded by lock
    private FaceFrameSource inner;           // guarded by lock
    private Relay relay;                     // guarded by lock
    private long lastOutNanos = -FRAME_NANOS;
    private long nextSeq;
    private volatile DeviceHealth health = DeviceHealth.disconnected("not started");

    public AttackLabSource(Subject initial, Supplier<Optional<Challenge>> challengeProbe, Pacing pacing, DemoClock clock, Consumer<FaceFrame> afterFrame) {
        this.subject = Objects.requireNonNull(initial);
        this.challengeProbe = Objects.requireNonNull(challengeProbe);
        this.pacing = Objects.requireNonNull(pacing);
        this.clock = Objects.requireNonNull(clock);
        this.afterFrame = afterFrame == null ? f -> { } : afterFrame;
    }

    public Subject subject() { return subject; }

    @Override public String adapterId() { return "attack-lab"; }
    @Override public DeviceHealth health() { return health; }
    @Override public DeviceCapabilities capabilities() {
        synchronized (lock) { return inner != null ? inner.capabilities() : newInner(subject).capabilities(); }
    }

    @Override public void start(FrameSink sink, StreamOptions options) {
        synchronized (lock) {
            if (downstream != null) return;
            downstream = Objects.requireNonNull(sink);
            launch(subject, true);
        }
    }

    /** Replaces what is in front of the camera. Safe from any thread, including the frame-delivery thread. */
    public void switchTo(Subject next) {
        FaceFrameSource old;
        synchronized (lock) {
            if (next == subject && inner != null) return;
            LOG.info("attack lab: subject {} -> {}", subject, next);
            subject = next;
            if (downstream == null) return;
            old = inner;
            relay.active = false;
            launch(next, false);
        }
        if (old != null) stopQuietly(old);
    }

    @Override public void stop() {
        FaceFrameSource old;
        synchronized (lock) {
            old = inner;
            inner = null;
            if (relay != null) relay.active = false;
            downstream = null;
        }
        if (old != null) stopQuietly(old);
        health = DeviceHealth.disconnected("stopped");
    }

    private void launch(Subject s, boolean first) {
        inner = newInner(s);
        relay = new Relay(first);
        inner.start(relay, StreamOptions.defaults());
    }

    private FaceFrameSource newInner(Subject s) {
        if (s.mockScenario() == null) return new ResponsiveSubjectSource(challengeProbe, pacing, "attack-lab-" + s.key());
        return MockFaceFrameSource.builder(Scenarios.bundled(s.mockScenario())).pacing(pacing).build();
    }

    private static void stopQuietly(FaceFrameSource s) {
        try { s.stop(); } catch (RuntimeException e) { LOG.debug("inner stop: {}", e.toString()); }
    }

    /** Sink handed to one inner source; it goes quiet once that source is replaced. */
    private final class Relay implements FrameSink {
        private final boolean first;
        private volatile boolean active = true;
        private long innerOrigin = Long.MIN_VALUE;
        private long outOrigin;

        Relay(boolean first) { this.first = first; }

        @Override public void onFrame(FaceFrame f) {
            FrameSink out;
            FaceFrame mapped;
            synchronized (lock) {
                if (!active || downstream == null) return;
                if (innerOrigin == Long.MIN_VALUE) { innerOrigin = f.timestampNanos(); outOrigin = lastOutNanos + FRAME_NANOS; }
                long ts = Math.max(lastOutNanos + 1, outOrigin + (f.timestampNanos() - innerOrigin));
                lastOutNanos = ts;
                mapped = FaceFrame.builder().sequence(nextSeq++).timestampNanos(ts).width(f.width()).height(f.height())
                        .format(f.format()).pixels(f.pixels()).build();
                out = downstream;
            }
            clock.advanceTo(mapped.timestampNanos());
            out.onFrame(mapped);
            afterFrame.accept(mapped);
        }

        @Override public void onHealth(DeviceHealth h) {
            FrameSink out;
            synchronized (lock) {
                if (!active || downstream == null) return;
                // a replacement source's connect handshake is not news to the session; its faults are
                if (!first && (h.state() == DeviceHealth.State.CONNECTING || h.state() == DeviceHealth.State.CONNECTED)) return;
                out = downstream;
            }
            health = h;
            out.onHealth(h);
        }

        @Override public void onEnd() {
            FrameSink out;
            synchronized (lock) {
                if (!active || downstream == null) return;
                out = downstream;
            }
            out.onEnd();
        }
    }
}
