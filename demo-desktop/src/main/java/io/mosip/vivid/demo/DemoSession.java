package io.mosip.vivid.demo;

import io.mosip.vivid.api.Challenge;
import io.mosip.vivid.api.FaceObservation;
import io.mosip.vivid.api.FrameAnalysis;
import io.mosip.vivid.api.LivenessPolicy;
import io.mosip.vivid.api.SessionContext;
import io.mosip.vivid.api.SessionState;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.core.action.ActionThresholds;
import io.mosip.vivid.core.bridge.FrameSourceBridge;
import io.mosip.vivid.core.session.LivenessOrchestrator;
import io.mosip.vivid.core.session.LivenessSessionFactory;
import io.mosip.vivid.core.session.TimeSource;
import io.mosip.vivid.device.DeviceCapabilities;
import io.mosip.vivid.device.DeviceHealth;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.FaceFrameSource;
import io.mosip.vivid.device.FrameSink;
import io.mosip.vivid.device.PixelFormat;
import io.mosip.vivid.device.StreamOptions;
import io.mosip.vivid.device.mock.Pacing;
import io.mosip.vivid.telemetry.audit.AuditRecord;
import io.mosip.vivid.telemetry.audit.AuditSink;
import io.mosip.vivid.telemetry.diagnostics.DiagnosticSnapshot;
import io.mosip.vivid.telemetry.metrics.LivenessMetrics;

import javax.imageio.ImageIO;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * One attack-lab liveness session: the real {@link LivenessSessionFactory} and {@link LivenessOrchestrator}, the
 * real engine (through {@link DemoEngine}), and a frame source connected by the real {@link FrameSourceBridge}.
 * After every frame it publishes a {@link Snapshot} to the listener, on the frame-delivery thread.
 */
public final class DemoSession implements AutoCloseable {
    private final DemoEngine engine;
    private final Workflow workflow;
    private final LivenessPolicy policy;
    private final boolean synthetic;
    private final DemoClock clock = new DemoClock();
    private final List<Snapshot.AuditLine> audit = new CopyOnWriteArrayList<>();
    private final Consumer<Snapshot> listener;
    private final LivenessOrchestrator session;
    private final FrameSourceBridge bridge;
    private final AttackLabSource lab;          // null with an external (SBI) source
    private final String externalLabel;
    private volatile Snapshot last;
    private volatile String note;

    /**
     * Session against the attack-lab source.
     *
     * @param seed challenge-draw seed for reproducible recordings, or null for a fresh {@link SecureRandom}
     */
    public DemoSession(DemoEngine engine, Workflow workflow, Subject subject, Pacing pacing, Long seed, Consumer<Snapshot> listener) {
        this(engine, workflow, subject, pacing, seed, listener, null, null);
    }

    /** Session against an external device (for example an SBI stream); the subject selector does not apply. */
    public static DemoSession external(DemoEngine engine, Workflow workflow, FaceFrameSource device, String label, Consumer<Snapshot> listener) {
        return new DemoSession(engine, workflow, null, null, null, listener, device, label);
    }

    private DemoSession(DemoEngine engine, Workflow workflow, Subject subject, Pacing pacing, Long seed, Consumer<Snapshot> listener,
                        FaceFrameSource external, String externalLabel) {
        this.engine = Objects.requireNonNull(engine);
        this.workflow = Objects.requireNonNull(workflow);
        this.listener = listener == null ? s -> { } : listener;
        this.synthetic = external == null;
        this.policy = DemoSetup.policy(workflow, synthetic);
        this.externalLabel = externalLabel;
        engine.clearLastAnalysis();
        AuditSink sink = this::onAudit;
        TimeSource time = synthetic ? clock : TimeSource.system();
        LivenessSessionFactory factory = new LivenessSessionFactory(engine, w -> policy, sink, new LivenessMetrics(), time,
                seed == null ? new SecureRandom() : seeded(seed), ActionThresholds.defaults());
        SessionContext ctx = factory.newContext(workflow, Locale.ENGLISH, false, false);
        this.session = factory.open(policy, ctx);
        FaceFrameSource source;
        if (synthetic) {
            lab = new AttackLabSource(subject, session::currentChallenge, pacing, clock, this::afterFrame);
            source = lab;
        } else {
            lab = null;
            source = new Observed(external, this::afterFrame);
        }
        this.bridge = new FrameSourceBridge(source, session);
    }

    private static SecureRandom seeded(long seed) {
        try {
            SecureRandom r = SecureRandom.getInstance("SHA1PRNG");
            r.setSeed(seed);
            return r;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public void start() { bridge.start(); }

    public Workflow workflow() { return workflow; }
    public LivenessPolicy policy() { return policy; }
    public LivenessOrchestrator orchestrator() { return session; }
    public Optional<Subject> subject() { return lab == null ? Optional.empty() : Optional.of(lab.subject()); }
    public long elapsedMs() { return clock.millis(); }

    /** Attack lab: change what is in front of the camera without restarting the session. */
    public void switchTo(Subject s) {
        if (lab == null) return;
        note = "Switched to " + s.label() + " at " + String.format(Locale.ROOT, "%.1f s", clock.millis() / 1000.0);
        audit.add(new Snapshot.AuditLine(clock.millis(), "ATTACK_LAB", "DEMO", "subject -> " + s.label()));
        lab.switchTo(s);
    }

    /** Starts the next attempt after a failure; false when no attempts are left. */
    public boolean retry() {
        note = null;
        return session.retry();
    }

    public Snapshot snapshot() { return last; }

    private void onAudit(AuditRecord r) {
        String detail = r.attributes().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(" "));
        if (r.evidenceHash().isPresent()) detail = (detail.isEmpty() ? "" : detail + " ") + "evidence=" + r.evidenceHash().get().substring(0, Math.min(12, r.evidenceHash().get().length())) + "…";
        audit.add(new Snapshot.AuditLine(clock.millis(), r.event().name(), r.event().severity().name(), detail));
    }

    private void afterFrame(FaceFrame f) {
        try {
            publish(f);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(DemoSession.class).warn("snapshot failed: {}", e.toString());
        }
    }

    private void publish(FaceFrame f) {
        SessionState st = session.state();
        DiagnosticSnapshot d = session.captureDiagnostics();
        Challenge c = session.currentChallenge().orElse(null);
        FrameAnalysis a = engine.lastAnalysis();
        Rectangle2D box = null;
        if (a != null && a.face().isPresent()) {
            FaceObservation o = a.face().get();
            box = new Rectangle2D.Double(o.boxX(), o.boxY(), o.boxW(), o.boxH());
        }
        Map<String, Double> weights = new TreeMap<>();
        for (String id : d.lastSignalScores().keySet()) weights.put(id, policy.signalWeight(id));
        for (String id : DemoSetup.SYNTHETIC_DISABLED_SIGNALS) weights.put(id, policy.signalWeight(id));
        String label = lab != null ? lab.subject().label() : externalLabel;
        Snapshot s = new Snapshot(workflow, label, synthetic, st, c, session.actionProgress(), d.lastSignalScores(), weights,
                policy.passiveThreshold(), resultOrNull(), decode(f), box, synthetic ? clock.millis() : d.capturedAt().toEpochMilli(),
                d.framesAnalyzed(), new ArrayList<>(audit), note);
        last = s;
        listener.accept(s);
    }

    private io.mosip.vivid.api.LivenessResult resultOrNull() {
        try {
            return session.result();
        } catch (IllegalStateException e) {
            return null;   // no result before the attempt ends
        }
    }

    /** RGB888 or MJPEG frame to an image for the preview. */
    static BufferedImage decode(FaceFrame f) {
        ByteBuffer buf = f.pixels();
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        if (f.format() == PixelFormat.RGB888 && bytes.length >= f.width() * f.height() * 3) {
            BufferedImage img = new BufferedImage(f.width(), f.height(), BufferedImage.TYPE_INT_RGB);
            int[] px = new int[f.width() * f.height()];
            for (int i = 0, j = 0; i < px.length; i++, j += 3) px[i] = ((bytes[j] & 0xff) << 16) | ((bytes[j + 1] & 0xff) << 8) | (bytes[j + 2] & 0xff);
            img.setRGB(0, 0, f.width(), f.height(), px, 0, f.width());
            return img;
        }
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            return null;
        }
    }

    public void stop() { bridge.stop(); }

    @Override public void close() {
        session.close();   // cancels a running attempt; the bridge's listener then stops the source
        bridge.close();
    }

    /** Pass-through source that reports each frame after the session has seen it. */
    private static final class Observed implements FaceFrameSource {
        private final FaceFrameSource d;
        private final Consumer<FaceFrame> after;
        Observed(FaceFrameSource d, Consumer<FaceFrame> after) { this.d = d; this.after = after; }
        @Override public String adapterId() { return d.adapterId(); }
        @Override public DeviceCapabilities capabilities() { return d.capabilities(); }
        @Override public DeviceHealth health() { return d.health(); }
        @Override public void stop() { d.stop(); }
        @Override public void start(FrameSink sink, StreamOptions options) {
            d.start(new FrameSink() {
                @Override public void onFrame(FaceFrame f) { sink.onFrame(f); after.accept(f); }
                @Override public void onHealth(DeviceHealth h) { sink.onHealth(h); }
                @Override public void onEnd() { sink.onEnd(); }
            }, options);
        }
    }
}
