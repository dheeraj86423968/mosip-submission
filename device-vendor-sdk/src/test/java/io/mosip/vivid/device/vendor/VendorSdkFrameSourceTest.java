package io.mosip.vivid.device.vendor;

import io.mosip.vivid.device.DeviceCapabilities;
import io.mosip.vivid.device.DeviceException;
import io.mosip.vivid.device.DeviceHealth;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.FrameSink;
import io.mosip.vivid.device.PixelFormat;
import io.mosip.vivid.device.StreamOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fault handling of the template adapter, driven by {@link FakeVendorSdk}'s fault injection. */
class VendorSdkFrameSourceTest {
    static final class Sink implements FrameSink {
        final List<FaceFrame> frames = new CopyOnWriteArrayList<>();
        final List<DeviceHealth> health = new CopyOnWriteArrayList<>();
        final AtomicInteger ends = new AtomicInteger();
        final long sleepMs;
        Sink(long sleepMs) { this.sleepMs = sleepMs; }
        @Override public void onFrame(FaceFrame f) {
            frames.add(f);
            if (sleepMs > 0) try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        @Override public void onHealth(DeviceHealth h) { health.add(h); }
        @Override public void onEnd() { ends.incrementAndGet(); }
        List<DeviceHealth.State> states() { return health.stream().map(DeviceHealth::state).collect(Collectors.toList()); }
        boolean await(java.util.function.BooleanSupplier c, long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            while (!c.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(10);
            return c.getAsBoolean();
        }
    }

    private VendorSdkFrameSource source;
    @AfterEach void stop() { if (source != null) source.stop(); }

    private VendorSdkFrameSource source(FakeVendorSdk sdk) {
        source = new VendorSdkFrameSource("fake-vendor", sdk, new AbstractFrameSourceAdapter.Backoff(20, 100));
        return source;
    }

    @Test
    void capabilitiesComeFromTheSdkAndFramesAreCopiedOutOfItsBuffer() throws Exception {
        FakeVendorSdk sdk = new FakeVendorSdk(160, 120, 50);
        DeviceCapabilities caps = source(sdk).capabilities();
        assertThat(caps.vendor()).isEqualTo("Acme Biometrics");
        assertThat(caps.certificationLevel()).isEqualTo(DeviceCapabilities.CertificationLevel.L1);
        assertThat(caps.width()).isEqualTo(160);
        assertThat(caps.formats()).containsExactly(PixelFormat.RGB888);
        assertThat(sdk.opens.get()).isEqualTo(sdk.closes.get());       // describe() opened and closed again
        Sink sink = new Sink(0);
        source.start(sink, new StreamOptions(0, 8, 1000, 3));
        assertThat(sink.await(() -> sink.frames.size() >= 5, 5000)).isTrue();
        source.stop();
        // the SDK reuses one buffer and writes the frame counter into byte 0: copies must keep distinct values
        assertThat(sink.frames.stream().map(f -> f.pixels().get(0)).distinct().count()).isGreaterThan(1);
        assertThat(sink.states()).startsWith(DeviceHealth.State.CONNECTING, DeviceHealth.State.CONNECTED).endsWith(DeviceHealth.State.DISCONNECTED);
        assertThat(sdk.opens.get()).isEqualTo(sdk.closes.get());
    }

    @Test
    void sdkErrorReconnectsAndSequenceKeepsIncreasing() throws Exception {
        FakeVendorSdk sdk = new FakeVendorSdk(64, 48, 60);
        sdk.errorAfterFrames = 5;
        Sink sink = new Sink(0);
        source(sdk).start(sink, new StreamOptions(0, 8, 1000, 3));
        assertThat(sink.await(() -> sink.frames.size() >= 15 && sdk.previews.get() >= 2, 5000)).isTrue();
        assertThat(sink.states()).containsSubsequence(DeviceHealth.State.CONNECTED, DeviceHealth.State.RECONNECTING, DeviceHealth.State.CONNECTED);
        assertThat(sink.health).anySatisfy(h -> assertThat(h.reason()).contains("USB transfer error").contains("E_IO"));
        for (int i = 1; i < sink.frames.size(); i++) assertThat(sink.frames.get(i).sequence()).isGreaterThan(sink.frames.get(i - 1).sequence());
        assertThat(source.reconnects()).isEqualTo(1);
    }

    @Test
    void stallIsDegradedThenReconnected() throws Exception {
        FakeVendorSdk sdk = new FakeVendorSdk(64, 48, 60);
        sdk.stallAfterFrames = 3;
        Sink sink = new Sink(0);
        source(sdk).start(sink, new StreamOptions(0, 8, 300, 3));
        assertThat(sink.await(() -> sink.frames.size() >= 10, 5000)).isTrue();
        assertThat(sink.states()).containsSubsequence(DeviceHealth.State.CONNECTED, DeviceHealth.State.DEGRADED,
                DeviceHealth.State.RECONNECTING, DeviceHealth.State.CONNECTED);
    }

    @Test
    void openFailuresAreRetriedThenGiveUpWithOneOnEnd() throws Exception {
        FakeVendorSdk sdk = new FakeVendorSdk(64, 48, 60);
        sdk.failOpens.set(2);
        Sink ok = new Sink(0);
        source(sdk).start(ok, new StreamOptions(0, 8, 1000, 3));
        assertThat(ok.await(() -> ok.frames.size() >= 3, 5000)).as("recovers after two failed opens").isTrue();
        source.stop();

        FakeVendorSdk dead = new FakeVendorSdk(64, 48, 60);
        dead.failOpens.set(Integer.MAX_VALUE);
        assertThatThrownBy(() -> source(dead).capabilities()).isInstanceOfSatisfying(DeviceException.class,
                e -> assertThat(e.kind()).isEqualTo(DeviceException.Kind.UNAVAILABLE));
        Sink sink = new Sink(0);
        source.start(sink, new StreamOptions(0, 8, 1000, 2));
        assertThat(sink.await(() -> sink.ends.get() == 1, 5000)).isTrue();
        assertThat(source.health().state()).isEqualTo(DeviceHealth.State.FAILED);
        assertThat(source.health().reason()).contains("gave up after 2").contains("E_NODEV");
        assertThat(sink.states()).containsExactly(DeviceHealth.State.CONNECTING, DeviceHealth.State.RECONNECTING,
                DeviceHealth.State.RECONNECTING, DeviceHealth.State.FAILED);
        source.stop();
        Thread.sleep(50);
        assertThat(sink.ends.get()).isEqualTo(1);
    }

    @Test
    void slowSinkDropsOldestAndTargetFpsDecimates() throws Exception {
        FakeVendorSdk sdk = new FakeVendorSdk(64, 48, 100);
        Sink slow = new Sink(50);
        source(sdk).start(slow, new StreamOptions(0, 2, 1000, 3));
        assertThat(slow.await(() -> source.framesDropped() > 5, 5000)).isTrue();
        source.stop();
        assertThat(slow.ends.get()).isEqualTo(1);

        Sink sink = new Sink(0);
        FakeVendorSdk fast = new FakeVendorSdk(64, 48, 100);
        source(fast).start(sink, new StreamOptions(10, 8, 1000, 3));
        Thread.sleep(1000);
        source.stop();
        assertThat(source.framesDecimated()).isGreaterThan(30);
        assertThat(sink.frames.size()).isBetween(6, 14);       // ~10 fps for one second
    }
}
