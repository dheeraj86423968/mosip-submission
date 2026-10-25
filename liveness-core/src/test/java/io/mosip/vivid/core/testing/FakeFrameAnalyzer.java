package io.mosip.vivid.core.testing;

import io.mosip.vivid.api.Challenge;
import io.mosip.vivid.api.FrameAnalysis;
import io.mosip.vivid.api.FrameAnalyzer;
import io.mosip.vivid.device.FaceFrame;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/**
 * Scripted {@link FrameAnalyzer}: returns queued {@link FrameAnalysis} objects in order, then falls back to a
 * default supplier. Records challenge notifications, resets and closes, and can be made to throw.
 */
public final class FakeFrameAnalyzer implements FrameAnalyzer {

    private final Deque<FrameAnalysis> script = new ArrayDeque<>();
    private final List<Challenge> challenges = new ArrayList<>();
    private Supplier<FrameAnalysis> fallback = () -> { throw new IllegalStateException("script exhausted"); };
    private RuntimeException failure;
    private boolean returnNull;
    private int resets;
    private int closes;
    private int analyzed;

    /** Queues one analysis. */
    public FakeFrameAnalyzer enqueue(FrameAnalysis a) {
        script.addLast(a);
        return this;
    }

    /** Queues several analyses. */
    public FakeFrameAnalyzer enqueueAll(List<FrameAnalysis> list) {
        script.addAll(list);
        return this;
    }

    /** Analysis returned when the script is empty. */
    public FakeFrameAnalyzer fallback(Supplier<FrameAnalysis> s) {
        fallback = s;
        return this;
    }

    /** Next analyze() throws. */
    public FakeFrameAnalyzer failNext(RuntimeException e) {
        failure = e;
        return this;
    }

    /** Next analyze() returns null. */
    public FakeFrameAnalyzer returnNullNext() {
        returnNull = true;
        return this;
    }

    @Override
    public FrameAnalysis analyze(FaceFrame frame) {
        analyzed++;
        if (failure != null) {
            RuntimeException e = failure;
            failure = null;
            throw e;
        }
        if (returnNull) {
            returnNull = false;
            return null;
        }
        FrameAnalysis a = script.pollFirst();
        return a != null ? a : fallback.get();
    }

    @Override
    public void onChallenge(Challenge challenge) {
        challenges.add(challenge);
    }

    @Override
    public void reset() {
        resets++;
    }

    @Override
    public void close() {
        closes++;
    }

    public List<Challenge> challenges() { return challenges; }
    public int resets() { return resets; }
    public int closes() { return closes; }
    public int analyzed() { return analyzed; }
    public int remaining() { return script.size(); }
}
