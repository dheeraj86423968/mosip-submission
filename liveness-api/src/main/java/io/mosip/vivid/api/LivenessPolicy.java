package io.mosip.vivid.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Effective policy for one workflow. Built by the policy module from JSON / properties; consumed by the orchestrator.
 * All timing values in milliseconds.
 */
public final class LivenessPolicy {
    public enum ActiveMode { NEVER, ON_LOW_CONFIDENCE, ALWAYS }
    public enum OnMaxRetries { RETRY_ONLY, LOCK_AND_ALERT, ASSISTED_CAPTURE_WITH_SUPERVISOR, FALLBACK_TO_EXCEPTION_FLOW }

    private final String policyVersion;
    private final Workflow workflow;
    private final boolean enabled;
    private final double passiveThreshold;              // fused liveness score needed to pass passively
    private final double padHardThreshold;              // any signal attackProbability above this (with confidence) vetoes
    private final long passiveWindowMillis;             // how long to observe before deciding passive
    private final int passiveMinFrames;
    private final ActiveMode activeMode;
    private final int minChallenges;
    private final int maxChallenges;
    private final Set<ChallengeType> challengePool;
    private final Set<ChallengeType> accessibilityPool;
    private final boolean requireDepthRevealingChallenge;
    private final long challengeTimeoutMillis;
    private final long minActionLatencyMillis;          // faster than this is suspicious (replay / injection)
    private final long maxActionLatencyMillis;
    private final int maxRetries;
    private final OnMaxRetries onMaxRetries;
    private final long sessionTimeoutMillis;
    private final double minFaceQuality;
    private final double identityDriftThreshold;        // cosine distance above which identity continuity fails
    private final Map<String, Double> signalWeights;    // signalId -> weight in fusion; absent = 1.0; 0 disables
    private final boolean requireIdentityContinuity;

    private LivenessPolicy(Builder b) {
        policyVersion = Objects.requireNonNullElse(b.policyVersion, "unversioned");
        workflow = Objects.requireNonNull(b.workflow, "workflow");
        enabled = b.enabled; passiveThreshold = b.passiveThreshold; padHardThreshold = b.padHardThreshold;
        passiveWindowMillis = b.passiveWindowMillis; passiveMinFrames = b.passiveMinFrames; activeMode = b.activeMode;
        minChallenges = b.minChallenges; maxChallenges = Math.max(b.maxChallenges, b.minChallenges);
        challengePool = Collections.unmodifiableSet(EnumSet.copyOf(b.challengePool));
        accessibilityPool = Collections.unmodifiableSet(b.accessibilityPool.isEmpty() ? EnumSet.noneOf(ChallengeType.class) : EnumSet.copyOf(b.accessibilityPool));
        requireDepthRevealingChallenge = b.requireDepthRevealingChallenge; challengeTimeoutMillis = b.challengeTimeoutMillis;
        minActionLatencyMillis = b.minActionLatencyMillis; maxActionLatencyMillis = b.maxActionLatencyMillis;
        maxRetries = b.maxRetries; onMaxRetries = b.onMaxRetries; sessionTimeoutMillis = b.sessionTimeoutMillis;
        minFaceQuality = b.minFaceQuality; identityDriftThreshold = b.identityDriftThreshold;
        signalWeights = Collections.unmodifiableMap(b.signalWeights); requireIdentityContinuity = b.requireIdentityContinuity;
        if (passiveThreshold < 0 || passiveThreshold > 1) throw new IllegalArgumentException("passiveThreshold must be in [0,1]");
        if (minChallenges < 0) throw new IllegalArgumentException("minChallenges must be >= 0");
        if (activeMode != ActiveMode.NEVER && minChallenges > 0 && challengePool.isEmpty()) throw new IllegalArgumentException("challengePool empty but active mode enabled");
    }
    public static Builder builder(Workflow w) { return new Builder().workflow(w); }

    /** Sensible compiled-in defaults so liveness is enforced even before the first config sync. */
    public static LivenessPolicy defaults(Workflow w) {
        Builder b = builder(w).policyVersion("builtin-1.0");
        switch (w) {
            case RESIDENT -> b.passiveThreshold(0.80).activeMode(ActiveMode.ON_LOW_CONFIDENCE).minChallenges(2).maxChallenges(3).maxRetries(3).onMaxRetries(OnMaxRetries.ASSISTED_CAPTURE_WITH_SUPERVISOR);
            case OPERATOR -> b.passiveThreshold(0.85).activeMode(ActiveMode.ALWAYS).minChallenges(1).maxChallenges(2).maxRetries(3).onMaxRetries(OnMaxRetries.LOCK_AND_ALERT);
            case SUPERVISOR -> b.passiveThreshold(0.90).activeMode(ActiveMode.ALWAYS).minChallenges(2).maxChallenges(3).maxRetries(2).onMaxRetries(OnMaxRetries.LOCK_AND_ALERT);
        }
        return b.build();
    }

    public String policyVersion() { return policyVersion; }
    public Workflow workflow() { return workflow; }
    public boolean enabled() { return enabled; }
    public double passiveThreshold() { return passiveThreshold; }
    public double padHardThreshold() { return padHardThreshold; }
    public long passiveWindowMillis() { return passiveWindowMillis; }
    public int passiveMinFrames() { return passiveMinFrames; }
    public ActiveMode activeMode() { return activeMode; }
    public int minChallenges() { return minChallenges; }
    public int maxChallenges() { return maxChallenges; }
    public Set<ChallengeType> challengePool() { return challengePool; }
    public Set<ChallengeType> accessibilityPool() { return accessibilityPool; }
    public boolean requireDepthRevealingChallenge() { return requireDepthRevealingChallenge; }
    public long challengeTimeoutMillis() { return challengeTimeoutMillis; }
    public long minActionLatencyMillis() { return minActionLatencyMillis; }
    public long maxActionLatencyMillis() { return maxActionLatencyMillis; }
    public int maxRetries() { return maxRetries; }
    public OnMaxRetries onMaxRetries() { return onMaxRetries; }
    public long sessionTimeoutMillis() { return sessionTimeoutMillis; }
    public double minFaceQuality() { return minFaceQuality; }
    public double identityDriftThreshold() { return identityDriftThreshold; }
    public Map<String, Double> signalWeights() { return signalWeights; }
    public double signalWeight(String id) { return signalWeights.getOrDefault(id, 1.0); }
    public boolean requireIdentityContinuity() { return requireIdentityContinuity; }

    public static final class Builder {
        private String policyVersion; private Workflow workflow; private boolean enabled = true;
        private double passiveThreshold = 0.8, padHardThreshold = 0.9; private long passiveWindowMillis = 2500; private int passiveMinFrames = 8;
        private ActiveMode activeMode = ActiveMode.ON_LOW_CONFIDENCE; private int minChallenges = 2, maxChallenges = 3;
        private Set<ChallengeType> challengePool = EnumSet.of(ChallengeType.BLINK, ChallengeType.SMILE, ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT, ChallengeType.NOD);
        private Set<ChallengeType> accessibilityPool = EnumSet.of(ChallengeType.BLINK, ChallengeType.SMILE, ChallengeType.HOLD_STILL);
        private boolean requireDepthRevealingChallenge = true; private long challengeTimeoutMillis = 8000;
        private long minActionLatencyMillis = 150, maxActionLatencyMillis = 6000; private int maxRetries = 3;
        private OnMaxRetries onMaxRetries = OnMaxRetries.RETRY_ONLY; private long sessionTimeoutMillis = 60000;
        private double minFaceQuality = 0.4, identityDriftThreshold = 0.45; private Map<String, Double> signalWeights = new java.util.HashMap<>();
        private boolean requireIdentityContinuity = true;
        public Builder policyVersion(String v) { policyVersion = v; return this; }
        public Builder workflow(Workflow v) { workflow = v; return this; }
        public Builder enabled(boolean v) { enabled = v; return this; }
        public Builder passiveThreshold(double v) { passiveThreshold = v; return this; }
        public Builder padHardThreshold(double v) { padHardThreshold = v; return this; }
        public Builder passiveWindowMillis(long v) { passiveWindowMillis = v; return this; }
        public Builder passiveMinFrames(int v) { passiveMinFrames = v; return this; }
        public Builder activeMode(ActiveMode v) { activeMode = v; return this; }
        public Builder minChallenges(int v) { minChallenges = v; return this; }
        public Builder maxChallenges(int v) { maxChallenges = v; return this; }
        public Builder challengePool(Set<ChallengeType> v) { challengePool = v; return this; }
        public Builder accessibilityPool(Set<ChallengeType> v) { accessibilityPool = v; return this; }
        public Builder requireDepthRevealingChallenge(boolean v) { requireDepthRevealingChallenge = v; return this; }
        public Builder challengeTimeoutMillis(long v) { challengeTimeoutMillis = v; return this; }
        public Builder minActionLatencyMillis(long v) { minActionLatencyMillis = v; return this; }
        public Builder maxActionLatencyMillis(long v) { maxActionLatencyMillis = v; return this; }
        public Builder maxRetries(int v) { maxRetries = v; return this; }
        public Builder onMaxRetries(OnMaxRetries v) { onMaxRetries = v; return this; }
        public Builder sessionTimeoutMillis(long v) { sessionTimeoutMillis = v; return this; }
        public Builder minFaceQuality(double v) { minFaceQuality = v; return this; }
        public Builder identityDriftThreshold(double v) { identityDriftThreshold = v; return this; }
        public Builder signalWeights(Map<String, Double> v) { signalWeights = new java.util.HashMap<>(v); return this; }
        public Builder signalWeight(String id, double w) { signalWeights.put(id, w); return this; }
        public Builder requireIdentityContinuity(boolean v) { requireIdentityContinuity = v; return this; }
        public LivenessPolicy build() { return new LivenessPolicy(this); }
    }
}
