package io.mosip.vivid.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Plug-in point into MOSIP's {@code io.mosip.registration.audit.AuditManagerService}. The desktop integration never
 * references the MOSIP class directly; the patch module provides {@code MosipLivenessAuditAdapter}, which maps
 * {@link DesktopAuditEvent} to the {@code AuditEvent} constants added by the patch and calls
 * {@code auditFactory.audit(event, Components.LIVENESS, refId, refIdType)}.
 * <p>
 * {@code details} never contains biometric data: scores, decision, nonce hex, policy and model versions only.
 */
@FunctionalInterface
public interface LivenessAuditAdapter {

    /**
     * @param event   what happened
     * @param refId   the session id (or user id / registration id if the caller prefers MOSIP's convention)
     * @param details small string map; implementations may serialise it into the audit description or a log sink
     */
    void audit(DesktopAuditEvent event, String refId, Map<String, String> details);

    /** Drops everything. */
    LivenessAuditAdapter NOOP = (event, refId, details) -> {};

    /** Writes to SLF4J at INFO; useful until the MOSIP adapter is wired. */
    static LivenessAuditAdapter logging() {
        Logger log = LoggerFactory.getLogger("io.mosip.vivid.desktop.audit");
        return (event, refId, details) -> log.info("liveness-audit {} {} ref={} {}", event.id(), event, refId, details);
    }

    /** Fan-out to several adapters (e.g. MOSIP audit table + telemetry sink). */
    static LivenessAuditAdapter composite(LivenessAuditAdapter... adapters) {
        return (event, refId, details) -> { for (LivenessAuditAdapter a : adapters) a.audit(event, refId, details); };
    }
}
