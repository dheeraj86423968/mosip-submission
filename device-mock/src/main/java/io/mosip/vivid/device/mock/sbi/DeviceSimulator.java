package io.mosip.vivid.device.mock.sbi;

import io.mosip.vivid.device.mock.FrameEncoder;
import io.mosip.vivid.device.mock.scenario.Fault;
import io.mosip.vivid.device.mock.scenario.PresentationLabel;
import io.mosip.vivid.device.mock.scenario.Scenario;
import io.mosip.vivid.device.mock.scenario.ScenarioTimeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The "camera" behind {@link MockSbiServer}: a single real-time thread that advances scenario time, renders
 * and JPEG-encodes frames, and applies scenario and injected faults to shared device state. STREAM handlers
 * are passive consumers that wait for the next published frame, so several clients see the same device and
 * the same faults.
 *
 * <p>Scenario time is anchored when the first STREAM request arrives (or at {@link #start()} if configured),
 * so a non-looping scenario does not run out before anyone connects.
 */
final class DeviceSimulator implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(DeviceSimulator.class);

    /** One encoded frame as published to stream writers. */
    record Published(long sequence, long scenarioMs, byte[] jpeg, boolean corrupted, PresentationLabel label) {}

    private final float jpegQuality;
    private final boolean anchorOnStart;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final ConcurrentLinkedQueue<Fault> injected = new ConcurrentLinkedQueue<>();

    // guarded by lock
    private ScenarioTimeline timeline;
    private double fpsOverride;
    private long anchorNanos = -1;
    private Published latest;
    private boolean plugged = true;
    private boolean permanentlyRemoved;
    private boolean ended;
    private long stallUntilMs = -1;
    private int corruptRemaining;
    private double corruptFraction = 0.5;
    private long generation;   // bumped on disconnect / scenario switch so writers re-evaluate
    private volatile boolean running;
    private Thread thread;

    DeviceSimulator(Scenario scenario, double fpsOverride, float jpegQuality, boolean anchorOnStart) {
        this.timeline = new ScenarioTimeline(scenario);
        this.fpsOverride = fpsOverride;
        this.jpegQuality = jpegQuality;
        this.anchorOnStart = anchorOnStart;
    }

    void start() {
        running = true;
        if (anchorOnStart) anchorNow();
        thread = new Thread(this::run, "mock-sbi-device");
        thread.setDaemon(true);
        thread.start();
    }

    @Override public void close() {
        running = false;
        if (thread != null) { thread.interrupt(); try { thread.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        signal();
    }

    void inject(Fault f) { injected.add(f); signal(); }

    /** Replace the running scenario; scenario time restarts at zero on the next stream. */
    void switchScenario(Scenario s) {
        lock.lock();
        try {
            timeline = new ScenarioTimeline(s);
            anchorNanos = anchorOnStart ? System.nanoTime() : -1;
            latest = null; ended = false; stallUntilMs = -1; corruptRemaining = 0;
            generation++;
            changed.signalAll();
        } finally { lock.unlock(); }
    }

    Scenario scenario() { lock.lock(); try { return timeline.scenario(); } finally { lock.unlock(); } }

    /** Called by STREAM handlers: starts the scenario clock if it has not started. */
    void ensureAnchored() { lock.lock(); try { if (anchorNanos < 0) anchorNow(); changed.signalAll(); } finally { lock.unlock(); } }

    private void anchorNow() { lock.lock(); try { anchorNanos = System.nanoTime(); } finally { lock.unlock(); } }

    boolean isPlugged() { lock.lock(); try { return plugged; } finally { lock.unlock(); } }
    boolean isEnded() { lock.lock(); try { return ended; } finally { lock.unlock(); } }
    long generation() { lock.lock(); try { return generation; } finally { lock.unlock(); } }
    Optional<Published> latest() { lock.lock(); try { return Optional.ofNullable(latest); } finally { lock.unlock(); } }

    /** Current scenario time in ms, or -1 before the clock is anchored. */
    long scenarioMs() {
        lock.lock();
        try { return anchorNanos < 0 ? -1 : (System.nanoTime() - anchorNanos) / 1_000_000L; } finally { lock.unlock(); }
    }

    /**
     * Wait up to {@code timeoutMs} for a frame newer than {@code afterSequence}. Returns empty on timeout,
     * when the device is unplugged, or when the scenario ended / generation changed; callers check state.
     */
    Optional<Published> awaitFrame(long afterSequence, long generationSeen, long timeoutMs) throws InterruptedException {
        lock.lock();
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (running && plugged && !ended && generation == generationSeen && (latest == null || latest.sequence() <= afterSequence)) {
                long left = deadline - System.nanoTime();
                if (left <= 0) return Optional.empty();
                changed.awaitNanos(left);
            }
            if (!running || !plugged || ended || generation != generationSeen) return Optional.empty();
            return Optional.of(latest);
        } finally { lock.unlock(); }
    }

    private void signal() { lock.lock(); try { changed.signalAll(); } finally { lock.unlock(); } }

    private void run() {
        long prevFrameMs = -1;
        long prevFaultMs = -1;
        long sequence = 0;
        try {
            while (running) {
                ScenarioTimeline tl;
                long nowMs;
                lock.lock();
                try {
                    while (running && anchorNanos < 0) changed.await();   // wait for first stream
                    if (!running) return;
                    tl = timeline;
                    nowMs = (System.nanoTime() - anchorNanos) / 1_000_000L;
                    if (tl.isEnded(nowMs) && !ended) { ended = true; changed.signalAll(); LOG.info("scenario '{}' ended", tl.scenario().name()); }
                } finally { lock.unlock(); }

                // scenario faults crossed since the last tick, then injected ones
                for (ScenarioTimeline.FaultOccurrence fo : tl.faultsBetween(prevFaultMs, nowMs)) applyFault(fo.fault(), nowMs);
                prevFaultMs = nowMs;
                Fault f;
                while ((f = injected.poll()) != null) applyFault(f, nowMs);

                long nextMs = tl.nextFrameTimeAtOrAfter(prevFrameMs + 1);
                if (fpsOverride > 0 && prevFrameMs >= 0) nextMs = Math.max(nextMs, prevFrameMs + Math.round(1000.0 / fpsOverride));
                if (nextMs < 0) { Thread.sleep(50); continue; }        // ended: idle until switched
                if (nextMs > nowMs) { Thread.sleep(Math.min(50, nextMs - nowMs)); continue; }

                boolean stalled, isPlugged;
                lock.lock();
                try { stalled = stallUntilMs > nowMs; isPlugged = plugged; } finally { lock.unlock(); }
                prevFrameMs = nextMs;
                if (stalled || !isPlugged) continue;

                Optional<ScenarioTimeline.Position> pos = tl.positionAt(nextMs);
                if (pos.isEmpty()) continue;
                byte[] jpeg = FrameEncoder.toJpeg(tl.render(pos.get()), jpegQuality);
                boolean corrupted = false;
                lock.lock();
                try {
                    if (corruptRemaining > 0) {
                        corruptRemaining--;
                        jpeg = Arrays.copyOf(jpeg, Math.max(1, (int) (jpeg.length * corruptFraction)));
                        corrupted = true;
                    }
                    latest = new Published(++sequence, nextMs, jpeg, corrupted, pos.get().segment().label());
                    changed.signalAll();
                } finally { lock.unlock(); }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOG.error("device simulator failed", e);
        }
    }

    private void applyFault(Fault fault, long nowMs) throws InterruptedException {
        LOG.info("mock SBI fault {} at scenario {} ms", fault, nowMs);
        lock.lock();
        try {
            switch (fault.kind()) {
                case DISCONNECT -> {
                    plugged = false; generation++;
                    if (fault.boolParam("permanent", false)) permanentlyRemoved = true;
                }
                case RECONNECT -> { if (!permanentlyRemoved) { plugged = true; generation++; } }
                case STALL -> stallUntilMs = nowMs + fault.longParam("durationMs", 2000);
                case CORRUPT_FRAME -> { corruptRemaining += (int) fault.longParam("count", 1); corruptFraction = fault.doubleParam("fraction", 0.5); }
                case LATENCY_SPIKE -> { /* handled below, outside the lock */ }
            }
            changed.signalAll();
        } finally { lock.unlock(); }
        if (fault.kind() == io.mosip.vivid.device.mock.scenario.FaultKind.LATENCY_SPIKE) Thread.sleep(fault.longParam("delayMs", 500));
    }
}
