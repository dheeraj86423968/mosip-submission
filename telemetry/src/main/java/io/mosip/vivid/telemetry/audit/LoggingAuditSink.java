package io.mosip.vivid.telemetry.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Writes audit records to SLF4J under the logger {@code io.mosip.vivid.audit.LIVENESS}: INFO events at info level,
 * WARN at warn, ERROR and SECURITY at error. Suitable as the default sink when the Registration Client's
 * own audit DAO is not wired in yet.
 */
public final class LoggingAuditSink implements AuditSink {

    /** Logger name, matching the audit component name. */
    public static final String LOGGER_NAME = "io.mosip.vivid.audit.LIVENESS";

    private final Logger log;

    /** Sink logging under {@value #LOGGER_NAME}. */
    public LoggingAuditSink() {
        this(LoggerFactory.getLogger(LOGGER_NAME));
    }

    /** Sink logging to a specific logger (for tests). */
    public LoggingAuditSink(Logger log) {
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override
    public void accept(AuditRecord record) {
        if (record == null) {
            return;
        }
        String line = record.toJson();
        switch (record.event().severity()) {
            case INFO:
                log.info("{}", line);
                break;
            case WARN:
                log.warn("{}", line);
                break;
            default:
                log.error("{}", line);
        }
    }
}
