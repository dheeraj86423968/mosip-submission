package io.mosip.vivid.desktop.mosip;

import io.mosip.registration.audit.AuditManagerService;
import io.mosip.registration.constants.AuditEvent;
import io.mosip.registration.constants.AuditReferenceIdTypes;
import io.mosip.registration.constants.Components;
import io.mosip.vivid.desktop.DesktopAuditEvent;
import io.mosip.vivid.desktop.LivenessAuditAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Writes liveness events into MOSIP's audit table through {@code AuditManagerService.audit(...)}.
 * The {@code AuditEvent.LIVENESS_*} constants and {@code Components.LIVENESS} are added by patch 0005.
 * MOSIP's audit row has no free-text payload column exposed through this API, so {@code details} are logged
 * (never biometric data) and the row carries event id + component + refId only.
 */
public final class MosipLivenessAuditAdapter implements LivenessAuditAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(MosipLivenessAuditAdapter.class);
    private final AuditManagerService auditManagerService;

    public MosipLivenessAuditAdapter(AuditManagerService auditManagerService) { this.auditManagerService = auditManagerService; }

    @Override public void audit(DesktopAuditEvent event, String refId, Map<String, String> details) {
        AuditEvent mosipEvent;
        try { mosipEvent = AuditEvent.valueOf("LIVENESS_" + event.name()); }
        catch (IllegalArgumentException missing) { LOG.warn("AuditEvent.LIVENESS_{} not present (patch 0005 not applied); event {} dropped", event.name(), event); return; }
        try {
            auditManagerService.audit(mosipEvent, Components.LIVENESS, refId, AuditReferenceIdTypes.USER_ID.getReferenceTypeId());
        } catch (RuntimeException e) {
            LOG.error("audit write failed for {}", event, e);
        }
        LOG.info("liveness {} ref={} {}", new Object[] {event.id(), refId, details}); // Object[]: the client resolves slf4j-api 1.6.6 (no varargs)
    }
}
