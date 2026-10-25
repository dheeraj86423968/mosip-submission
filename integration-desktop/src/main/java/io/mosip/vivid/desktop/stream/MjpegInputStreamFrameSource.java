package io.mosip.vivid.desktop.stream;

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

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link FaceFrameSource} over the {@code InputStream} that the Registration Client already obtains from
 * {@code BioService.getStream(device, "FACE_FULL FACE")} (SBI {@code STREAM} verb). It parses the same multipart
 * MJPEG framing that {@code io.mosip.registration.controller.device.Streamer#retrieveNextImage} parses
 * ({@code Content-Length: N} header, then the JPEG starting at the next {@code 0xFF}), so it is a drop-in
 * replacement for the Streamer thread for the face modality.
 * <p>
 * Frames are delivered as {@link PixelFormat#MJPEG}; the engine decodes. The stream is not signed by SBI 0.9.5/1.0,
 * so {@code deviceSignature} is never set and {@code DeviceCapabilities.signsFrames()} is false.
 * <p>
 * This is the desktop-only bridge. When the {@code device-sbi-stream} module (which owns reconnect and the
 * {@code /stream} HTTP call itself) is available it should be preferred; this class never opens the HTTP connection,
 * it only consumes a stream someone else opened.
 */
public final class MjpegInputStreamFrameSource implements FaceFrameSource {
    private static final Logger LOG = LoggerFactory.getLogger(MjpegInputStreamFrameSource.class);
    public static final String ADAPTER_ID = "sbi-stream-mjpeg";
    private static final byte[] CONTENT_LENGTH = "Content-Length:".getBytes();
    private static final int MAX_HEADER = 64 * 1024;
    private static final int MAX_FRAME = 16 * 1024 * 1024;

    private final InputStream in;
    private final DeviceCapabilities.Builder capabilities;
    private final AtomicReference<DeviceHealth> health = new AtomicReference<>(DeviceHealth.connecting());
    private final AtomicReference<DeviceCapabilities> resolved = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong sequence = new AtomicLong();
    private volatile Thread thread;

    /**
     * @param in           the SBI stream, positioned anywhere before a frame boundary (the client has usually
     *                     already consumed the first frame to check liveness of the stream; that is fine)
     * @param capabilities vendor/model/serial/spec version from {@code MdmBioDevice}; resolution and fps are
     *                     filled from the first decoded frame
     */
    public MjpegInputStreamFrameSource(InputStream in, DeviceCapabilities.Builder capabilities) {
        this.in = Objects.requireNonNull(in, "in");
        this.capabilities = capabilities == null ? DeviceCapabilities.builder() : capabilities;
        this.capabilities.formats(Set.of(PixelFormat.MJPEG)).signsFrames(false);
    }

    @Override public String adapterId() { return ADAPTER_ID; }

    @Override public DeviceCapabilities capabilities() {
        DeviceCapabilities c = resolved.get();
        return c != null ? c : capabilities.build();
    }

    @Override public void start(FrameSink sink, StreamOptions options) {
        Objects.requireNonNull(sink, "sink");
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("already started");
        Thread t = new Thread(() -> deliver(sink, options == null ? StreamOptions.defaults() : options), "VIVID-SBI-STREAM");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    private void deliver(FrameSink sink, StreamOptions options) {
        long minIntervalNanos = options.targetFps() > 0 ? 1_000_000_000L / options.targetFps() : 0;
        long lastDelivered = 0;
        try {
            while (running.get()) {
                byte[] jpeg = readFrame();
                if (jpeg == null) break;
                long now = System.nanoTime();
                if (minIntervalNanos > 0 && lastDelivered != 0 && now - lastDelivered < minIntervalNanos) continue; // drop to target fps
                int[] dims = Jpeg.dimensions(jpeg);
                if (dims.length != 2) {
                    if (health.get().state() == DeviceHealth.State.CONNECTED) setHealth(sink, DeviceHealth.degraded("undecodable frame header"));
                    continue;
                }
                if (resolved.get() == null) {
                    resolved.set(capabilities.resolution(dims[0], dims[1]).nominalFps(options.targetFps()).build());
                }
                if (health.get().state() != DeviceHealth.State.CONNECTED) setHealth(sink, DeviceHealth.connected());
                FaceFrame frame = FaceFrame.builder().sequence(sequence.incrementAndGet()).timestampNanos(now)
                        .width(dims[0]).height(dims[1]).format(PixelFormat.MJPEG).pixels(jpeg).build();
                lastDelivered = now;
                sink.onFrame(frame);
            }
            if (running.get()) setHealth(sink, DeviceHealth.disconnected("stream ended"));
        } catch (IOException e) {
            if (running.get()) {
                LOG.warn("SBI stream read failed: {}", e.toString());
                setHealth(sink, DeviceHealth.failed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        } catch (DeviceException e) {
            LOG.warn("SBI stream protocol error: {}", e.getMessage());
            setHealth(sink, DeviceHealth.failed(e.getMessage()));
        } catch (RuntimeException e) {
            LOG.error("frame sink threw; stopping stream", e);
            setHealth(sink, DeviceHealth.failed("sink error: " + e));
        } finally {
            running.set(false);
            closeQuietly();
            try { sink.onEnd(); } catch (RuntimeException ignored) { }
        }
    }

    private void setHealth(FrameSink sink, DeviceHealth h) {
        health.set(h);
        try { sink.onHealth(h); } catch (RuntimeException e) { LOG.warn("sink.onHealth threw", e); }
    }

    /** Returns the next JPEG or null at EOF. Package-private for tests. */
    byte[] readFrame() throws IOException {
        // 1. scan header bytes until "Content-Length:" then read digits until CR/LF
        int matched = 0, consumed = 0, b;
        while (true) {
            b = in.read();
            if (b < 0) return null;
            if (++consumed > MAX_HEADER) throw new DeviceException(DeviceException.Kind.PROTOCOL_ERROR, "no Content-Length header within " + MAX_HEADER + " bytes");
            if (b == CONTENT_LENGTH[matched]) { if (++matched == CONTENT_LENGTH.length) break; }
            else matched = (b == CONTENT_LENGTH[0]) ? 1 : 0;
        }
        StringBuilder digits = new StringBuilder();
        while (true) {
            b = in.read();
            if (b < 0) return null;
            if (b == '\r' || b == '\n') { if (digits.length() > 0) break; else continue; }
            if (b != ' ') digits.append((char) b);
            if (digits.length() > 12) throw new DeviceException(DeviceException.Kind.PROTOCOL_ERROR, "bad Content-Length");
        }
        int length;
        try { length = Integer.parseInt(digits.toString()); }
        catch (NumberFormatException e) { throw new DeviceException(DeviceException.Kind.PROTOCOL_ERROR, "bad Content-Length '" + digits + "'"); }
        if (length <= 0 || length > MAX_FRAME) throw new DeviceException(DeviceException.Kind.PROTOCOL_ERROR, "unreasonable frame length " + length);
        // 2. skip to the JPEG SOI (first 0xFF), exactly like Streamer.retrieveNextImage
        int skipped = 0;
        while (true) {
            b = in.read();
            if (b < 0) return null;
            if (b == 0xFF) break;
            if (++skipped > MAX_HEADER) throw new DeviceException(DeviceException.Kind.PROTOCOL_ERROR, "no JPEG start after header");
        }
        // 3. read the remaining length-1 bytes
        ByteArrayOutputStream out = new ByteArrayOutputStream(length);
        out.write(0xFF);
        byte[] buf = new byte[8192];
        int remaining = length - 1;
        while (remaining > 0) {
            int n = in.read(buf, 0, Math.min(buf.length, remaining));
            if (n < 0) throw new EOFException("stream ended mid-frame");
            out.write(buf, 0, n);
            remaining -= n;
        }
        return out.toByteArray();
    }

    @Override public void stop() {
        if (!running.compareAndSet(true, false)) { closeQuietly(); return; }
        closeQuietly();                         // unblocks the reader
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        health.set(DeviceHealth.disconnected("stopped"));
    }

    private void closeQuietly() {
        try { in.close(); } catch (IOException ignored) { }
    }

    @Override public DeviceHealth health() { return health.get(); }
}
