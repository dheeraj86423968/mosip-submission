package io.mosip.vivid.demo;

import io.mosip.vivid.device.DeviceHealth;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.FrameSink;
import io.mosip.vivid.device.StreamOptions;
import io.mosip.vivid.device.mock.Pacing;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AttackLabSourceTest {

    @Test
    void switchingSubjectsKeepsOneContinuousStreamAndHidesTheSwap() throws Exception {
        DemoClock clock = new DemoClock();
        List<FaceFrame> frames = new CopyOnWriteArrayList<>();
        List<DeviceHealth> health = new CopyOnWriteArrayList<>();
        AtomicInteger ends = new AtomicInteger();
        AtomicInteger clockLag = new AtomicInteger();
        CountDownLatch enough = new CountDownLatch(1);
        AttackLabSource[] src = new AttackLabSource[1];
        src[0] = new AttackLabSource(Subject.LIVE, Optional::empty, Pacing.unthrottled(), clock, f -> {
            if (f.sequence() == 10) src[0].switchTo(Subject.PRINT);
            if (f.sequence() == 20) src[0].switchTo(Subject.VIDEO_REPLAY);
            if (f.sequence() == 30) enough.countDown();
        });
        src[0].start(new FrameSink() {
            @Override public void onFrame(FaceFrame f) {
                if (clock.nanoTime() != f.timestampNanos()) clockLag.incrementAndGet();   // clock must lead the session
                frames.add(f);
            }
            @Override public void onHealth(DeviceHealth h) { health.add(h); }
            @Override public void onEnd() { ends.incrementAndGet(); }
        }, StreamOptions.defaults());
        assertThat(enough.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(src[0].subject()).isEqualTo(Subject.VIDEO_REPLAY);
        src[0].stop();

        assertThat(frames.size()).isGreaterThanOrEqualTo(31);
        for (int i = 1; i < frames.size(); i++) {
            assertThat(frames.get(i).sequence()).isEqualTo(frames.get(i - 1).sequence() + 1);
            long gap = frames.get(i).timestampNanos() - frames.get(i - 1).timestampNanos();
            assertThat(gap).isBetween(1L, 200_000_000L);
        }
        assertThat(frames.get(0).width()).isEqualTo(640);
        // only the first source's handshake reaches the session; the swaps produce no disconnect or end of stream
        assertThat(health).extracting(DeviceHealth::state).isSubsetOf(DeviceHealth.State.CONNECTING, DeviceHealth.State.CONNECTED);
        assertThat(ends.get()).isZero();
        assertThat(clockLag.get()).isZero();
    }
}
