package io.mosip.vivid.testkit.scenario;

import io.mosip.vivid.api.LivenessResult;
import io.mosip.vivid.api.LivenessSession;
import io.mosip.vivid.api.SessionContext;
import io.mosip.vivid.api.SessionState;
import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.device.DeviceHealth;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.FrameSink;
import io.mosip.vivid.device.PixelFormat;
import io.mosip.vivid.device.StreamOptions;
import io.mosip.vivid.device.mock.MockFaceFrameSource;
import io.mosip.vivid.device.mock.Pacing;
import io.mosip.vivid.device.mock.scenario.Scenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a mock-device {@link Scenario} through any {@link LivenessSession}: frames are fed on the mock's delivery
 * thread, health events become {@link LivenessSession#tick()} calls so timeouts still fire while the device is
 * silent, and every state change is recorded with the scenario time at which it happened. The run ends when the
 * session reaches a terminal state (or FAILED without retry), when the scenario ends, or at the configured
 * limits. Nothing here depends on liveness-core: only the liveness-api interfaces are used.
 */
public final class ScenarioRunner {
    private static final Logger LOG = LoggerFactory.getLogger(ScenarioRunner.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Per-run options. */
    public static final class Options {
        private Pacing pacing = Pacing.unthrottled();
        private PixelFormat format = PixelFormat.RGB888;
        private Workflow workflow = Workflow.RESIDENT;
        private Locale locale = Locale.ENGLISH;
        private boolean assistedCapture;
        private boolean retryOnFailure;
        private long maxScenarioMillis = -1;
        private long maxWallMillis = 120_000;
        private StreamOptions streamOptions = StreamOptions.defaults();

        public static Options defaults() { return new Options(); }
        /** Replay speed; unthrottled by default so suites run fast. Use real time when the session uses wall-clock timeouts. */
        public Options pacing(Pacing v) { pacing = Objects.requireNonNull(v); return this; }
        public Options format(PixelFormat v) { format = v; return this; }
        public Options workflow(Workflow v) { workflow = v; return this; }
        public Options locale(Locale v) { locale = v; return this; }
        public Options assistedCapture(boolean v) { assistedCapture = v; return this; }
        /** Call {@link LivenessSession#retry()} on FAILED while the policy allows it. */
        public Options retryOnFailure(boolean v) { retryOnFailure = v; return this; }
        /** Stop after this much scenario time (-1 = until the scenario ends; looping scenarios need a limit). */
        public Options maxScenarioMillis(long v) { maxScenarioMillis = v; return this; }
        public Options maxWallMillis(long v) { maxWallMillis = v; return this; }
        public Options streamOptions(StreamOptions v) { streamOptions = v; return this; }
        public Pacing pacing() { return pacing; }
        public long maxScenarioMillis() { return maxScenarioMillis; }
    }

    private final LivenessSessionFactory factory;

    public ScenarioRunner(LivenessSessionFactory factory) { this.factory = Objects.requireNonNull(factory); }

    /** Run with default options and a fresh random nonce. */
    public ScenarioRun run(Scenario scenario) { return run(scenario, Options.defaults()); }

    public ScenarioRun run(Scenario scenario, Options opts) {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        SessionContext ctx = new SessionContext(opts.workflow, scenario.name() + "-" + Long.toHexString(System.nanoTime()), nonce, opts.locale, false, opts.assistedCapture);
        return run(scenario, ctx, opts);
    }

    public ScenarioRun run(Scenario scenario, SessionContext ctx, Options opts) {
        long startWall = System.currentTimeMillis();
        List<StateTransition> trace = new ArrayList<>();
        List<DeviceHealth> health = new ArrayList<>();
        AtomicLong lastScenarioMs = new AtomicLong(-1);
        AtomicLong firstTs = new AtomicLong(Long.MIN_VALUE);
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        int[] frames = {0};
        boolean[] timedOut = {false};
        long limit = opts.maxScenarioMillis >= 0 ? opts.maxScenarioMillis : (scenario.loop() ? Math.max(60_000, scenario.totalDurationMs() * 4) : Long.MAX_VALUE);

        LivenessSession session = factory.open(ctx);
        try (session) {
            session.addListener((prev, cur) -> {
                synchronized (trace) { trace.add(new StateTransition(lastScenarioMs.get(), System.currentTimeMillis() - startWall, prev, cur)); }
            });
            MockFaceFrameSource source = MockFaceFrameSource.builder(scenario).pacing(opts.pacing).format(opts.format).build();
            FrameSink sink = new FrameSink() {
                @Override public void onFrame(FaceFrame frame) {
                    if (done.getCount() == 0) return;
                    firstTs.compareAndSet(Long.MIN_VALUE, frame.timestampNanos());
                    long scenarioMs = (frame.timestampNanos() - firstTs.get()) / 1_000_000L;
                    lastScenarioMs.set(scenarioMs);
                    if (scenarioMs > limit) { timedOut[0] = true; done.countDown(); return; }
                    try {
                        frames[0]++;
                        SessionState s = session.feed(frame);
                        if (shouldStop(s, session, opts)) done.countDown();
                    } catch (RuntimeException e) { error.set(e); done.countDown(); }
                }
                @Override public void onHealth(DeviceHealth h) {
                    synchronized (health) { health.add(h); }
                    if (done.getCount() == 0) return;
                    try {
                        SessionState s = session.tick();
                        if (shouldStop(s, session, opts)) done.countDown();
                    } catch (RuntimeException e) { error.set(e); done.countDown(); }
                }
                @Override public void onEnd() {
                    // device gone: give the session one more tick to react, then stop the run
                    if (done.getCount() > 0) {
                        try { SessionState s = session.tick(); if (!shouldStop(s, session, opts)) timedOut[0] = !s.isTerminal() && s.phase() != SessionState.Phase.FAILED; }
                        catch (RuntimeException e) { error.set(e); }
                    }
                    done.countDown();
                }
            };
            source.start(sink, opts.streamOptions);
            try {
                if (!done.await(opts.maxWallMillis, TimeUnit.MILLISECONDS)) { timedOut[0] = true; LOG.warn("scenario '{}' hit wall-clock limit {} ms", scenario.name(), opts.maxWallMillis); }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { done.countDown(); source.stop(); }

            SessionState finalState = session.state();
            LivenessResult result = null;
            try { result = session.result(); } catch (RuntimeException e) { LOG.debug("no result available for '{}': {}", scenario.name(), e.toString()); }
            long scenarioMillis = Math.max(0, lastScenarioMs.get());
            List<StateTransition> traceCopy; synchronized (trace) { traceCopy = List.copyOf(trace); }
            List<DeviceHealth> healthCopy; synchronized (health) { healthCopy = List.copyOf(health); }
            return new ScenarioRun(scenario, result, finalState, traceCopy, healthCopy, frames[0], scenarioMillis,
                    System.currentTimeMillis() - startWall, timedOut[0], error.get());
        }
    }

    private static boolean shouldStop(SessionState s, LivenessSession session, Options opts) {
        if (s.isTerminal()) return true;
        if (s.phase() == SessionState.Phase.FAILED) {
            if (opts.retryOnFailure && s.canRetry()) {
                boolean ok = session.retry();
                return !ok;
            }
            return true;
        }
        return false;
    }
}
