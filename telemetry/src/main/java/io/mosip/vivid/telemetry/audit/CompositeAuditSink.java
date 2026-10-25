package io.mosip.vivid.telemetry.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Fans records out to several sinks. A failing sink is logged and skipped so that one broken destination
 * (for example a full disk behind an {@link OfflineAuditQueue}) cannot block the others or the session.
 */
public final class CompositeAuditSink implements AuditSink {

    private static final Logger LOG = LoggerFactory.getLogger(CompositeAuditSink.class);

    private final List<AuditSink> sinks;

    /** Composite over the given sinks, invoked in order. */
    public CompositeAuditSink(List<AuditSink> sinks) {
        this.sinks = List.copyOf(Objects.requireNonNull(sinks, "sinks"));
    }

    /** Convenience varargs constructor. */
    public static CompositeAuditSink of(AuditSink... sinks) {
        return new CompositeAuditSink(List.of(sinks));
    }

    @Override
    public void accept(AuditRecord record) {
        for (AuditSink s : sinks) {
            try {
                s.accept(record);
            } catch (RuntimeException e) {
                LOG.error("audit sink {} failed for {}: {}", s.getClass().getSimpleName(), record.event(), e.toString());
            }
        }
    }

    @Override
    public void close() {
        for (AuditSink s : sinks) {
            try {
                s.close();
            } catch (Exception e) {
                LOG.warn("audit sink {} failed to close: {}", s.getClass().getSimpleName(), e.toString());
            }
        }
    }

    /** The wrapped sinks. */
    public List<AuditSink> sinks() {
        return sinks;
    }
}
