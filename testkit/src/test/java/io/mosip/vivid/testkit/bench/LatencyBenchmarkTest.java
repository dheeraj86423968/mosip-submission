package io.mosip.vivid.testkit.bench;

import io.mosip.vivid.api.FaceQuality;
import io.mosip.vivid.api.FrameAnalysis;
import io.mosip.vivid.api.FrameAnalyzer;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.PixelFormat;
import io.mosip.vivid.device.mock.scenario.Scenarios;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LatencyBenchmarkTest {
    /** Spends a deterministic amount of time per frame and reports half of it. */
    static final class SleepyAnalyzer implements FrameAnalyzer {
        @Override public FrameAnalysis analyze(FaceFrame frame) {
            long t0 = System.nanoTime();
            while (System.nanoTime() - t0 < 1_000_000L) { /* 1 ms busy */ }
            return new FrameAnalysis(frame.sequence(), frame.timestampNanos(), FaceQuality.noFace(), null, null, null, 500_000L);
        }
        @Override public void reset() { }
    }

    @Test
    void percentilesAreOrderedAndReportedNanosArePassedThrough() throws Exception {
        LatencyBenchmark.Result r = new LatencyBenchmark(3).run(new SleepyAnalyzer(), "sleepy", Scenarios.bundled("live-pass"), 20, PixelFormat.RGB888);
        assertThat(r.frames()).isEqualTo(20);
        assertThat(r.width()).isEqualTo(640);
        assertThat(r.wall().p50()).isGreaterThanOrEqualTo(1_000_000L);
        assertThat(r.wall().p50()).isLessThanOrEqualTo(r.wall().p95());
        assertThat(r.wall().p95()).isLessThanOrEqualTo(r.wall().p99());
        assertThat(r.wall().p99()).isLessThanOrEqualTo(r.wall().max());
        assertThat(r.reported().p50()).isEqualTo(500_000L);
        assertThat(r.toJsonNode().get("wallNanos").get("count").asInt()).isEqualTo(20);
        assertThat(r.toString()).contains("sleepy");
    }

    @Test
    void nearestRankPercentiles() {
        LatencyStats s = new LatencyStats(new long[] {5, 1, 4, 2, 3});
        assertThat(s.p50()).isEqualTo(3);
        assertThat(s.percentile(100)).isEqualTo(5);
        assertThat(s.percentile(0)).isEqualTo(1);
        assertThat(s.mean()).isEqualTo(3.0);
    }
}
