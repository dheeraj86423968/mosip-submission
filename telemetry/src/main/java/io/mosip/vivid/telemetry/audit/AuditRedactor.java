package io.mosip.vivid.telemetry.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Removes attributes whose key suggests biometric payload (frames, pixels, images, embeddings) so that nothing
 * biometric can reach an audit log, even by mistake in a future engine. Applied automatically by
 * {@link AuditRecord.Builder}; also usable standalone by sinks that receive records from elsewhere.
 */
public final class AuditRedactor {

    /** Keys matching this pattern (case-insensitive) are dropped. */
    public static final Pattern FORBIDDEN_KEY = Pattern.compile("(frame|pixel|image|embedding|landmark|biometric)", Pattern.CASE_INSENSITIVE);

    /** Attribute values longer than this are truncated; audit attributes are summaries, not payloads. */
    public static final int MAX_VALUE_LENGTH = 512;

    private AuditRedactor() {}

    /** True if the key must not appear in an audit record. */
    public static boolean isForbiddenKey(String key) {
        return key == null || FORBIDDEN_KEY.matcher(key).find();
    }

    /** Returns a copy without forbidden keys, with over-long values truncated and {@code null} values rendered as "". */
    public static Map<String, String> redact(Map<String, ?> attributes) {
        Map<String, String> out = new LinkedHashMap<>();
        if (attributes == null) {
            return out;
        }
        for (Map.Entry<String, ?> e : attributes.entrySet()) {
            if (isForbiddenKey(e.getKey())) {
                continue;
            }
            String v = e.getValue() == null ? "" : String.valueOf(e.getValue());
            if (v.length() > MAX_VALUE_LENGTH) {
                v = v.substring(0, MAX_VALUE_LENGTH) + "...";
            }
            out.put(e.getKey(), v);
        }
        return out;
    }
}
