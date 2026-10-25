package io.mosip.vivid.device.vendor;

import io.mosip.vivid.device.DeviceCapabilities;
import io.mosip.vivid.device.PixelFormat;

import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A pretend vendor SDK: a callback-style preview on its own thread producing RGB888 gradient frames, with fault
 * injection. It mimics the shape of real camera SDKs (open/startPreview/stopPreview/close, errors via callback,
 * a buffer it reuses between frames so the adapter must copy).
 */
final class FakeVendorSdk implements VendorSdk {
    final int width, height;
    final double fps;
    /** open() fails this many more times. */
    final AtomicInteger failOpens = new AtomicInteger();
    /** After this many frames of the current preview, report onError once (-1 = never). */
    volatile int errorAfterFrames = -1;
    /** After this many frames of the current preview, go silent once (-1 = never). */
    volatile int stallAfterFrames = -1;
    final AtomicInteger opens = new AtomicInteger(), closes = new AtomicInteger(), previews = new AtomicInteger();
    private volatile boolean isOpen;
    private Thread previewThread;
    private volatile boolean previewing;

    FakeVendorSdk(int width, int height, double fps) { this.width = width; this.height = height; this.fps = fps; }

    @Override public VendorDeviceInfo open() throws VendorSdkException {
        if (failOpens.getAndUpdate(n -> Math.max(0, n - 1)) > 0) throw new VendorSdkException("device not found", "E_NODEV", null);
        opens.incrementAndGet();
        isOpen = true;
        return VendorDeviceInfo.builder("Acme Biometrics", "FaceCam 3000").serial("FC3K-0001")
                .certification(DeviceCapabilities.CertificationLevel.L1).resolution(width, height).fps(fps)
                .formats(EnumSet.of(PixelFormat.RGB888)).build();
    }

    @Override public synchronized void startPreview(PreviewListener listener) throws VendorSdkException {
        if (!isOpen) throw new VendorSdkException("not open");
        previews.incrementAndGet();
        previewing = true;
        final byte[] sdkBuffer = new byte[width * height * 3];       // reused like a native ring slot
        previewThread = new Thread(() -> {
            long periodNanos = (long) (1e9 / fps);
            long next = System.nanoTime();
            int n = 0;
            boolean stalled = false;
            while (previewing) {
                if (stallAfterFrames >= 0 && n == stallAfterFrames) { stallAfterFrames = -1; stalled = true; }
                if (errorAfterFrames >= 0 && n == errorAfterFrames) {
                    errorAfterFrames = -1;
                    listener.onError(new VendorSdkException("USB transfer error", "E_IO", null));
                }
                if (!stalled) {
                    for (int i = 0; i < sdkBuffer.length; i += 3) { sdkBuffer[i] = (byte) n; sdkBuffer[i + 1] = (byte) (i / 3 % width); sdkBuffer[i + 2] = (byte) 128; }
                    listener.onFrame(VendorFrame.copyOf(width, height, PixelFormat.RGB888, java.nio.ByteBuffer.wrap(sdkBuffer)));
                    n++;
                }
                next += periodNanos;
                long sleep = next - System.nanoTime();
                if (sleep > 0) try { Thread.sleep(sleep / 1_000_000, (int) (sleep % 1_000_000)); } catch (InterruptedException e) { return; }
            }
        }, "fake-vendor-preview");
        previewThread.setDaemon(true);
        previewThread.start();
    }

    @Override public synchronized void stopPreview() {
        previewing = false;
        Thread t = previewThread;
        previewThread = null;
        if (t != null && t != Thread.currentThread()) { t.interrupt(); try { t.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    }

    @Override public void close() {
        if (isOpen) { isOpen = false; closes.incrementAndGet(); }
    }
}
