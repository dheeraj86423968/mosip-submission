package io.mosip.vivid.bundle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Reads and writes signing keys in the formats a MOSIP operator is likely to hand us:
 * PKCS#8 private keys and X.509 SubjectPublicKeyInfo public keys, either DER or PEM encoded.
 * Both EC (P-256, signature {@code SHA256withECDSA}) and RSA ({@code SHA256withRSA}) keys are supported.
 */
public final class KeyIo {
    private KeyIo() {}

    /** Generates a P-256 EC key pair, the recommended bundle signing key type. */
    public static KeyPair generateEcKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("EC key generation unavailable", e);
        }
    }

    /** Generates an RSA key pair of the given size (3072 recommended). */
    public static KeyPair generateRsaKeyPair(int bits) {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(bits);
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA key generation unavailable", e);
        }
    }

    /** Signature algorithm matching a key's algorithm ("EC" or "RSA"). */
    public static String signatureAlgorithm(java.security.Key key) {
        switch (key.getAlgorithm()) {
            case "EC": return "SHA256withECDSA";
            case "RSA": return "SHA256withRSA";
            default: throw new IllegalArgumentException("unsupported key algorithm " + key.getAlgorithm());
        }
    }

    /** Parses a public key from PEM or DER bytes, trying EC then RSA. */
    public static PublicKey readPublicKey(byte[] pemOrDer) throws GeneralSecurityException {
        byte[] der = unwrapPem(pemOrDer, "PUBLIC KEY");
        X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
        GeneralSecurityException last = null;
        for (String alg : new String[] {"EC", "RSA"}) {
            try {
                return KeyFactory.getInstance(alg).generatePublic(spec);
            } catch (GeneralSecurityException e) {
                last = e;
            }
        }
        throw last;
    }

    /** Parses a PKCS#8 private key from PEM or DER bytes, trying EC then RSA. */
    public static PrivateKey readPrivateKey(byte[] pemOrDer) throws GeneralSecurityException {
        byte[] der = unwrapPem(pemOrDer, "PRIVATE KEY");
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        GeneralSecurityException last = null;
        for (String alg : new String[] {"EC", "RSA"}) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(spec);
            } catch (GeneralSecurityException e) {
                last = e;
            }
        }
        throw last;
    }

    public static PublicKey readPublicKey(Path file) throws IOException, GeneralSecurityException {
        return readPublicKey(Files.readAllBytes(file));
    }

    public static PrivateKey readPrivateKey(Path file) throws IOException, GeneralSecurityException {
        return readPrivateKey(Files.readAllBytes(file));
    }

    /** PEM encoding of a public key ({@code -----BEGIN PUBLIC KEY-----}). */
    public static String toPem(PublicKey key) {
        return pem("PUBLIC KEY", key.getEncoded());
    }

    /** PEM encoding of a PKCS#8 private key ({@code -----BEGIN PRIVATE KEY-----}). */
    public static String toPem(PrivateKey key) {
        return pem("PRIVATE KEY", key.getEncoded());
    }

    private static String pem(String label, byte[] der) {
        String b64 = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + b64 + "\n-----END " + label + "-----\n";
    }

    /** Returns DER bytes: strips a PEM envelope if present, otherwise returns the input unchanged. */
    static byte[] unwrapPem(byte[] data, String expectedLabel) {
        String text = new String(data, StandardCharsets.US_ASCII);
        if (!text.contains("-----BEGIN")) {
            return data;
        }
        StringBuilder b64 = new StringBuilder();
        boolean inBlock = false;
        for (String line : text.split("\\r?\\n")) {
            String t = line.trim();
            if (t.startsWith("-----BEGIN")) {
                if (!t.contains(expectedLabel)) {
                    throw new IllegalArgumentException("expected a '" + expectedLabel + "' PEM block but found: " + t);
                }
                inBlock = true;
            } else if (t.startsWith("-----END")) {
                break;
            } else if (inBlock && !t.isEmpty()) {
                b64.append(t);
            }
        }
        return Base64.getDecoder().decode(b64.toString());
    }
}
