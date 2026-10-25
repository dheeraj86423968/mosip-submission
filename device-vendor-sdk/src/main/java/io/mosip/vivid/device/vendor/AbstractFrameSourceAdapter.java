package io.mosip.vivid.device.vendor;

import io.mosip.vivid.device.DeviceCapabilities;
import io.mosip.vivid.device.DeviceException;
import io.mosip.vivid.device.DeviceHealth;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.FaceFrameSource;
import io.mosip.vivid.device.FrameSink;
import io.mosip.vivid.device.PixelFormat;
import io.mosip.vivid.device.StreamOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Base class for a {@link FaceFrameSource} over any device API. A subclass only says how to open the device,
 * start its frames and close it; this class does everything the {@code device-api} contract and
 * {@code testkit}'s {@code FrameSourceConformanceTest} require:
 *
 * <ul>
 *   <li><b>Threads.</b> Frames arrive on whatever thread the device calls {@link FrameEmitter#emit}; they go into a
 *       {@link FrameRing} (drop-oldest, {@link StreamOptions#maxQueuedFrames()}) and a dedicated delivery thread
 *       calls the sink. A supervisor thread owns connect, stall detection and reconnect.</li>
 *   <li><b>Frames.</b> Sequence numbers are assigned here, strictly increasing across reconnects; timestamps are
 *       {@link System#nanoTime()} at arrival, never decreasing. {@link StreamOptions#targetFps()} is honoured by
 *       dropping frames that arrive faster than it (counted in {@link #framesDecimated()}).</li>
 *   <li><b>Health.</b> {@code CONNECTING} at start, {@code CONNECTED} on the first frame of each connection,
 *       {@code DEGRADED} when no frame arrived for {@link StreamOptions#stallTimeoutMillis()}, then
 *       {@code RECONNECTING}; {@code RECONNECTING} on a device error; {@code FAILED} after
 *       {@link StreamOptions#reconnectAttempts()} consecutive failed attempts; {@code DISCONNECTED("stopped")} on
 *       {@link #stop()}. Health events reach the sink in order, on the delivery thread.</li>
 *   <li><b>Reconnect.</b> Exponential backoff {@code min(max, initial * 2^(n-1))} plus up to 25 % jitter. The
 *       attempt counter resets once a connection delivers a frame. Frames from an abandoned connection are ignored.</li>
 *   <li><b>Lifecycle.</b> {@code start} while running is ignored, {@code stop} is idempotent, {@code onEnd} fires
 *       exactly once per start (also after {@code FAILED}), and nothing reaches the sink after {@code onEnd}.</li>
 * </ul>
 *
 * <p>Hook methods are called on the supervisor thread, never concurrently with each other for the same adapter.
 */
public abstract class AbstractFrameSourceAdapter implements FaceFrameSource {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractFrameSourceAdapter.class);

    /** Reconnect backoff parameters. */
    public static final class Backoff {
        private final long initialMs, maxMs;
        public Backoff(long initialMs, long maxMs) {
            if (initialMs <= 0 || maxMs < initialMs) throw new IllegalArgumentException("bad backoff " + initialMs + ".." + maxMs);
            this.initialMs = initialMs; this.maxMs = maxMs;
        }
        /** 250 ms doubling to 4 s, as docs/design/02-frame-streaming.md specifies. */
        public static Backoff defaults() { return new Backoff(250, 4000); }
        public long initialMs() { return initialMs; }
        public long maxMs() { return maxMs; }
        long delayFor(int attempt) {
            long base = Math.min(maxMs, initialMs * (1L << Math.min(20, Math.max(0, attempt - 1))));
            return Math.min(maxMs, base + ThreadLocalRandom.current().nextLong(base / 4 + 1));
        }
    }

    /**
     * Handed to {@link #startFrames}; bound to one connection. Thread-safe and non-blocking, so it can be called
     * directly from a vendor SDK's callback thread. Calls on an emitter whose connection was abandoned are ignored.
     */
    public interface FrameEmitter {
        /**
         * Delivers one frame. {@code pixels} must not be modified afterwards (copy SDK-owned buffers first).
         *
         * @param depth GRAY16 depth plane aligned with the colour frame, or null
         * @param ir    infrared plane, or null
         */
        void emit(int width, int height, PixelFormat format, ByteBuffer pixels, ByteBuffer depth, ByteBuffer ir);
        /** Reports a connection-level failure; the adapter disconnects and reconnects with backoff. */
        void fault(String reason);
        /** False once this connection has been abandoned (stop, fault, stall); SDK loops may poll it. */
        boolean isActive();
    }

    private final String adapterId;
    private final Backoff backoff;
    private final AtomicReference<DeviceHealth> health = new AtomicReference<>(DeviceHealth.disconnected("not started"));
    private final AtomicReference<DeviceCapabilities> capabilities = new AtomicReference<>();
    private final AtomicLong framesReceived = new AtomicLong(), framesDropped = new AtomicLong(), framesDecimated = new AtomicLong(), reconnects = new AtomicLong();
    private final Object lifecycle = new Object();
    private final Object hooks = new Object();     // serialises connect/disconnect/probe
    private Session session;                       // guarded by lifecycle

    protected AbstractFrameSourceAdapter(String adapterId, Backoff backoff) {
        this.adapterId = Objects.requireNonNull(adapterId);
        this.backoff = Objects.requireNonNull(backoff);
    }

    // ------------------------------------------------------------------ hooks

    /**
     * Opens the device and returns what it can do. Called on the supervisor thread for every (re)connection.
     * Throw anything to signal failure; the adapter retries with backoff.
     */
    protected abstract DeviceCapabilities connect() throws Exception;

    /**
     * Starts frame delivery for the connection just opened by {@link #connect()}. Either register a callback
     * that calls {@code emitter.emit(...)} and return, or start your own thread. Must not block indefinitely.
     */
    protected abstract void startFrames(FrameEmitter emitter) throws Exception;

    /** Stops frames and releases the device. Idempotent; must not throw. Called after failures too. */
    protected abstract void disconnect();

    /**
     * Capabilities when no session is running. The default opens the device with {@link #connect()} and closes it
     * again with {@link #disconnect()}; override if the SDK can describe a device without opening it.
     */
    protected DeviceCapabilities probeCapabilities() throws Exception {
        try { return connect(); } finally { disconnect(); }
    }

    // ------------------------------------------------------------------ FaceFrameSource

    @Override public String adapterId() { return adapterId; }
    @Override public DeviceHealth health() { return health.get(); }

    /**
     * The last known capabilities, probing the device once if none are known.
     *
     * @throws DeviceException {@code UNAVAILABLE} if the device cannot be opened
     */
    @Override public DeviceCapabilities capabilities() {
        DeviceCapabilities c = capabilities.get();
        if (c != null) return c;
        synchronized (hooks) {
            c = capabilities.get();
            if (c != null) return c;
            try {
                c = Objects.requireNonNull(probeCapabilities(), "probeCapabilities() returned null");
            } catch (DeviceException e) {
                throw e;
            } catch (Exception e) {
                throw new DeviceException(DeviceException.Kind.UNAVAILABLE, adapterId + ": device not available: " + e.getMessage(), e);
            }
            capabilities.set(c);
            return c;
        }
    }

    @Override public void start(FrameSink sink, StreamOptions options) {
        Objects.requireNonNull(sink); Objects.requireNonNull(options);
        synchronized (lifecycle) {
            if (session != null && session.isActive()) { LOG.warn("{}: start() while already streaming; ignored", adapterId); return; }
            session = new Session(sink, options);
            session.start();
        }
    }

    @Override public void stop() {
        Session s;
        synchronized (lifecycle) { s = session; session = null; }
        if (s != null) s.stopAndJoin();
    }

    public long framesReceived() { return framesReceived.get(); }
    /** Frames evicted from the ring because the sink was slower than the device. */
    public long framesDropped() { return framesDropped.get(); }
    /** Frames skipped to honour {@link StreamOptions#targetFps()}. */
    public long framesDecimated() { return framesDecimated.get(); }
    public long reconnects() { return reconnects.get(); }

    // ------------------------------------------------------------------ session

    private final class Session {
        private final FrameSink sink;
        private final StreamOptions options;
        private final FrameRing ring;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicBoolean endSignalled = new AtomicBoolean();
        private final LinkedBlockingQueue<String> faults = new LinkedBlockingQueue<>();
        private final Thread supervisor, deliverer;
        private final Object frameLock = new Object();
        private long sequence, lastTimestamp, lastAccepted;
        private volatile Connection current;

        Session(FrameSink sink, StreamOptions options) {
            this.sink = sink; this.options = options;
            this.ring = new FrameRing(options.maxQueuedFrames());
            supervisor = new Thread(this::supervise, adapterId + "-supervisor");
            deliverer = new Thread(this::deliver, adapterId + "-delivery");
            supervisor.setDaemon(true); deliverer.setDaemon(true);
        }

        boolean isActive() { return running.get() || supervisor.isAlive() || deliverer.isAlive(); }

        void start() { setHealth(DeviceHealth.connecting()); deliverer.start(); supervisor.start(); }

        void stopAndJoin() {
            if (!running.compareAndSet(true, false)) return;
            supervisor.interrupt();
            if (Thread.currentThread() != supervisor) join(supervisor);
            setHealth(DeviceHealth.disconnected("stopped"));
            ring.close();
            if (Thread.currentThread() != deliverer) join(deliverer);
        }

        private void join(Thread t) { try { t.join(10_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

        private void setHealth(DeviceHealth h) {
            DeviceHealth prev = health.getAndSet(h);
            if (prev.state() != h.state() || !prev.reason().equals(h.reason())) {
                LOG.info("{} health {} -> {}", adapterId, prev, h);
                ring.offerHealth(h);
            }
        }

        /** One connection's emitter; ignores calls once abandoned. */
        private final class Connection implements FrameEmitter {
            final AtomicBoolean active = new AtomicBoolean(true);
            volatile long lastFrameNanos = System.nanoTime();
            volatile boolean sawFrame;

            @Override public boolean isActive() { return active.get() && running.get(); }

            @Override public void fault(String reason) { if (isActive()) faults.offer(reason == null ? "device error" : reason); }

            @Override public void emit(int width, int height, PixelFormat format, ByteBuffer pixels, ByteBuffer depth, ByteBuffer ir) {
                if (!isActive()) return;
                if (width <= 0 || height <= 0 || format == null || pixels == null || !pixels.hasRemaining()) {
                    LOG.debug("{}: ignoring empty or malformed frame {}x{} {}", adapterId, width, height, format);
                    return;
                }
                long now = System.nanoTime();
                lastFrameNanos = now;
                // Under frameLock so that CONNECTED cannot be reported for a connection the supervisor has
                // already abandoned (abandon() clears 'active' under the same lock before reporting RECONNECTING).
                synchronized (frameLock) {
                    if (!isActive()) return;
                    if (!sawFrame) { sawFrame = true; setHealth(DeviceHealth.connected()); }
                    if (options.targetFps() > 0 && lastAccepted != 0 && now - lastAccepted < (long) (0.8e9 / options.targetFps())) {
                        framesDecimated.incrementAndGet();
                        return;
                    }
                    lastAccepted = now;
                    long ts = Math.max(now, lastTimestamp);
                    lastTimestamp = ts;
                    FaceFrame.Builder b = FaceFrame.builder().sequence(sequence++).timestampNanos(ts)
                            .width(width).height(height).format(format).pixels(pixels);
                    if (depth != null) b.depth(depth);
                    if (ir != null) b.ir(ir);
                    framesReceived.incrementAndGet();
                    if (ring.offer(b.build())) framesDropped.incrementAndGet();
                }
            }
        }

        private void supervise() {
            int attempt = 0;
            DeviceHealth terminal = null;
            try {
                while (running.get()) {
                    String failure = runConnection();
                    if (!running.get()) break;
                    if (failure == null) break;               // not reached: runConnection returns a reason or stop
                    attempt = current != null && current.sawFrame ? 1 : attempt + 1;
                    if (attempt > options.reconnectAttempts()) {
                        terminal = DeviceHealth.failed("gave up after " + options.reconnectAttempts() + " reconnect attempts: " + failure);
                        break;
                    }
                    long delay = backoff.delayFor(attempt);
                    setHealth(DeviceHealth.reconnecting("attempt " + attempt + "/" + options.reconnectAttempts() + " in " + delay + " ms: " + failure));
                    reconnects.incrementAndGet();
                    Thread.sleep(delay);
                }
            } catch (InterruptedException e) {
                // stop() requested
            } catch (RuntimeException e) {
                LOG.error("{} supervisor crashed", adapterId, e);
                terminal = DeviceHealth.failed("adapter error: " + e);
            } finally {
                abandon();
                if (running.compareAndSet(true, false)) {      // ended on its own, not via stop()
                    setHealth(terminal != null ? terminal : DeviceHealth.disconnected("ended"));
                    ring.close();
                }
            }
        }

        /** Connects and supervises one connection; returns why it ended (null only when stopping). */
        private String runConnection() throws InterruptedException {
            faults.clear();
            Connection c = new Connection();
            current = c;
            try {
                synchronized (hooks) {
                    capabilities.set(Objects.requireNonNull(connect(), "connect() returned null"));
                    startFrames(c);
                }
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                abandon();
                return "connect failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            long stallNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1, options.stallTimeoutMillis()));
            long pollMs = Math.max(10, Math.min(250, options.stallTimeoutMillis() / 4));
            while (running.get()) {
                String signal = faults.poll(pollMs, TimeUnit.MILLISECONDS);
                if (signal != null) { abandon(); return signal; }
                long silent = System.nanoTime() - c.lastFrameNanos;
                if (silent > stallNanos) {
                    String reason = "no frame for " + TimeUnit.NANOSECONDS.toMillis(silent) + " ms";
                    if (c.sawFrame) setHealth(DeviceHealth.degraded(reason));
                    abandon();
                    return reason;
                }
            }
            return null;
        }

        private void abandon() {
            Connection c = current;
            if (c != null) synchronized (frameLock) { c.active.set(false); }
            synchronized (hooks) {
                try { disconnect(); } catch (RuntimeException e) { LOG.warn("{}: disconnect() threw", adapterId, e); }
            }
        }

        private void deliver() {
            try {
                while (true) {
                    FrameRing.Item item = ring.poll(250);
                    if (item == null) { if (ring.isClosed()) break; continue; }
                    try {
                        if (item.health() != null) sink.onHealth(item.health()); else sink.onFrame(item.frame());
                    } catch (RuntimeException e) { LOG.warn("{}: sink threw", adapterId, e); }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (endSignalled.compareAndSet(false, true)) {
                    try { sink.onEnd(); } catch (RuntimeException e) { LOG.warn("{}: sink.onEnd threw", adapterId, e); }
                }
            }
        }
    }
}
