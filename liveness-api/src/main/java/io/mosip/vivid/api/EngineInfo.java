package io.mosip.vivid.api;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/** Static description of an engine implementation and the model bundle it loaded. */
public final class EngineInfo {
    private final String engineId;
    private final String engineVersion;
    private final String modelBundleId;
    private final String modelBundleVersion;
    private final Set<ChallengeType> supportedChallenges;
    private final Set<String> signalIds;
    private final boolean producesEmbeddings;

    public EngineInfo(String engineId, String engineVersion, String modelBundleId, String modelBundleVersion,
                      Set<ChallengeType> supportedChallenges, Set<String> signalIds, boolean producesEmbeddings) {
        this.engineId = Objects.requireNonNull(engineId); this.engineVersion = Objects.requireNonNull(engineVersion);
        this.modelBundleId = Objects.requireNonNullElse(modelBundleId, "none");
        this.modelBundleVersion = Objects.requireNonNullElse(modelBundleVersion, "none");
        this.supportedChallenges = Collections.unmodifiableSet(Objects.requireNonNull(supportedChallenges));
        this.signalIds = Collections.unmodifiableSet(Objects.requireNonNull(signalIds));
        this.producesEmbeddings = producesEmbeddings;
    }
    public String engineId() { return engineId; }
    public String engineVersion() { return engineVersion; }
    public String modelBundleId() { return modelBundleId; }
    public String modelBundleVersion() { return modelBundleVersion; }
    public Set<ChallengeType> supportedChallenges() { return supportedChallenges; }
    public Set<String> signalIds() { return signalIds; }
    public boolean producesEmbeddings() { return producesEmbeddings; }
}
