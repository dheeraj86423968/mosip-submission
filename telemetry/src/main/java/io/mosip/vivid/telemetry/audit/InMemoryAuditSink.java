package io.mosip.vivid.telemetry.audit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Bounded in-memory sink for tests and the diagnostic screen. Keeps the most recent {@code capacity} records,
 * dropping the oldest when full. Thread-safe.
 */
public final class InMemoryAuditSink implements AuditSink {

    /** Default capacity. */
    public static final int DEFAULT_CAPACITY = 1000;

    private final int capacity;
    private final Deque<AuditRecord> records;
    private long dropped;

    /** Sink keeping the last {@value #DEFAULT_CAPACITY} records. */
    public InMemoryAuditSink() {
        this(DEFAULT_CAPACITY);
    }

    /** Sink keeping the last {@code capacity} records. */
    public InMemoryAuditSink(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.records = new ArrayDeque<>(Math.min(capacity, 64));
    }

    @Override
    public synchronized void accept(AuditRecord record) {
        if (record == null) {
            return;
        }
        if (records.size() == capacity) {
            records.pollFirst();
            dropped++;
        }
        records.addLast(record);
    }

    /** Snapshot of retained records, oldest first. */
    public synchronized List<AuditRecord> records() {
        return new ArrayList<>(records);
    }

    /** Records of one event type, oldest first. */
    public synchronized List<AuditRecord> find(LivenessAuditEvent event) {
        return records.stream().filter(r -> r.event() == event).collect(Collectors.toList());
    }

    /** The most recent record of one event type. */
    public synchronized Optional<AuditRecord> last(LivenessAuditEvent event) {
        for (var it = records.descendingIterator(); it.hasNext();) {
            AuditRecord r = it.next();
            if (r.event() == event) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    /** Event sequence, oldest first, for compact assertions. */
    public synchronized List<LivenessAuditEvent> events() {
        return records.stream().map(AuditRecord::event).collect(Collectors.toList());
    }

    /** Number of records dropped because the sink was full. */
    public synchronized long dropped() {
        return dropped;
    }

    public synchronized int size() {
        return records.size();
    }

    public synchronized void clear() {
        records.clear();
    }
}
