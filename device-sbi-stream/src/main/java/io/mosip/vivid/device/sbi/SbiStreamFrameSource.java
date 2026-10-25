package io.mosip.vivid.device.sbi;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Desktop {@link FaceFrameSource} for MOSIP SBI face devices. Discovers registration face devices on
 * 127.0.0.1:4501-4600 (or a configured endpoint), opens {@code STREAM /stream} and delivers each MJPEG part as a
 * {@link PixelFormat#MJPEG} {@link FaceFrame}.
 *
 * <h2>Threads</h2>
 * A reader thread owns the socket: it parses multipart parts into a bounded ring (drop-oldest, so a slow
 * engine never stalls the socket). A delivery thread drains the ring into the sink; health transitions are
 * queued through the same path so the sink sees them in order.
 *
 * <h2>Fault model</h2>
 * <ul>
 *   <li>No bytes for {@link StreamOptions#stallTimeoutMillis()}: {@code DEGRADED} then {@code RECONNECTING}.</li>
 *   <li>Socket reset, EOF, malformed response: {@code RECONNECTING} with exponential backoff and jitter
 *       ({@link SbiStreamConfig#backoffInitialMs()} doubling up to {@link SbiStreamConfig#backoffMaxMs()}).</li>
 *   <li>After {@link StreamOptions#reconnectAttempts()} consecutive failures: {@code FAILED} and {@code onEnd}.</li>
 *   <li>{@link #stop()}: {@code DISCONNECTED("stopped")} and {@code onEnd}, exactly once per start.</li>
 * </ul>
 * Every reconnection re-runs discovery: a scan finds a service that restarted on a different port, and a pinned
 * endpoint is re-probed so a service that now exposes a different {@code deviceId} is followed.
 *
 * <h2>Capabilities</h2>
 * SBI advertises vendor, model, serial, certification level and spec version but not resolution or fps, so
 * {@link #capabilities()} reports 0x0 until the first frame has been parsed, then the observed JPEG size.
 * By default the stream is unsigned ({@code signsFrames()} false; only {@code RCAPTURE} is signed). With
 * {@link SbiStreamConfig.SignedPreview} enabled and a device that advertises it, every frame is verified with
 * {@link SignedPreviewVerifier} and carries its device signature; {@code signsFrames()} is then true, so the core
 * treats any frame without a valid signature as injected.
 */
public final class SbiStreamFrameSource implements FaceFrameSource {
    private static final Logger LOG = LoggerFactory.getLogger(SbiStreamFrameSource.class);
    /** Adapter identifier. */
    public static final String ADAPTER_ID = "sbi-stream";
    private static final int FALLBACK_WIDTH = 640, FALLBACK_HEIGHT = 480;

    private final SbiStreamConfig config;
    private final SbiClient client;
    private final AtomicReference<DeviceHealth> health = new AtomicReference<>(DeviceHealth.disconnected("not started"));
    private final AtomicReference<SbiDeviceInfo> device = new AtomicReference<>();
    private final AtomicReference<int[]> observedSize = new AtomicReference<>();
    /** Device key read from {@code /info} ("" = not advertised); kept across reconnects, i.e. trust on first use. */
    private final AtomicReference<String> advertisedKey = new AtomicReference<>();
    private final AtomicLong framesReceived = new AtomicLong();
    private final AtomicLong framesDropped = new AtomicLong();
    private final AtomicLong reconnects = new AtomicLong();
    private volatile double observedFps;
    private final Object lifecycle = new Object();
    private Session session;   // guarded by lifecycle

    public SbiStreamFrameSource(SbiStreamConfig config) {
        this.config = Objects.requireNonNull(config);
        this.client = new SbiClient(config.connectTimeoutMs(), config.discoveryReadTimeoutMs());
    }

    /** Convenience: scan the standard range and use the first Ready registration face device. */
    public static SbiStreamFrameSource discoverDefault() { return new SbiStreamFrameSource(SbiStreamConfig.defaults()); }

    @Override public String adapterId() { return ADAPTER_ID; }
    @Override public DeviceHealth health() { return health.get(); }

    /**
     * Discovers the device on first call (blocking for at most the discovery timeouts) and maps its SBI metadata
     * onto {@link DeviceCapabilities}. Resolution and fps reflect what has been observed so far.
     *
     * @throws DeviceException {@code UNAVAILABLE} if no suitable face device answers
     */
    @Override public DeviceCapabilities capabilities() {
        SbiDeviceInfo d = device.get();
        if (d == null) d = resolveDevice();
        int[] wh = observedSize.get();
        DeviceCapabilities c = d.toCapabilities(wh == null ? 0 : wh[0], wh == null ? 0 : wh[1], observedFps);
        if (!signingExpected(d)) return c;
        return DeviceCapabilities.builder().vendor(c.vendor()).model(c.model()).deviceId(c.deviceId()).sbiSpecVersion(c.sbiSpecVersion())
                .certificationLevel(c.certificationLevel()).resolution(c.width(), c.height()).nominalFps(c.nominalFps())
                .formats(c.formats()).hasDepth(c.hasDepth()).hasIr(c.hasIr()).signsFrames(true).build();
    }

    /** Signing is expected when required by config, or requested and advertised by the device. */
    private boolean signingExpected(SbiDeviceInfo d) {
        switch (config.signedPreview()) {
            case REQUIRED: return true;
            case IF_SUPPORTED: return deviceSigningKey(d).isPresent();
            default: return false;
        }
    }

    /** The key to verify against: the pinned one, else the one the device advertises in {@code /info}. */
    private Optional<String> deviceSigningKey(SbiDeviceInfo d) {
        if (config.trustedDeviceKey().isPresent()) return config.trustedDeviceKey();
        String cached = advertisedKey.get();
        if (cached != null) return cached.isEmpty() ? Optional.empty() : Optional.of(cached);
        String key = "";
        try {
            for (SbiDeviceInfo i : client.info(d.endpoint())) {
                JsonNode ss = i.raw().path("streamSigning");
                if ((d.deviceId().isEmpty() || d.deviceId().equals(i.deviceId())) && ss.path("supported").asBoolean(false)
                        && SignedPreviewVerifier.ALG.equals(ss.path("alg").asText())) {
                    key = ss.path("deviceKey").asText("");
                    break;
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("cannot read streamSigning from /info: {}", e.toString());
        }
        advertisedKey.set(key);
        return key.isEmpty() ? Optional.empty() : Optional.of(key);
    }

    /** The device currently bound, if discovery has run. */
    public Optional<SbiDeviceInfo> device() { return Optional.ofNullable(device.get()); }
    public long framesReceived() { return framesReceived.get(); }
    /** Frames evicted from the ring because the sink was slower than the device. */
    public long framesDropped() { return framesDropped.get(); }
    public long reconnects() { return reconnects.get(); }

    @Override public void start(FrameSink sink, StreamOptions options) {
        Objects.requireNonNull(sink); Objects.requireNonNull(options);
        synchronized (lifecycle) {
            if (session != null && session.isActive()) { LOG.warn("start() while already streaming; ignored"); return; }
            session = new Session(sink, options);
            session.start();
        }
    }

    @Override public void stop() {
        Session s;
        synchronized (lifecycle) { s = session; session = null; }
        if (s != null) s.stopAndJoin();
    }

    // ---------------------------------------------------------------- discovery

    private SbiDeviceInfo resolveDevice() {
        List<SbiDeviceInfo> found;
        if (config.endpoint().isPresent()) {
            found = new SbiDiscovery(client, config.host(), 1, 1, 1).probe(config.endpoint().get());
        } else {
            found = new SbiDiscovery(client, config.host(), config.portFrom(), config.portTo(), config.discoveryParallelism()).discoverFaceDevices();
        }
        Optional<SbiDeviceInfo> pick = SbiDiscovery.select(found, config.deviceId().orElse(null), config.preferRegistration());
        if (pick.isEmpty()) {
            String where = config.endpoint().map(SbiEndpoint::toString).orElse(config.host() + ":" + config.portFrom() + "-" + config.portTo());
            String want = config.deviceId().map(id -> " with deviceId '" + id + "'").orElse("");
            throw new DeviceException(DeviceException.Kind.UNAVAILABLE, "no SBI face device" + want + " found at " + where + (found.isEmpty() ? "" : "; saw " + found));
        }
        SbiDeviceInfo d = pick.get();
        if (!d.isReady()) LOG.warn("selected device {} reports status '{}'", d.deviceId(), d.deviceStatus());
        device.set(d);
        return d;
    }

    // ---------------------------------------------------------------- session

    /** One start()..stop() lifetime: reader thread, delivery thread, queue. */
    private final class Session {
        private final FrameSink sink;
        private final StreamOptions options;
        private final DeliveryQueue queue;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicBoolean endSignalled = new AtomicBoolean();
        private final AtomicReference<SbiClient.Stream> openStream = new AtomicReference<>();
        private final Thread reader;
        private final Thread deliverer;
        private long sequence;
        private long lastFrameNanos;

        Session(FrameSink sink, StreamOptions options) {
            this.sink = sink; this.options = options;
            this.queue = new DeliveryQueue(options.maxQueuedFrames());
            reader = new Thread(this::readLoop, "sbi-stream-reader");
            deliverer = new Thread(this::deliverLoop, "sbi-stream-delivery");
            reader.setDaemon(true); deliverer.setDaemon(true);
        }

        boolean isActive() { return running.get() || reader.isAlive() || deliverer.isAlive(); }

        void start() { setHealth(DeviceHealth.connecting()); deliverer.start(); reader.start(); }

        void stopAndJoin() {
            if (!running.compareAndSet(true, false)) return;
            SbiClient.Stream s = openStream.getAndSet(null);
            if (s != null) s.close();                 // unblocks a read in progress
            reader.interrupt();
            if (Thread.currentThread() != reader) try { reader.join(10_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            setHealth(DeviceHealth.disconnected("stopped"));
            queue.close();
            if (Thread.currentThread() != deliverer) try { deliverer.join(10_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        private void setHealth(DeviceHealth h) {
            DeviceHealth prev = health.getAndSet(h);
            if (prev.state() != h.state() || !prev.reason().equals(h.reason())) {
                LOG.info("sbi-stream health {} -> {}", prev, h);
                queue.offerHealth(h);
            }
        }

        private void readLoop() {
            int attempt = 0;
            DeviceHealth terminal = null;
            try {
                while (running.get()) {
                    String failure;
                    try {
                        streamOnce();
                        if (!running.get()) break;
                        failure = "stream ended by device" + endReason;
                    } catch (SocketTimeoutException e) {
                        failure = "no data for " + options.stallTimeoutMillis() + " ms";
                        setHealth(DeviceHealth.degraded(failure));
                    } catch (EOFException e) {
                        failure = "connection closed: " + e.getMessage();
                    } catch (DeviceException e) {
                        failure = e.getMessage();
                    } catch (IOException e) {
                        failure = e.getClass().getSimpleName() + ": " + e.getMessage();
                    }
                    if (!running.get()) break;
                    closeStream();
                    if (resetOnSuccess) attempt = 0;      // consecutive failures only
                    attempt++;
                    if (attempt > options.reconnectAttempts()) {
                        terminal = DeviceHealth.failed("gave up after " + options.reconnectAttempts() + " reconnect attempts: " + failure);
                        break;
                    }
                    long delay = backoffMillis(attempt);
                    setHealth(DeviceHealth.reconnecting("attempt " + attempt + "/" + options.reconnectAttempts() + " in " + delay + " ms: " + failure));
                    reconnects.incrementAndGet();
                    // Rediscover on every reconnect, also for a pinned endpoint: a restarted service may have moved
                    // port (scan) or expose a different deviceId behind the same port, and streaming a stale id
                    // gets SBI error 605 forever (found against MockMDS, see docs/testing/interop-mockmds.md).
                    device.set(null);
                    Thread.sleep(delay);
                }
            } catch (InterruptedException e) {
                // stop() requested
            } catch (RuntimeException e) {
                LOG.error("sbi-stream reader crashed", e);
                terminal = DeviceHealth.failed("reader error: " + e.getMessage());
            } finally {
                closeStream();
                // When stop() drove the shutdown it reports the final health and closes the queue itself;
                // here we only finish sessions that ended on their own (device gone, retries exhausted).
                if (running.compareAndSet(true, false)) {
                    setHealth(terminal != null ? terminal : DeviceHealth.disconnected("stream ended"));
                    queue.close();
                }
            }
        }

        private volatile boolean resetOnSuccess;
        /** SBI error the service appended after a clean end of stream (MockMDS: 609 on timeout), or "". */
        private volatile String endReason = "";

        /** Connect, then read parts until the stream ends or a fault interrupts it. */
        private void streamOnce() throws IOException {
            resetOnSuccess = false;
            endReason = "";
            SbiDeviceInfo d = device.get();
            if (d == null) d = resolveDevice();
            int subId = config.deviceSubId().orElse(d.firstSubId());
            int readTimeout = (int) Math.max(1, Math.min(Integer.MAX_VALUE, options.stallTimeoutMillis()));
            SignedPreviewVerifier verifier = null;
            if (config.signedPreview() != SbiStreamConfig.SignedPreview.OFF) {
                Optional<String> key = deviceSigningKey(d);
                if (key.isPresent()) {
                    try { verifier = new SignedPreviewVerifier(key.get()); }
                    catch (java.security.GeneralSecurityException e) { LOG.warn("signed preview: unusable device key: {}", e.toString()); }
                }
            }
            SbiClient.Stream s = client.openStream(d.endpoint(), d.deviceId(), subId, config.streamRequestTimeoutMs(), readTimeout,
                    verifier == null ? null : verifier.requestFields());
            if (!running.get()) { s.close(); return; }
            openStream.set(s);
            LOG.info("STREAM open on {} ({}), boundary from '{}'", d.endpoint(), d.deviceId(), s.contentType());
            MultipartStreamReader.Part part;
            boolean first = true;
            while (running.get() && (part = s.reader().next()) != null) {
                if (part.isEmpty()) { LOG.debug("skipping zero-length part"); continue; }
                if ("stream-init".equalsIgnoreCase(part.headers().getOrDefault("x-sbi-part", ""))) {
                    if (verifier != null && !verifier.acceptInit(part.body())) verifier = null;   // frames stay unsigned
                    continue;
                }
                if (first) { setHealth(DeviceHealth.connected()); resetOnSuccess = true; first = false; }
                else if (health.get().state() == DeviceHealth.State.DEGRADED) setHealth(DeviceHealth.connected());
                publish(part.body(), verifier == null ? null : verifier.verify(part.headers(), part.body()).orElse(null));
            }
            byte[] tail = s.reader().trailer();
            if (tail.length > 0) endReason = SbiClient.sbiError(new String(tail, java.nio.charset.StandardCharsets.ISO_8859_1));
        }

        private void publish(byte[] jpeg, byte[] deviceSignature) {
            long now = System.nanoTime();
            int[] wh = JpegDimensions.of(jpeg).orElse(null);
            if (wh != null) observedSize.set(wh);
            else { wh = observedSize.get(); LOG.debug("frame without readable JPEG header ({} bytes)", jpeg.length); }
            if (wh == null) wh = new int[] {FALLBACK_WIDTH, FALLBACK_HEIGHT};
            if (lastFrameNanos != 0) {
                double inst = 1e9 / Math.max(1, now - lastFrameNanos);
                observedFps = observedFps == 0 ? inst : observedFps * 0.9 + inst * 0.1;
            }
            lastFrameNanos = now;
            FaceFrame frame = FaceFrame.builder().sequence(sequence++).timestampNanos(now)
                    .width(wh[0]).height(wh[1]).format(PixelFormat.MJPEG).pixels(jpeg).deviceSignature(deviceSignature).build();
            framesReceived.incrementAndGet();
            if (queue.offer(frame)) framesDropped.incrementAndGet();
        }

        private void closeStream() {
            SbiClient.Stream s = openStream.getAndSet(null);
            if (s != null) s.close();
        }

        private long backoffMillis(int attempt) {
            long base = Math.min(config.backoffMaxMs(), config.backoffInitialMs() * (1L << Math.min(20, attempt - 1)));
            long jitter = ThreadLocalRandom.current().nextLong(base / 4 + 1);
            return Math.min(config.backoffMaxMs(), base + jitter);
        }

        private void deliverLoop() {
            try {
                while (true) {
                    DeliveryQueue.Item item = queue.poll(250);
                    if (item == null) { if (queue.isClosed()) break; continue; }
                    try {
                        if (item.health() != null) sink.onHealth(item.health()); else sink.onFrame(item.frame());
                    } catch (RuntimeException e) { LOG.warn("sink threw", e); }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (endSignalled.compareAndSet(false, true)) {
                    try { sink.onEnd(); } catch (RuntimeException e) { LOG.warn("sink.onEnd threw", e); }
                }
            }
        }
    }
}
