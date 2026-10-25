package io.mosip.vivid.telemetry.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Append-only JSON-lines audit queue for offline operation. Each {@link #accept} appends one line and forces it
 * to disk, so a power loss cannot lose an acknowledged record. {@link #drain} replays every record to a consumer
 * (typically the Registration Client's audit sync on the next connection) and truncates the file once all
 * records were consumed. Lines that fail to parse (a torn write from a crash mid-append) are skipped and counted.
 *
 * <p>Thread-safe. Records never contain biometric data (see {@link AuditRedactor}).</p>
 */
public final class OfflineAuditQueue implements AuditSink {

    private static final Logger LOG = LoggerFactory.getLogger(OfflineAuditQueue.class);

    private final Path file;
    private final Object lock = new Object();
    private long corruptLinesSkipped;

    /** Queue backed by the given file; parent directories are created on first append. */
    public OfflineAuditQueue(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** Appends and fsyncs one record. I/O failures are logged, not thrown. */
    @Override
    public void accept(AuditRecord record) {
        if (record == null) {
            return;
        }
        byte[] line = (record.toJson() + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (lock) {
            try {
                Path parent = file.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                    ByteBuffer buf = ByteBuffer.wrap(line);
                    while (buf.hasRemaining()) {
                        ch.write(buf);
                    }
                    ch.force(true);
                }
            } catch (IOException e) {
                LOG.error("cannot append audit record {} to {}: {}", record.event(), file.getFileName(), e.toString());
            }
        }
    }

    /**
     * Replays every queued record to {@code consumer} in order and truncates the queue once all were delivered.
     * If the consumer throws, delivery stops, nothing is truncated and the exception propagates; records already
     * consumed will be delivered again on the next drain (at-least-once).
     *
     * @return number of records delivered
     */
    public int drain(Consumer<AuditRecord> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        synchronized (lock) {
            if (!Files.exists(file)) {
                return 0;
            }
            int delivered = 0;
            try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (isBlank(line)) {
                        continue;
                    }
                    AuditRecord rec;
                    try {
                        rec = AuditRecord.fromJson(line);
                    } catch (RuntimeException e) {
                        corruptLinesSkipped++;
                        LOG.warn("skipping corrupt audit line in {}: {}", file.getFileName(), e.getMessage());
                        continue;
                    }
                    consumer.accept(rec);
                    delivered++;
                }
                Files.write(file, new byte[0], StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot drain audit queue " + file.getFileName(), e);
            }
            return delivered;
        }
    }

    /** Number of parseable records currently queued (reads the file; O(n)). */
    public int pending() {
        synchronized (lock) {
            if (!Files.exists(file)) {
                return 0;
            }
            int n = 0;
            try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (isBlank(line)) {
                        continue;
                    }
                    try {
                        AuditRecord.fromJson(line);
                        n++;
                    } catch (RuntimeException ignored) {
                        // counted during drain
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read audit queue " + file.getFileName(), e);
            }
            return n;
        }
    }

    /** Corrupt lines skipped by drains so far. */
    public long corruptLinesSkipped() {
        synchronized (lock) {
            return corruptLinesSkipped;
        }
    }

    /** The backing file. */
    public Path file() {
        return file;
    }

    /** {@code String.isBlank()} without the Java 11 API, which Android API 28 lacks. */
    private static boolean isBlank(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (!Character.isWhitespace(cp)) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }
}
