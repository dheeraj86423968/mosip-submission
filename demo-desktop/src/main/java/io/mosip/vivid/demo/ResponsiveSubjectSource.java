package io.mosip.vivid.demo;

import io.mosip.vivid.api.Challenge;
import io.mosip.vivid.device.DeviceCapabilities;
import io.mosip.vivid.device.DeviceHealth;
import io.mosip.vivid.device.FaceFrameSource;
import io.mosip.vivid.device.FrameSink;
import io.mosip.vivid.device.PixelFormat;
import io.mosip.vivid.device.StreamOptions;
import io.mosip.vivid.device.mock.FrameEncoder;
import io.mosip.vivid.device.mock.Pacing;
import io.mosip.vivid.device.mock.render.ExpressionEvent;
import io.mosip.vivid.device.mock.render.ExpressionEvent.Action;
import io.mosip.vivid.device.mock.render.ExpressionTimeline;
import io.mosip.vivid.device.mock.render.SyntheticFaceRenderer;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A simulated person in front of the camera, drawn with device-mock's {@link SyntheticFaceRenderer}. Unlike the
 * mock's scripted scenarios it watches the session's current challenge and performs each prompted action after a
 * human reaction delay, the same closed-loop approach as {@code e2e-tests/InteractiveChallengeTest}. It also blinks
 * on its own every few seconds. Frames are encoded with device-mock's {@link FrameEncoder}.
 */
public final class ResponsiveSubjectSource implements FaceFrameSource {
    public static final int WIDTH = 640, HEIGHT = 480;
    public static final double FPS = 15;
    /** Delay between a prompt appearing and the subject starting the movement. */
    public static final long REACTION_MS = 700;

    private final Supplier<Optional<Challenge>> challengeProbe;
    private final Pacing pacing;
    private final String adapterId;
    private final AtomicReference<DeviceHealth> health = new AtomicReference<>(DeviceHealth.disconnected("not started"));
    private volatile Thread worker;

    public ResponsiveSubjectSource(Supplier<Optional<Challenge>> challengeProbe, Pacing pacing, String adapterId) {
        this.challengeProbe = Objects.requireNonNull(challengeProbe);
        this.pacing = Objects.requireNonNull(pacing);
        this.adapterId = adapterId;
    }

    @Override public String adapterId() { return adapterId; }
    @Override public DeviceHealth health() { return health.get(); }
    @Override public DeviceCapabilities capabilities() {
        return DeviceCapabilities.builder().vendor("MOSIP Vivid").model("attack-lab-subject").deviceId("DEMO-0001")
                .sbiSpecVersion("0.9.5").certificationLevel(DeviceCapabilities.CertificationLevel.L0)
                .resolution(WIDTH, HEIGHT).nominalFps(FPS).formats(Set.of(PixelFormat.RGB888)).signsFrames(false).build();
    }

    @Override public synchronized void start(FrameSink sink, StreamOptions options) {
        if (worker != null) return;
        Thread t = new Thread(() -> run(sink), "responsive-subject");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    @Override public void stop() {
        Thread t;
        synchronized (this) { t = worker; worker = null; }
        if (t == null) return;
        t.interrupt();
        if (Thread.currentThread() != t) {
            try { t.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private void run(FrameSink sink) {
        Thread self = Thread.currentThread();
        SyntheticFaceRenderer renderer = new SyntheticFaceRenderer(WIDTH, HEIGHT);
        List<ExpressionEvent> events = new ArrayList<>();
        Set<String> answered = new HashSet<>();
        long startNanos = System.nanoTime();
        long nextSpontaneousBlink = 900;
        set(sink, DeviceHealth.connected());
        try {
            for (long seq = 0; worker == self; seq++) {
                long t = Math.round(seq * 1000.0 / FPS);
                if (t >= nextSpontaneousBlink) { events.add(ExpressionEvent.blinkAt(t)); nextSpontaneousBlink += 4200; }
                Optional<Challenge> c = challengeProbe.get();
                if (c.isPresent() && answered.add(c.get().issuedAtNanos() + "#" + c.get().index())) respond(c.get(), t, events);
                events.removeIf(e -> e.toMs() < t - 5000);
                BufferedImage img = renderer.render(ExpressionTimeline.of(events).stateAt(t));
                if (!pacing.isUnthrottled()) {
                    long wait = (startNanos + pacing.wallMillis(t) * 1_000_000L - System.nanoTime()) / 1_000_000L;
                    if (wait > 0) Thread.sleep(wait);
                }
                if (worker != self) break;
                sink.onFrame(FrameEncoder.frame(seq, startNanos + t * 1_000_000L, WIDTH, HEIGHT, PixelFormat.RGB888, FrameEncoder.toRgb888(img)));
            }
        } catch (InterruptedException e) {
            // stopped
        } finally {
            set(sink, DeviceHealth.disconnected("stopped"));
            sink.onEnd();
        }
    }

    /** Schedules the prompted action, starting {@link #REACTION_MS} after now. */
    static void respond(Challenge c, long nowMs, List<ExpressionEvent> ev) {
        long t = nowMs + REACTION_MS;
        switch (c.type()) {
            case BLINK -> ev.add(ExpressionEvent.blinkAt(t));
            case BLINK_TWICE -> { ev.add(ExpressionEvent.blinkAt(t)); ev.add(ExpressionEvent.blinkAt(t + 600)); }
            case SMILE -> ev.add(ExpressionEvent.between(Action.SMILE, t, t + 1200, 1.0));
            case OPEN_MOUTH -> ev.add(ExpressionEvent.between(Action.OPEN_MOUTH, t, t + 1200, 1.0));
            case RAISE_EYEBROWS -> ev.add(ExpressionEvent.between(Action.RAISE_EYEBROWS, t, t + 1200, 1.0));
            case TURN_LEFT -> ev.add(ExpressionEvent.between(Action.YAW, t, t + 1500, 30));
            case TURN_RIGHT -> ev.add(ExpressionEvent.between(Action.YAW, t, t + 1500, -30));
            case LOOK_UP -> ev.add(ExpressionEvent.between(Action.PITCH, t, t + 1500, 22));
            case LOOK_DOWN -> ev.add(ExpressionEvent.between(Action.PITCH, t, t + 1500, -22));
            case NOD -> ev.add(ExpressionEvent.between(Action.NOD, t, t + 1500, 22));
            case HOLD_STILL -> ev.add(ExpressionEvent.between(Action.HOLD_STILL, t, t + 2500, 1.0));
        }
    }

    private void set(FrameSink sink, DeviceHealth h) {
        health.set(h);
        sink.onHealth(h);
    }
}
