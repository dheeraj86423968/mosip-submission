package io.mosip.vivid.av;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * The random code the resident reads aloud. Digits are drawn independently and uniformly from {@link SecureRandom},
 * so a four-digit code carries log2(10^4) ≈ 13.3 bits: a pre-recorded answer is right with probability 1/10,000.
 */
public final class SpokenCode {
    private final String digits;

    private SpokenCode(String digits) { this.digits = digits; }

    public static SpokenCode random(SecureRandom random, int length) {
        Objects.requireNonNull(random, "random");
        if (length < 3 || length > 8) throw new IllegalArgumentException("code length must be 3..8");
        StringBuilder b = new StringBuilder(length);
        for (int i = 0; i < length; i++) b.append((char) ('0' + random.nextInt(10)));
        return new SpokenCode(b.toString());
    }

    /** For tests and replay of a known code. */
    public static SpokenCode of(String digits) {
        if (digits == null || !digits.matches("[0-9]{3,8}")) throw new IllegalArgumentException("3-8 digits required");
        return new SpokenCode(digits);
    }

    public String digits() { return digits; }
    public int length() { return digits.length(); }

    /** Digits separated by spaces ("4 8 1 7") so screen readers and residents read them one by one. */
    public String display() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0) b.append(' ');
            b.append(digits.charAt(i));
        }
        return b.toString();
    }

    @Override public String toString() { return display(); }
    @Override public boolean equals(Object o) { return o instanceof SpokenCode && ((SpokenCode) o).digits.equals(digits); }
    @Override public int hashCode() { return digits.hashCode(); }
}
