package io.mosip.vivid.bundle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Audit hook for bundle lifecycle events. The plan requires every rejected load attempt to be audited, so the
 * store and importer report through this interface; the default implementation logs via SLF4J and the
 * telemetry module can supply one that emits audit events.
 */
public interface BundleEventListener {
    void onRejected(Path source, BundleRejectedException.Reason reason, String detail);

    void onInstalled(InstalledBundle bundle);

    void onActivated(InstalledBundle bundle, InstalledBundle previous);

    void onRolledBack(InstalledBundle restored, InstalledBundle from);

    void onRemoved(String bundleId, String version);

    /** Logging-only listener. */
    static BundleEventListener logging() {
        return new BundleEventListener() {
            private final Logger log = LoggerFactory.getLogger("io.mosip.vivid.bundle.audit");
            @Override public void onRejected(Path source, BundleRejectedException.Reason reason, String detail) {
                log.warn("bundle rejected source={} reason={} detail={}", source, reason, detail);
            }
            @Override public void onInstalled(InstalledBundle bundle) { log.info("bundle installed {}", bundle); }
            @Override public void onActivated(InstalledBundle bundle, InstalledBundle previous) {
                log.info("bundle activated {} (previous {})", bundle, previous);
            }
            @Override public void onRolledBack(InstalledBundle restored, InstalledBundle from) {
                log.warn("bundle rolled back to {} from {}", restored, from);
            }
            @Override public void onRemoved(String bundleId, String version) { log.info("bundle removed {}@{}", bundleId, version); }
        };
    }
}
