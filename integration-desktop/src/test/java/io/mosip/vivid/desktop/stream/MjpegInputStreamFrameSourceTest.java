package io.mosip.vivid.desktop.stream;

import io.mosip.vivid.device.DeviceCapabilities;
import io.mosip.vivid.device.DeviceHealth;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.FrameSink;
import io.mosip.vivid.device.PixelFormat;
import io.mosip.vivid.device.StreamOptions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class MjpegInputStreamFrameSourceTest {

    public static byte[] jpeg(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, (x * 7 + y * 13) & 0xFFFFFF);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(img, "jpeg", out)).isTrue();
        return out.toByteArray();
    }

    /** Same framing MockMDS / Streamer use: boundary, Content-Type, Content-Length, blank line, JPEG. */
    public static byte[] multipart(byte[]... frames) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] f : frames) {
            out.write(("--boundary\r\nContent-Type: image/jpeg\r\nContent-Length: " + f.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(f);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        return out.toByteArray();
    }

    @Test
    void jpegDimensionsAreParsedFromSof() throws IOException {
        assertThat(Jpeg.dimensions(jpeg(64, 48))).containsExactly(64, 48);
        assertThat(Jpeg.dimensions(new byte[] { 1, 2, 3 })).isEmpty();
        assertThat(Jpeg.isJpeg(jpeg(8, 8))).isTrue();
    }

    @Test
    void deliversEveryFrameWithDimensionsThenEndsOnce() throws Exception {
        byte[] a = jpeg(320, 240), b = jpeg(320, 240), c = jpeg(640, 480);
        MjpegInputStreamFrameSource src = new MjpegInputStreamFrameSource(new ByteArrayInputStream(multipart(a, b, c)),
                DeviceCapabilities.builder().vendor("MockMDS").model("face").deviceId("SN1").sbiSpecVersion("1.0"));
        List<FaceFrame> frames = new CopyOnWriteArrayList<>();
        List<DeviceHealth> health = new CopyOnWriteArrayList<>();
        AtomicInteger ends = new AtomicInteger();
        CountDownLatch ended = new CountDownLatch(1);
        src.start(new FrameSink() {
            @Override public void onFrame(FaceFrame frame) { frames.add(frame); }
            @Override public void onHealth(DeviceHealth h) { health.add(h); }
            @Override public void onEnd() { ends.incrementAndGet(); ended.countDown(); }
        }, new StreamOptions(0, 4, 3000, 0));
        assertThat(ended.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(frames).hasSize(3);
        assertThat(frames.get(0).format()).isEqualTo(PixelFormat.MJPEG);
        assertThat(frames.get(0).width()).isEqualTo(320);
        assertThat(frames.get(2).height()).isEqualTo(480);
        assertThat(frames.get(0).sequence()).isEqualTo(1);
        assertThat(frames.get(2).sequence()).isEqualTo(3);
        byte[] delivered = new byte[frames.get(0).pixels().remaining()];
        frames.get(0).pixels().get(delivered);
        assertThat(delivered).isEqualTo(a);
        assertThat(health.get(0).state()).isEqualTo(DeviceHealth.State.CONNECTED);
        assertThat(health.get(health.size() - 1).state()).isEqualTo(DeviceHealth.State.DISCONNECTED);
        assertThat(ends.get()).isEqualTo(1);
        assertThat(src.capabilities().width()).isEqualTo(320);
        assertThat(src.capabilities().vendor()).isEqualTo("MockMDS");
        assertThat(src.capabilities().signsFrames()).isFalse();
        src.stop(); src.stop(); // idempotent
    }

    /** Socket-like stream: read() blocks until data arrives or close() is called (closing unblocks, as a socket does). */
    static final class BlockingStream extends java.io.InputStream {
        private final java.util.concurrent.BlockingQueue<Integer> q = new java.util.concurrent.LinkedBlockingQueue<>();
        private volatile boolean closed;
        void feed(byte[] data) { for (byte b : data) q.add(b & 0xFF); }
        @Override public int read() throws IOException {
            while (!closed) {
                try { Integer v = q.poll(50, TimeUnit.MILLISECONDS); if (v != null) return v; }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
            }
            throw new IOException("Socket closed");
        }
        @Override public void close() { closed = true; }
    }

    @Test
    void stopUnblocksAReaderWaitingOnAnOpenStream() throws Exception {
        BlockingStream in = new BlockingStream();
        in.feed(multipart(jpeg(32, 32)));
        MjpegInputStreamFrameSource src = new MjpegInputStreamFrameSource(in, null);
        CountDownLatch first = new CountDownLatch(1), ended = new CountDownLatch(1);
        List<DeviceHealth> health = new CopyOnWriteArrayList<>();
        src.start(new FrameSink() {
            @Override public void onFrame(FaceFrame frame) { first.countDown(); }
            @Override public void onHealth(DeviceHealth h) { health.add(h); }
            @Override public void onEnd() { ended.countDown(); }
        }, StreamOptions.defaults());
        assertThat(first.await(5, TimeUnit.SECONDS)).isTrue();
        src.stop();                                   // closes the stream, which unblocks the reader
        assertThat(ended.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(src.health().state()).isEqualTo(DeviceHealth.State.DISCONNECTED);
        // a stop() initiated by us must not be reported as a device FAILURE to the sink
        assertThat(health).noneMatch(h -> h.state() == DeviceHealth.State.FAILED);
    }

    @Test
    void garbageBeforeHeaderIsSkippedAndBadLengthIsAProtocolError() throws Exception {
        byte[] a = jpeg(16, 16);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace;boundary=b\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        out.write(multipart(a));
        MjpegInputStreamFrameSource src = new MjpegInputStreamFrameSource(new ByteArrayInputStream(out.toByteArray()), null);
        assertThat(src.readFrame()).isEqualTo(a);
        assertThat(src.readFrame()).isNull();

        MjpegInputStreamFrameSource bad = new MjpegInputStreamFrameSource(new ByteArrayInputStream("Content-Length: abc\r\n\r\n".getBytes(StandardCharsets.US_ASCII)), null);
        org.assertj.core.api.Assertions.assertThatThrownBy(bad::readFrame).isInstanceOf(io.mosip.vivid.device.DeviceException.class);
    }
}
