package io.mosip.vivid.device.sbi;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * The SBI 0.9.5 capture hash chain, as MOSIP's MockMDS (v1.2.0.2, {@code SBIServiceResponse.getBiometricData}) and
 * the Registration Client compute it:
 * <pre>
 *   prev  = previousHash empty ? SHA-256(empty byte array) : hexDecode(previousHash)
 *   hash  = upperHex( SHA-256( prev || SHA-256(bioValue) ) )      // raw digest bytes, not hex strings
 * </pre>
 * where {@code bioValue} is the base64url-decoded ISO record from the signed {@code data} payload. Confirmed
 * byte for byte against a recorded MockMDS {@code RCAPTURE} response (see {@code docs/testing/interop-mockmds.md}).
 * Hashing the hex strings instead (which is how the spec text reads) gives a different value.
 */
public final class SbiCaptureHash {
    private SbiCaptureHash() {}

    /** Hash for one biometric given the previous entry's hash ("" for the first). */
    public static String chained(String previousHash, byte[] bioValue) {
        return chainedFromDigest(previousHash, sha256(bioValue));
    }

    /** As {@link #chained} when the caller already has {@code SHA-256(bioValue)}. */
    public static String chainedFromDigest(String previousHash, byte[] bioValueDigest) {
        byte[] prev = previousHash == null || previousHash.trim().isEmpty() ? sha256(new byte[0]) : hexDecode(previousHash.trim());
        byte[] joined = new byte[prev.length + bioValueDigest.length];
        System.arraycopy(prev, 0, joined, 0, prev.length);
        System.arraycopy(bioValueDigest, 0, joined, prev.length, bioValueDigest.length);
        return hex(sha256(joined));
    }

    static byte[] sha256(byte[] b) {
        try { return MessageDigest.getInstance("SHA-256").digest(b); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static byte[] hexDecode(String s) {
        if ((s.length() & 1) != 0) throw new IllegalArgumentException("odd-length hex");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16), lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("not hex: " + s);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02X", x));
        return sb.toString();
    }
}
