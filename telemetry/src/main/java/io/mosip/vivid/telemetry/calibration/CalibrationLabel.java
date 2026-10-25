package io.mosip.vivid.telemetry.calibration;

/** Outcome label of a session contributed to calibration histograms. */
public enum CalibrationLabel {
    /**
     * The session was later confirmed live: it passed (possibly on a retry), the capture was accepted and the
     * decision was not overridden or flagged afterwards. The recorded score is the first attempt's fused score.
     */
    BONA_FIDE,
    /** Every other completed session: rejected, locked, overridden or flagged. A mixture of attacks and bona fide. */
    REJECTED
}
