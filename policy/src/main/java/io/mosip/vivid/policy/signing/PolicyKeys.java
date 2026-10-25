package io.mosip.vivid.policy.signing;

import io.mosip.vivid.policy.PolicyException;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Key generation and Base64 (PKCS#8 / X.509) encoding helpers for policy signing, using only {@code java.security}.
 * Production deployments are expected to obtain the public key from the MOSIP key manager; this class exists
 * for tooling, tests and air-gapped pilots.
 */
public final class PolicyKeys {

    private PolicyKeys() {}

    /** Generates a P-256 EC key pair (the recommended choice: small signatures, fast verification). */
    public static KeyPair generateEc() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new PolicyException("cannot generate EC key pair", e);
        }
    }

    /** Generates an RSA key pair of the given size (2048 minimum recommended). */
    public static KeyPair generateRsa(int bits) {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(bits);
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new PolicyException("cannot generate RSA key pair", e);
        }
    }

    /** Base64 of the X.509 SubjectPublicKeyInfo encoding. */
    public static String encodePublic(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    /** Base64 of the PKCS#8 encoding. Handle with care; never log. */
    public static String encodePrivate(PrivateKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    /** Decodes a Base64 X.509 public key of the given algorithm ("EC" or "RSA"). */
    public static PublicKey decodePublic(String base64, String algorithm) {
        try {
            return KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64.trim())));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new PolicyException("cannot decode " + algorithm + " public key", e);
        }
    }

    /** Decodes a Base64 PKCS#8 private key of the given algorithm ("EC" or "RSA"). */
    public static PrivateKey decodePrivate(String base64, String algorithm) {
        try {
            return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64.trim())));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new PolicyException("cannot decode " + algorithm + " private key", e);
        }
    }

    /** Tries EC first, then RSA, for a Base64 public key of unknown type. */
    public static PublicKey decodePublicAny(String base64) {
        try {
            return decodePublic(base64, "EC");
        } catch (PolicyException e) {
            return decodePublic(base64, "RSA");
        }
    }

    /** Tries EC first, then RSA, for a Base64 private key of unknown type. */
    public static PrivateKey decodePrivateAny(String base64) {
        try {
            return decodePrivate(base64, "EC");
        } catch (PolicyException e) {
            return decodePrivate(base64, "RSA");
        }
    }
}
