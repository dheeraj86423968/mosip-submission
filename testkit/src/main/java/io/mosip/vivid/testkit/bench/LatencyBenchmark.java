package io.mosip.vivid.testkit.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosip.vivid.api.FrameAnalysis;
import io.mosip.vivid.api.FrameAnalyzer;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.PixelFormat;
import io.mosip.vivid.device.StreamOptions;
import io.mosip.vivid.device.mock.MockFaceFrameSource;
import io.mosip.vivid.device.mock.Pacing;
import io.mosip.vivid.device.mock.scenario.Scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Feeds N frames through a {@link FrameAnalyzer} and reports per-frame latency percentiles, both as measured
 * around {@code analyze()} (wall) and as the analyzer itself reports in {@link FrameAnalysis#processingNanos()}.
 * Frames are rendered up front so rendering cost never leaks into the numbers; a warm-up pass is excluded.
 */
public final class LatencyBenchmark {
    /** Benchmark output. */
    public record Result(String analyzerName, int frames, int warmup, PixelFormat format, int width, int height, LatencyStats wall, LatencyStats reported) {
        public ObjectNode toJsonNode() {
            ObjectNode n = new ObjectMapper().createObjectNode();
            n.put("analyzer", analyzerName).put("frames", frames).put("warmup", warmup).put("format", format.name()).put("width", width).put("height", height);
            put(n.putObject("wallNanos"), wall);
            put(n.putObject("reportedNanos"), reported);
            return n;
        }
        private static void put(ObjectNode o, LatencyStats s) {
            o.put("p50", s.p50()).put("p95", s.p95()).put("p99", s.p99()).put("mean", s.mean()).put("max", s.max()).put("count", s.count());
        }
        @Override public String toString() { return analyzerName + " " + width + "x" + height + " " + format + ": wall[" + wall + "] reported[" + reported + "]"; }
    }

    private final int warmup;

    /** @param warmup frames analysed before measurement starts (JIT, model warm-up) */
    public LatencyBenchmark(int warmup) { this.warmup = Math.max(0, warmup); }

    public LatencyBenchmark() { this(10); }

    /** Render {@code frames} frames of the scenario (looping if needed) in the given format. */
    public static List<FaceFrame> renderFrames(Scenario scenario, int frames, PixelFormat format) throws InterruptedException {
        List<FaceFrame> out = new ArrayList<>(frames);
        CountDownLatch done = new CountDownLatch(1);
        MockFaceFrameSource src = MockFaceFrameSource.builder(scenario).pacing(Pacing.unthrottled()).format(format).build();
        src.start(new io.mosip.vivid.device.FrameSink() {
            @Override public void onFrame(FaceFrame f) {
                synchronized (out) { if (out.size() < frames) out.add(copy(f)); if (out.size() >= frames) done.countDown(); }
            }
            @Override public void onEnd() { done.countDown(); }
        }, StreamOptions.defaults());
        done.await(5, TimeUnit.MINUTES);
        src.stop();
        synchronized (out) { return List.copyOf(out); }
    }

    private static FaceFrame copy(FaceFrame f) {
        byte[] px = new byte[f.pixels().remaining()];
        f.pixels().get(px);
        return FaceFrame.builder().sequence(f.sequence()).timestampNanos(f.timestampNanos()).width(f.width()).height(f.height()).format(f.format()).pixels(px).build();
    }

    /** Run on frames rendered from a scenario. */
    public Result run(FrameAnalyzer analyzer, String analyzerName, Scenario scenario, int frames, PixelFormat format) throws InterruptedException {
        return run(analyzer, analyzerName, renderFrames(scenario, frames + warmup, format));
    }

    /** Run on pre-built frames (the first {@code warmup} are excluded from the statistics). */
    public Result run(FrameAnalyzer analyzer, String analyzerName, List<FaceFrame> frames) {
        Objects.requireNonNull(analyzer);
        if (frames.size() <= warmup) throw new IllegalArgumentException("need more than " + warmup + " frames, got " + frames.size());
        int n = frames.size() - warmup;
        long[] wall = new long[n], reported = new long[n];
        for (int i = 0; i < frames.size(); i++) {
            long t0 = System.nanoTime();
            FrameAnalysis a = analyzer.analyze(frames.get(i));
            long dt = System.nanoTime() - t0;
            if (i >= warmup) { wall[i - warmup] = dt; reported[i - warmup] = a == null ? 0 : a.processingNanos(); }
        }
        FaceFrame f0 = frames.get(0);
        return new Result(analyzerName, n, warmup, f0.format(), f0.width(), f0.height(), new LatencyStats(wall), new LatencyStats(reported));
    }
}
