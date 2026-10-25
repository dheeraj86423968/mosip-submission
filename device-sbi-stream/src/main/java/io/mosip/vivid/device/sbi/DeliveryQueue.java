package io.mosip.vivid.device.sbi;

import io.mosip.vivid.device.DeviceHealth;
import io.mosip.vivid.device.FaceFrame;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Hand-off between the socket reader and the sink delivery thread. Frames live in a bounded ring that drops
 * the oldest frame when full (the reader never blocks on a slow sink); health transitions are queued
 * unbounded and delivered before any pending frame, so the sink sees them in order and never loses one.
 */
final class DeliveryQueue {
    /** Either a frame or a health event. */
    static final class Item {
        private final FaceFrame frame;
        private final DeviceHealth health;

        Item(FaceFrame frame, DeviceHealth health) {
            this.frame = frame;
            this.health = health;
        }

        public FaceFrame frame() { return frame; }
        public DeviceHealth health() { return health; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Item)) return false;
            Item other = (Item) o;
            return java.util.Objects.equals(frame, other.frame)
                    && java.util.Objects.equals(health, other.health);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(frame);
            h = 31 * h + java.util.Objects.hashCode(health);
            return h;
        }

        @Override
        public String toString() {
            return "Item[frame=" + frame + ", health=" + health + "]";
        }
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final ArrayDeque<FaceFrame> frames = new ArrayDeque<>();
    private final ArrayDeque<DeviceHealth> health = new ArrayDeque<>();
    private final int capacity;
    private long dropped;
    private boolean closed;

    DeliveryQueue(int capacity) { this.capacity = Math.max(1, capacity); }

    /** Enqueue a frame, evicting the oldest if full. Returns true if a frame was dropped. */
    boolean offer(FaceFrame f) {
        lock.lock();
        try {
            boolean drop = false;
            if (frames.size() >= capacity) { frames.pollFirst(); dropped++; drop = true; }
            frames.addLast(f);
            notEmpty.signal();
            return drop;
        } finally { lock.unlock(); }
    }

    void offerHealth(DeviceHealth h) {
        lock.lock();
        try { health.addLast(h); notEmpty.signal(); } finally { lock.unlock(); }
    }

    /** Next item, health first; null on timeout or when closed and drained. */
    Item poll(long timeoutMs) throws InterruptedException {
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

    /** Stop accepting; pollers drain what is left and then get null. */
    void close() { lock.lock(); try { closed = true; notEmpty.signalAll(); } finally { lock.unlock(); } }
    boolean isClosed() { lock.lock(); try { return closed; } finally { lock.unlock(); } }
    long dropped() { lock.lock(); try { return dropped; } finally { lock.unlock(); } }
    int queuedFrames() { lock.lock(); try { return frames.size(); } finally { lock.unlock(); } }
}
