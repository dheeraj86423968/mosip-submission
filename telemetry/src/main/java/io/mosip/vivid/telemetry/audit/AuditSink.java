package io.mosip.vivid.telemetry.audit;

/**
 * Destination for audit records. Implementations must be thread-safe and must not throw: audit failures are
 * logged by the implementation and never propagate into the liveness session.
 */
@FunctionalInterface
public interface AuditSink extends AutoCloseable {

    /** Records one event. */
    void accept(AuditRecord record);

    /** Releases resources. Default: nothing to release. */
    @Override
    default void close() {}

    /** A sink that discards everything; useful as a default. */
    static AuditSink discarding() {
        return record -> { };
    }
}
