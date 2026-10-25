package io.mosip.vivid.api;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Final outcome of a session. Contains everything audit and diagnostics need and nothing biometric:
 * scores, decisions, timings, hashes. Never frames.
 */
public final class LivenessResult {
    public enum Decision { LIVE, NOT_LIVE, ATTACK, ERROR, CANCELLED }

    private final String sessionId;
    private final Workflow workflow;
    private final Decision decision;
    private final FailureReason failure;             // nullable
    private final double fusedLivenessScore;         // [0,1], 1 = live
    private final Map<String, Double> signalScores;  // last/aggregated attack probability per signal
    private final String vetoSignal;                 // nullable
    private final String attackClass;                // nullable
    private final List<ChallengeOutcome> challenges;
    private final int attemptsUsed;
    private final long passiveMillis;
    private final long totalMillis;
    private final int framesAnalyzed;
    private final String engineId;
    private final String modelBundleVersion;
    private final String policyVersion;
    private final String nonceHex;
    private final float[] anchorEmbedding;          // nullable; for identity-continuity against the signed capture image
    private final String evidenceHash;               // SHA-256 over the decision record for tamper-evident audit

    public LivenessResult(String sessionId, Workflow workflow, Decision decision, FailureReason failure, double fusedLivenessScore,
                          Map<String, Double> signalScores, String vetoSignal, String attackClass, List<ChallengeOutcome> challenges,
                          int attemptsUsed, long passiveMillis, long totalMillis, int framesAnalyzed, String engineId,
                          String modelBundleVersion, String policyVersion, String nonceHex, float[] anchorEmbedding, String evidenceHash) {
        this.sessionId = Objects.requireNonNull(sessionId); this.workflow = Objects.requireNonNull(workflow);
        this.decision = Objects.requireNonNull(decision); this.failure = failure; this.fusedLivenessScore = fusedLivenessScore;
        this.signalScores = signalScores == null ? Map.of() : Collections.unmodifiableMap(signalScores);
        this.vetoSignal = vetoSignal; this.attackClass = attackClass;
        this.challenges = challenges == null ? List.of() : Collections.unmodifiableList(challenges);
        this.attemptsUsed = attemptsUsed; this.passiveMillis = passiveMillis; this.totalMillis = totalMillis; this.framesAnalyzed = framesAnalyzed;
        this.engineId = engineId; this.modelBundleVersion = modelBundleVersion; this.policyVersion = policyVersion; this.nonceHex = nonceHex;
        this.anchorEmbedding = anchorEmbedding == null ? null : anchorEmbedding.clone(); this.evidenceHash = evidenceHash;
    }
    public String sessionId() { return sessionId; }
    public Workflow workflow() { return workflow; }
    public Decision decision() { return decision; }
    public boolean accepted() { return decision == Decision.LIVE; }
    public Optional<FailureReason> failure() { return Optional.ofNullable(failure); }
    public double fusedLivenessScore() { return fusedLivenessScore; }
    public Map<String, Double> signalScores() { return signalScores; }
    public Optional<String> vetoSignal() { return Optional.ofNullable(vetoSignal); }
    public Optional<String> attackClass() { return Optional.ofNullable(attackClass); }
    public List<ChallengeOutcome> challenges() { return challenges; }
    public int attemptsUsed() { return attemptsUsed; }
    public long passiveMillis() { return passiveMillis; }
    public long totalMillis() { return totalMillis; }
    public int framesAnalyzed() { return framesAnalyzed; }
    public String engineId() { return engineId; }
    public String modelBundleVersion() { return modelBundleVersion; }
    public String policyVersion() { return policyVersion; }
    public String nonceHex() { return nonceHex; }
    public Optional<float[]> anchorEmbedding() { return anchorEmbedding == null ? Optional.empty() : Optional.of(anchorEmbedding.clone()); }
    public String evidenceHash() { return evidenceHash; }
}
