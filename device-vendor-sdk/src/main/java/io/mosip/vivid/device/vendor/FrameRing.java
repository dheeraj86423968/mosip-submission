package io.mosip.vivid.device.vendor;

import io.mosip.vivid.device.DeviceHealth;
import io.mosip.vivid.device.FaceFrame;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Hand-off between the thread that produces frames (a vendor SDK callback, a socket reader) and the thread that
 * calls the {@link io.mosip.vivid.device.FrameSink}. Frames live in a bounded ring that drops the <b>oldest</b> frame
 * when full, so a slow engine never blocks the producer and the sink always gets the freshest frames. Health
 * events are queued without bound and delivered before pending frames, so none is lost and their order is kept.
 *
 * <p>Same semantics as {@code device-sbi-stream}'s package-private {@code DeliveryQueue}; it is public here so any
 * adapter can use it (see README, "Reuse").
 */
public final class FrameRing {
    /** Either a frame or a health event. */
    public static final class Item {
        private final FaceFrame frame;
        private final DeviceHealth health;
        Item(FaceFrame frame, DeviceHealth health) { this.frame = frame; this.health = health; }
        /** The frame, or null if this item is a health event. */
        public FaceFrame frame() { return frame; }
        /** The health event, or null if this item is a frame. */
        public DeviceHealth health() { return health; }
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final ArrayDeque<FaceFrame> frames = new ArrayDeque<>();
    private final ArrayDeque<DeviceHealth> health = new ArrayDeque<>();
    private final int capacity;
    private long dropped;
    private boolean closed;

    /** @param capacity frames held before the oldest is evicted (at least 1) */
    public FrameRing(int capacity) { this.capacity = Math.max(1, capacity); }

    /** Enqueues a frame, evicting the oldest when full. Ignored after {@link #close()}. Returns true if a frame was dropped. */
    public boolean offer(FaceFrame f) {
        lock.lock();
        try {
            if (closed) return false;
            boolean drop = false;
            if (frames.size() >= capacity) { frames.pollFirst(); dropped++; drop = true; }
            frames.addLast(f);
            notEmpty.signal();
            return drop;
        } finally { lock.unlock(); }
    }

    /** Enqueues a health event. Ignored after {@link #close()}. */
    public void offerHealth(DeviceHealth h) {
        lock.lock();
        try { if (closed) return; health.addLast(h); notEmpty.signal(); } finally { lock.unlock(); }
    }

    /** Next item, health first; null on timeout, or when closed and drained. */
    public Item poll(long timeoutMs) throws InterruptedException {
        lock.lock();
        try {
            long nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (frames.isEmpty() && health.isEmpty()) {
                if (closed || nanos <= 0) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            if (!health.isEmpty()) return new Item(null, health.pollFirst());
            return new Item(frames.pollFirst(), null);
        } finally { lock.unlock(); }
    }

    /** Stops accepting items; the consumer drains what is queued and then gets null. */
    public void close() { lock.lock(); try { closed = true; notEmpty.signalAll(); } finally { lock.unlock(); } }
    public boolean isClosed() { lock.lock(); try { return closed; } finally { lock.unlock(); } }
    public boolean isDrained() { lock.lock(); try { return closed && frames.isEmpty() && health.isEmpty(); } finally { lock.unlock(); } }
    /** Frames evicted so far. */
    public long dropped() { lock.lock(); try { return dropped; } finally { lock.unlock(); } }
    public int queuedFrames() { lock.lock(); try { return frames.size(); } finally { lock.unlock(); } }
}
