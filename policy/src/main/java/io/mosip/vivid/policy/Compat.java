package io.mosip.vivid.policy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Java 8 / Android API 28 stand-ins for JDK methods this module would otherwise call. Android's
 * {@code InputStream.readAllBytes()} arrives only in API 33 and {@code String.isBlank()} is a Java 11 API, and this
 * module ships inside the Android Registration Client (minSdk 28).
 */
final class Compat {

    private Compat() {}

    /** Reads the stream to EOF without closing it; equivalent to {@code InputStream.readAllBytes()}. */
    static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(32, in.available()));
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Equivalent to {@code String.isBlank()}: empty or only {@link Character#isWhitespace(int)} code points. */
    static boolean isBlank(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (!Character.isWhitespace(cp)) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }
}
