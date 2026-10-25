package io.mosip.vivid.av;

import io.mosip.vivid.api.LivenessResult;

/**
 * Client-side assertion for deployments that require the spoken code (for example assisted capture for residents
 * who cannot perform facial gestures): a LIVE result counts only if the {@link AvSignals#AV_SYNC} signal is present
 * and reported a pass. Guards against a policy or pool change that silently stops issuing the carrier.
 */
public final class SpokenCodeRequirement {
    private SpokenCodeRequirement() {}

    public static boolean satisfied(LivenessResult result) {
        if (result == null || !result.accepted()) return false;
        Double p = result.signalScores().get(AvSignals.AV_SYNC);
        return p != null && p < 0.5;
    }
}
