package io.mosip.vivid.bundle;

import java.nio.file.Path;
import java.util.Objects;

/** A bundle that passed {@link BundleVerifier}: its manifest, the exact signed bytes and where it was read from. */
public final class VerifiedBundle {
    private final BundleManifest manifest;
    private final byte[] manifestBytes;
    private final byte[] signatureBytes;
    private final Path source;

    VerifiedBundle(BundleManifest manifest, byte[] manifestBytes, byte[] signatureBytes, Path source) {
        this.manifest = Objects.requireNonNull(manifest);
        this.manifestBytes = manifestBytes.clone();
        this.signatureBytes = signatureBytes.clone();
        this.source = Objects.requireNonNull(source);
    }

    public BundleManifest manifest() { return manifest; }
    /** Exact {@code manifest.json} bytes the signature covers. */
    public byte[] manifestBytes() { return manifestBytes.clone(); }
    /** Exact {@code manifest.sig} bytes. */
    public byte[] signatureBytes() { return signatureBytes.clone(); }
    /** The {@code .mlb} file or installed directory that was verified. */
    public Path source() { return source; }

    @Override public String toString() { return manifest + " from " + source; }
}
