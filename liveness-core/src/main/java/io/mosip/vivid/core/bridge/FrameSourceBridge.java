package io.mosip.vivid.core.bridge;

import io.mosip.vivid.api.FailureReason;
import io.mosip.vivid.api.SessionState;
import io.mosip.vivid.core.session.LivenessOrchestrator;
import io.mosip.vivid.device.DeviceException;
import io.mosip.vivid.device.DeviceHealth;
import io.mosip.vivid.device.FaceFrame;
import io.mosip.vivid.device.FaceFrameSource;
import io.mosip.vivid.device.FrameSink;
import io.mosip.vivid.device.StreamOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wires a {@link FaceFrameSource} into a {@link LivenessOrchestrator}: frames are fed to the session, health
 * changes become device hints or DEVICE_* failures, a periodic {@link LivenessOrchestrator#tick()} keeps timeouts
 * firing while frames are absent, and the source is stopped once the session reaches a terminal state.
 *
 * <p>Stopping on terminal state is scheduled on the executor rather than done inside the listener, because the
 * listener runs under the session lock on the delivery thread and a synchronous {@code source.stop()} could wait
 * for that very thread.</p>
 */
public final class FrameSourceBridge implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(FrameSourceBridge.class);

    /** Interval of the timeout tick while frames are absent. */
    public static final long TICK_MILLIS = 200;

    private final FaceFrameSource source;
    private final LivenessOrchestrator session;
    private volatile StreamOptions options;
    private final ScheduledExecutorService scheduler;
    private final boolean ownScheduler;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile ScheduledFuture<?> ticker;
    private volatile boolean requireSignedFrames;

    /** Bridge with default stream options and its own scheduler; pass options to {@link #start(StreamOptions)}. */
    public FrameSourceBridge(FaceFrameSource source, LivenessOrchestrator session) {
        this(source, session, null, null);
    }

    /** Bridge with its own single-thread scheduler. */
    public FrameSourceBridge(FaceFrameSource source, LivenessOrchestrator session, StreamOptions options) {
        this(source, session, options, null);
    }

    /** Bridge using the given scheduler for ticks and deferred stops; {@code null} creates a private one. */
    public FrameSourceBridge(FaceFrameSource source, LivenessOrchestrator session, StreamOptions options, ScheduledExecutorService scheduler) {
        this.source = Objects.requireNonNull(source, "source");
        this.session = Objects.requireNonNull(session, "session");
        this.options = Objects.requireNonNullElse(options, StreamOptions.defaults());
        this.ownScheduler = scheduler == null;
        this.scheduler = scheduler == null ? Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "liveness-bridge");
            t.setDaemon(true);
            return t;
        }) : scheduler;
    }

    /** Starts with the given stream options (overriding those given at construction). Idempotent. */
    public void start(StreamOptions streamOptions) {
        if (streamOptions != null) {
            this.options = streamOptions;
        }
        start();
    }

    /** Starts the session, the source and the tick. Idempotent. */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        session.addListener((previous, current) -> {
            if (current.isTerminal()) {
                scheduler.execute(this::stop);
            }
        });
        session.start();
        if (session.state().isTerminal()) {
            return;
        }
        try {
            // read before start so no frame slips through unchecked, and again after, once the device is known
            requireSignedFrames = signsFrames(source);
            source.start(new Sink(), options);
            requireSignedFrames |= signsFrames(source);
            if (source.health().isUsable()) {
                session.deviceReady();
            }
        } catch (DeviceException e) {
            session.deviceFault(map(e.kind()), e.getMessage());
            return;
        } catch (RuntimeException e) {
            session.deviceFault(FailureReason.DEVICE_UNAVAILABLE, e.toString());
            return;
        }
        ticker = scheduler.scheduleAtFixedRate(this::safeTick, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** Stops the tick and the source; the session is left as it is (the caller reads its result). Idempotent. */
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> t = ticker;
        if (t != null) {
            t.cancel(false);
        }
        try {
            source.stop();
        } catch (RuntimeException e) {
            LOG.warn("frame source stop failed: {}", e.toString());
        }
    }

    /** Stops and releases a privately owned scheduler. */
    @Override
    public void close() {
        stop();
        if (ownScheduler) {
            scheduler.shutdownNow();
        }
    }

    /** The session driven by this bridge. */
    public LivenessOrchestrator session() {
        return session;
    }

    private void safeTick() {
        try {
            if (!stopped.get()) {
                session.tick();
            }
        } catch (RuntimeException e) {
            LOG.error("tick failed: {}", e.toString());
        }
    }

    /** Maps a device exception kind onto the liveness failure taxonomy. */
    public static FailureReason map(DeviceException.Kind kind) {
        if (kind == null) {
            return FailureReason.DEVICE_UNAVAILABLE;
        }
        switch (kind) {
            case CONNECTION_FAILED:
            case PROTOCOL_ERROR:
                return FailureReason.DEVICE_CONNECTION_FAILURE;
            case CAMERA_UNAVAILABLE:
                return FailureReason.CAMERA_UNAVAILABLE;
            case INVALID_FRAME:
                return FailureReason.INVALID_FRAME_DATA;
            case UNAVAILABLE:
            default:
                return FailureReason.DEVICE_UNAVAILABLE;
        }
    }

    private static boolean signsFrames(FaceFrameSource source) {
        try {
            return source.capabilities().signsFrames();
        } catch (RuntimeException e) {
            LOG.debug("capabilities unavailable: {}", e.toString());
            return false;
        }
    }

    private final class Sink implements FrameSink {
        @Override
        public void onFrame(FaceFrame frame) {
            if (stopped.get()) {
                return;
            }
            if (requireSignedFrames && !frame.deviceSignature().isPresent()) {
                // the device promised signed frames; an unsigned one was injected or forged on the preview path
                session.frameSignatureInvalid("frame " + frame.sequence() + " has no valid device signature");
                return;
            }
            try {
                session.feed(frame);
            } catch (RuntimeException e) {
                LOG.error("session rejected frame {}: {}", frame.sequence(), e.toString());
            }
        }

        @Override
        public void onHealth(DeviceHealth health) {
            if (stopped.get() || health == null) {
                return;
            }
            switch (health.state()) {
                case CONNECTED:
                    session.deviceReady();
                    break;
                case DEGRADED:
                case RECONNECTING:
                    session.deviceReconnecting(health.reason());
                    break;
                case DISCONNECTED:
                    session.deviceFault(FailureReason.DEVICE_CONNECTION_FAILURE, health.reason());
                    break;
                case FAILED:
                    session.deviceFault(FailureReason.DEVICE_UNAVAILABLE, health.reason());
                    break;
                case CONNECTING:
                default:
                    break;
            }
        }

        @Override
        public void onEnd() {
            if (stopped.get()) {
                return;
            }
            SessionState s = session.state();
            if (!s.isTerminal() && s.phase() != SessionState.Phase.FAILED) {
                session.deviceFault(FailureReason.DEVICE_UNAVAILABLE, "frame stream ended");
            }
        }
    }
}
