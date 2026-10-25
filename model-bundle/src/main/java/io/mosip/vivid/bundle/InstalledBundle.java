package io.mosip.vivid.bundle;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** A bundle that has been verified and extracted into the store. Immutable handle; file paths resolve under {@link #directory()}. */
public final class InstalledBundle {
    private final BundleManifest manifest;
    private final Path directory;

    InstalledBundle(BundleManifest manifest, Path directory) {
        this.manifest = Objects.requireNonNull(manifest);
        this.directory = Objects.requireNonNull(directory);
    }

    public String bundleId() { return manifest.getBundleId(); }
    public String version() { return manifest.getVersion(); }
    public BundleManifest manifest() { return manifest; }
    /** {@code <root>/<bundleId>/<version>} */
    public Path directory() { return directory; }

    /** Absolute path of a model by logical name. */
    public Optional<Path> modelPath(String modelName) {
        return manifest.model(modelName).map(m -> directory.resolve(m.getFile()));
    }

    /** Absolute path of the first model with the given purpose. */
    public Optional<Path> modelPathByPurpose(String purpose) {
        return manifest.modelByPurpose(purpose).map(m -> directory.resolve(m.getFile()));
    }

    /** Absolute path of a file referenced by the manifest (model or self-test). */
    public Path resolve(String manifestRelativePath) {
        return directory.resolve(manifestRelativePath);
    }

    @Override public String toString() { return bundleId() + "@" + version() + " (" + directory + ")"; }
}
