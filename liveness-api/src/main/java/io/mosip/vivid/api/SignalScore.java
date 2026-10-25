package io.mosip.vivid.api;

import java.util.Objects;

/**
 * Output of one anti-spoofing signal for one frame or window.
 * attackProbability is calibrated in [0,1]; confidence in [0,1] says how much to trust it this frame
 * (e.g. rPPG has low confidence until its window fills). hardVeto means the signal is certain enough to fail the session alone.
 */
public final class SignalScore {
    private final String signalId;
    private final double attackProbability;
    private final double confidence;
    private final boolean hardVeto;
    private final String attackClass; // nullable: PRINT, SCREEN_REPLAY, VIDEO_REPLAY, MASK, DEEPFAKE, INJECTION, UNKNOWN

    public SignalScore(String signalId, double attackProbability, double confidence, boolean hardVeto, String attackClass) {
        this.signalId = Objects.requireNonNull(signalId);
        this.attackProbability = clamp(attackProbability);
        this.confidence = clamp(confidence);
        this.hardVeto = hardVeto;
        this.attackClass = attackClass;
    }
    public static SignalScore of(String id, double p, double confidence) { return new SignalScore(id, p, confidence, false, null); }
    public static SignalScore veto(String id, String attackClass) { return new SignalScore(id, 1.0, 1.0, true, attackClass); }
    private static double clamp(double v) { return Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v)); }

    public String signalId() { return signalId; }
    public double attackProbability() { return attackProbability; }
    public double confidence() { return confidence; }
    public boolean hardVeto() { return hardVeto; }
    public String attackClass() { return attackClass; }
    @Override public String toString() { return signalId + "=" + String.format("%.3f", attackProbability) + "@" + String.format("%.2f", confidence) + (hardVeto ? "!" : ""); }
}
