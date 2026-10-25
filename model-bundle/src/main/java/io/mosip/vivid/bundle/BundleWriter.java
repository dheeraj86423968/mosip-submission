package io.mosip.vivid.bundle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Creates a signed {@code .mlb} from a directory of model files and a manifest template.
 * The template's {@code sha256} fields may be empty: they are computed from the files on disk.
 * The resulting archive is laid out as {@code manifest.json}, {@code manifest.sig}, then the referenced files
 * with their manifest-relative paths.
 */
public final class BundleWriter {
    private final BundleSigner signer;

    public BundleWriter(PrivateKey signingKey) {
        this.signer = new BundleSigner(signingKey);
    }

    /**
     * Fills hashes, signs and writes the archive.
     *
     * @param manifest   template (modified in place: hashes and createdAt are set)
     * @param contentDir directory the manifest's file paths are relative to
     * @param out        destination {@code .mlb}; overwritten
     * @return the exact manifest bytes that were signed
     */
    public byte[] write(BundleManifest manifest, Path contentDir, Path out) throws IOException, BundleRejectedException {
        Objects.requireNonNull(manifest);
        Objects.requireNonNull(contentDir);
        Objects.requireNonNull(out);
        for (BundleManifest.ModelEntry m : manifest.getModels()) {
            Path f = resolveSafe(contentDir, m.getFile());
            m.setSha256(Sha256.hex(f));
        }
        for (BundleManifest.SelfTestEntry s : manifest.getSelfTest()) {
            resolveSafe(contentDir, s.getFile());
        }
        if (manifest.getCreatedAt() == null) manifest.setCreatedAt(Instant.now().toString());
        manifest.validate();

        byte[] manifestBytes = manifest.toJsonBytes();
        byte[] sigBytes = signer.signatureEntry(manifestBytes);
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        try (OutputStream fo = Files.newOutputStream(out); ZipOutputStream zip = new ZipOutputStream(fo)) {
            putBytes(zip, BundleManifest.MANIFEST_ENTRY, manifestBytes);
            putBytes(zip, BundleManifest.SIGNATURE_ENTRY, sigBytes);
            for (String rel : manifest.referencedFiles()) {
                Path f = resolveSafe(contentDir, rel);
                zip.putNextEntry(new ZipEntry(rel));
                try (InputStream in = Files.newInputStream(f)) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
            }
        }
        return manifestBytes;
    }

    private static Path resolveSafe(Path dir, String rel) throws BundleRejectedException {
        if (!BundleManifest.isSafeRelativePath(rel)) {
            throw new BundleRejectedException(BundleRejectedException.Reason.UNSAFE_PATH, rel);
        }
        Path f = dir.resolve(rel).normalize();
        if (!f.startsWith(dir.toAbsolutePath().normalize()) && !f.startsWith(dir.normalize())) {
            throw new BundleRejectedException(BundleRejectedException.Reason.UNSAFE_PATH, rel);
        }
        if (!Files.isRegularFile(f)) {
            throw new BundleRejectedException(BundleRejectedException.Reason.MISSING_FILE, rel + " not found under " + dir);
        }
        return f;
    }

    private static void putBytes(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }
}
