package io.mosip.vivid.bundle;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Objects;

/**
 * Produces {@code manifest.sig}: the base64 text of a {@code SHA256withECDSA} (EC key) or {@code SHA256withRSA}
 * (RSA key) signature over the exact bytes of {@code manifest.json}.
 */
public final class BundleSigner {
    private final PrivateKey key;

    public BundleSigner(PrivateKey key) {
        this.key = Objects.requireNonNull(key, "key");
    }

    /** Raw signature bytes over the manifest bytes. */
    public byte[] sign(byte[] manifestBytes) {
        try {
            Signature sig = Signature.getInstance(KeyIo.signatureAlgorithm(key));
            sig.initSign(key);
            sig.update(manifestBytes);
            return sig.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("signing failed", e);
        }
    }

    /** The content of {@code manifest.sig}: base64 of {@link #sign}, no line breaks, trailing newline. */
    public byte[] signatureEntry(byte[] manifestBytes) {
        return (Base64.getEncoder().encodeToString(sign(manifestBytes)) + "\n").getBytes(StandardCharsets.US_ASCII);
    }
}
