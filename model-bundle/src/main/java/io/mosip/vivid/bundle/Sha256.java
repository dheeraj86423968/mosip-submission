package io.mosip.vivid.bundle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Small SHA-256 helper producing lower-case hex, the encoding used in manifests. */
public final class Sha256 {
    private Sha256() {}

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Hex digest of a byte array. */
    public static String hex(byte[] data) {
        return toHex(digest().digest(data));
    }

    /** Hex digest of a whole stream; the stream is consumed but not closed. */
    public static String hex(InputStream in) throws IOException {
        MessageDigest md = digest();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            md.update(buf, 0, n);
        }
        return toHex(md.digest());
    }

    /** Hex digest of a file. */
    public static String hex(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return hex(in);
        }
    }

    /** Constant-time comparison of two hex digests, ignoring case. */
    public static boolean equalsHex(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] y = b.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(x, y);
    }

    static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
