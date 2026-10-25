package io.mosip.vivid.calibration;

import io.mosip.vivid.api.Workflow;
import io.mosip.vivid.telemetry.audit.AuditRecord;
import io.mosip.vivid.telemetry.audit.LivenessAuditEvent;
import io.mosip.vivid.telemetry.calibration.CalibrationReleaser;
import io.mosip.vivid.telemetry.calibration.DpMechanism;
import io.mosip.vivid.telemetry.calibration.ScoreBinning;

import java.util.Map;

/**
 * One client's noisy histograms for one workflow and release, as read back from a
 * {@link LivenessAuditEvent#CALIBRATION_RELEASED} audit record.
 */
public record ClientRelease(String client, String centre, Workflow workflow, String releaseId, ScoreBinning binning,
                            String mechanism, double epsilon, double rho, int sensitivity, long[] bonaFide, long[] rejected,
                            Double threshold, String policyVersion) {

    /** Parses a release record; throws {@link IllegalArgumentException} on anything malformed. */
    public static ClientRelease fromAudit(AuditRecord r) {
        if (r.event() != LivenessAuditEvent.CALIBRATION_RELEASED) {
            throw new IllegalArgumentException("not a calibration release: " + r.event());
        }
        Map<String, String> a = r.attributes();
        ScoreBinning binning = ScoreBinning.parse(require(a, "binning"));
        String threshold = a.getOrDefault("threshold", "");
        return new ClientRelease(require(a, "client"), require(a, "centre"),
                r.workflow().orElseThrow(() -> new IllegalArgumentException("release without workflow")),
                require(a, "releaseId"), binning, require(a, "mechanism"), Double.parseDouble(require(a, "epsilon")),
                Double.parseDouble(require(a, "rho")), Integer.parseInt(require(a, "sensitivity")),
                CalibrationReleaser.readCounts(a, "bf", binning.bins()), CalibrationReleaser.readCounts(a, "rej", binning.bins()),
                threshold.isEmpty() ? null : Double.parseDouble(threshold), a.getOrDefault("policyVersion", ""));
    }

    /** Variance of the noise on each count of this release. */
    public double noiseVariance() {
        if (DpMechanism.LAPLACE.equals(mechanism)) {
            double q = Math.exp(-epsilon / sensitivity);
            return 2 * q / ((1 - q) * (1 - q));
        }
        if (DpMechanism.GAUSSIAN.equals(mechanism)) {
            return (double) sensitivity * sensitivity / (2 * rho);
        }
        throw new IllegalArgumentException("unknown mechanism " + mechanism);
    }

    /** Deduplication key: the audit sync is at-least-once, so the same release can arrive twice. */
    public String key() {
        return client + "|" + releaseId + "|" + workflow;
    }

    private static String require(Map<String, String> a, String k) {
        String v = a.get(k);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("missing attribute " + k);
        }
        return v;
    }
}
