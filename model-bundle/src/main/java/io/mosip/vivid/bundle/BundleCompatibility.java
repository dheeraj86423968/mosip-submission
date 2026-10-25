package io.mosip.vivid.bundle;

import java.util.Objects;

/**
 * The engine/bundle compatibility matrix, enforced at load:
 * <ul>
 *   <li>the bundle's {@code platform} must equal the engine's platform;</li>
 *   <li>the engine version must be {@code >= minEngineVersion};</li>
 *   <li>optionally, a bundle whose major version exceeds the engine's supported major is refused
 *       (a major bump means an incompatible layout).</li>
 * </ul>
 */
public final class BundleCompatibility {
    private final SemanticVersion engineVersion;
    private final String platform;
    private final int maxBundleMajor;

    /**
     * @param engineVersion the running engine's version, e.g. {@code 0.1.0}
     * @param platform      {@link BundleManifest#PLATFORM_JVM} or {@link BundleManifest#PLATFORM_ANDROID}
     * @param maxBundleMajor highest bundle major version this engine understands; {@code Integer.MAX_VALUE} to disable
     */
    public BundleCompatibility(String engineVersion, String platform, int maxBundleMajor) {
        this.engineVersion = SemanticVersion.parse(engineVersion);
        this.platform = Objects.requireNonNull(platform);
        this.maxBundleMajor = maxBundleMajor;
    }

    /** Convenience constructor with no major-version cap. */
    public BundleCompatibility(String engineVersion, String platform) {
        this(engineVersion, platform, Integer.MAX_VALUE);
    }

    public SemanticVersion engineVersion() { return engineVersion; }
    public String platform() { return platform; }

    /** True if {@link #check} would not throw. */
    public boolean isCompatible(BundleManifest manifest) {
        try {
            check(manifest);
            return true;
        } catch (BundleRejectedException e) {
            return false;
        }
    }

    /**
     * @throws BundleRejectedException with {@code INCOMPATIBLE_PLATFORM} or {@code INCOMPATIBLE_ENGINE}
     */
    public void check(BundleManifest manifest) throws BundleRejectedException {
        if (!platform.equals(manifest.getPlatform())) {
            throw new BundleRejectedException(BundleRejectedException.Reason.INCOMPATIBLE_PLATFORM,
                    "bundle targets " + manifest.getPlatform() + " but engine platform is " + platform);
        }
        SemanticVersion min = SemanticVersion.parse(manifest.getMinEngineVersion());
        if (!engineVersion.isAtLeast(min)) {
            throw new BundleRejectedException(BundleRejectedException.Reason.INCOMPATIBLE_ENGINE,
                    "bundle requires engine >= " + min + " but engine is " + engineVersion);
        }
        SemanticVersion bundle = SemanticVersion.parse(manifest.getVersion());
        if (bundle.major() > maxBundleMajor) {
            throw new BundleRejectedException(BundleRejectedException.Reason.INCOMPATIBLE_ENGINE,
                    "bundle major version " + bundle.major() + " exceeds the highest supported major " + maxBundleMajor);
        }
    }
}
