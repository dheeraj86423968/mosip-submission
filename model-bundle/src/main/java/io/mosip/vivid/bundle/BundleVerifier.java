package io.mosip.vivid.bundle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static io.mosip.vivid.bundle.BundleManifest.MANIFEST_ENTRY;
import static io.mosip.vivid.bundle.BundleManifest.SIGNATURE_ENTRY;

/**
 * Verifies a bundle before anything is read from it:
 * <ol>
 *   <li>{@code manifest.json} and {@code manifest.sig} must exist;</li>
 *   <li>the signature must verify under at least one pinned public key (a small trust set allows key rotation);</li>
 *   <li>the manifest must be structurally valid and every path safe;</li>
 *   <li>every referenced file must exist and match its SHA-256 (self-test images are required to exist; a hash is
 *       checked only when the manifest gives one, which it does not for images).</li>
 * </ol>
 * Signature is verified before parsing so a malicious manifest never reaches the JSON parser unauthenticated.
 * Both {@code .mlb} archives and already-extracted directories (the store layout) can be verified.
 */
public final class BundleVerifier {
    private static final Logger LOG = LoggerFactory.getLogger(BundleVerifier.class);
    private static final long MAX_MANIFEST_BYTES = 1L << 20;

    private final List<PublicKey> trustedKeys;

    public BundleVerifier(PublicKey trustedKey) {
        this(Collections.singletonList(Objects.requireNonNull(trustedKey)));
    }

    /** Several keys are accepted so that a new signing key can be rolled out before the old one is retired. */
    public BundleVerifier(List<PublicKey> trustedKeys) {
        if (trustedKeys == null || trustedKeys.isEmpty()) throw new IllegalArgumentException("at least one trusted key required");
        this.trustedKeys = List.copyOf(trustedKeys);
    }

    /** Verifies an {@code .mlb} archive. */
    public VerifiedBundle verify(Path mlb) throws BundleRejectedException {
        Objects.requireNonNull(mlb);
        try (ZipFile zip = new ZipFile(mlb.toFile())) {
            byte[] manifestBytes = readEntry(zip, MANIFEST_ENTRY, BundleRejectedException.Reason.MISSING_MANIFEST);
            byte[] sigBytes = readEntry(zip, SIGNATURE_ENTRY, BundleRejectedException.Reason.MISSING_SIGNATURE);
            BundleManifest manifest = authenticate(manifestBytes, sigBytes, mlb);
            // Unsafe entry names are rejected even if the manifest does not reference them.
            java.util.Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.isDirectory() && !BundleManifest.isSafeRelativePath(e.getName())) {
                    throw new BundleRejectedException(BundleRejectedException.Reason.UNSAFE_PATH, "zip entry " + e.getName());
                }
            }
            for (BundleManifest.ModelEntry m : manifest.getModels()) {
                ZipEntry e = zip.getEntry(m.getFile());
                if (e == null || e.isDirectory()) {
                    throw new BundleRejectedException(BundleRejectedException.Reason.MISSING_FILE, m.getFile());
                }
                try (InputStream in = zip.getInputStream(e)) {
                    checkHash(m, Sha256.hex(in));
                }
            }
            for (BundleManifest.SelfTestEntry s : manifest.getSelfTest()) {
                ZipEntry e = zip.getEntry(s.getFile());
                if (e == null || e.isDirectory()) {
                    throw new BundleRejectedException(BundleRejectedException.Reason.MISSING_FILE, s.getFile());
                }
            }
            LOG.info("verified bundle {} from {}", manifest, mlb);
            return new VerifiedBundle(manifest, manifestBytes, sigBytes, mlb);
        } catch (IOException e) {
            throw new BundleRejectedException(BundleRejectedException.Reason.IO_ERROR, mlb + ": " + e.getMessage(), e);
        }
    }

    /** Re-verifies an installed bundle directory (same checks, files on disk instead of zip entries). */
    public VerifiedBundle verifyDirectory(Path dir) throws BundleRejectedException {
        Objects.requireNonNull(dir);
        try {
            Path manifestPath = dir.resolve(MANIFEST_ENTRY);
            Path sigPath = dir.resolve(SIGNATURE_ENTRY);
            if (!Files.isRegularFile(manifestPath)) {
                throw new BundleRejectedException(BundleRejectedException.Reason.MISSING_MANIFEST, manifestPath.toString());
            }
            if (!Files.isRegularFile(sigPath)) {
                throw new BundleRejectedException(BundleRejectedException.Reason.MISSING_SIGNATURE, sigPath.toString());
            }
            byte[] manifestBytes = Files.readAllBytes(manifestPath);
            byte[] sigBytes = Files.readAllBytes(sigPath);
            BundleManifest manifest = authenticate(manifestBytes, sigBytes, dir);
            for (BundleManifest.ModelEntry m : manifest.getModels()) {
                Path f = dir.resolve(m.getFile()).normalize();
                if (!f.startsWith(dir.normalize()) || !Files.isRegularFile(f)) {
                    throw new BundleRejectedException(BundleRejectedException.Reason.MISSING_FILE, m.getFile());
                }
                checkHash(m, Sha256.hex(f));
            }
            for (BundleManifest.SelfTestEntry s : manifest.getSelfTest()) {
                Path f = dir.resolve(s.getFile()).normalize();
                if (!f.startsWith(dir.normalize()) || !Files.isRegularFile(f)) {
                    throw new BundleRejectedException(BundleRejectedException.Reason.MISSING_FILE, s.getFile());
                }
            }
            return new VerifiedBundle(manifest, manifestBytes, sigBytes, dir);
        } catch (IOException e) {
            throw new BundleRejectedException(BundleRejectedException.Reason.IO_ERROR, dir + ": " + e.getMessage(), e);
        }
    }

    /** Signature check followed by parse and structural validation. */
    private BundleManifest authenticate(byte[] manifestBytes, byte[] sigBytes, Path source) throws BundleRejectedException {
        byte[] signature;
        try {
            signature = Base64.getMimeDecoder().decode(new String(sigBytes, StandardCharsets.US_ASCII).trim());
        } catch (IllegalArgumentException e) {
            throw new BundleRejectedException(BundleRejectedException.Reason.BAD_SIGNATURE, "manifest.sig is not base64");
        }
        boolean ok = false;
        for (PublicKey key : trustedKeys) {
            try {
                Signature verifier = Signature.getInstance(KeyIo.signatureAlgorithm(key));
                verifier.initVerify(key);
                verifier.update(manifestBytes);
                if (verifier.verify(signature)) {
                    ok = true;
                    break;
                }
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                LOG.debug("signature check with key {} failed: {}", key.getAlgorithm(), e.toString());
            }
        }
        if (!ok) {
            LOG.warn("REJECTED bundle at {}: signature does not verify under any trusted key", source);
            throw new BundleRejectedException(BundleRejectedException.Reason.BAD_SIGNATURE, "manifest signature invalid for " + source);
        }
        BundleManifest manifest = BundleManifest.parse(manifestBytes);
        manifest.validate();
        return manifest;
    }

    private static void checkHash(BundleManifest.ModelEntry m, String actual) throws BundleRejectedException {
        if (!Sha256.equalsHex(m.getSha256(), actual)) {
            throw new BundleRejectedException(BundleRejectedException.Reason.HASH_MISMATCH,
                    m.getFile() + " expected " + m.getSha256() + " but was " + actual);
        }
    }

    private static byte[] readEntry(ZipFile zip, String name, BundleRejectedException.Reason missing)
            throws IOException, BundleRejectedException {
        ZipEntry e = zip.getEntry(name);
        if (e == null || e.isDirectory()) throw new BundleRejectedException(missing, name + " not in archive");
        if (e.getSize() > MAX_MANIFEST_BYTES) {
            throw new BundleRejectedException(BundleRejectedException.Reason.MALFORMED_MANIFEST, name + " is larger than 1 MiB");
        }
        try (InputStream in = zip.getInputStream(e)) {
            return in.readNBytes((int) MAX_MANIFEST_BYTES + 1);
        }
    }

    /** Map of entry name to size for diagnostics / the {@code list} tool command. */
    static Map<String, Long> entrySizes(Path mlb) throws IOException {
        Map<String, Long> out = new HashMap<>();
        try (ZipFile zip = new ZipFile(mlb.toFile())) {
            java.util.Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.isDirectory()) out.put(e.getName(), e.getSize());
            }
        }
        return out;
    }
}
