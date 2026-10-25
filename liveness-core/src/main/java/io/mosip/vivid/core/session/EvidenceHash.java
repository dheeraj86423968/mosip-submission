package io.mosip.vivid.core.session;

import io.mosip.vivid.api.ChallengeOutcome;
import io.mosip.vivid.api.LivenessResult;
import io.mosip.vivid.api.Workflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Tamper-evident digest of a decision record: SHA-256 (hex) over a canonical string of the session id, workflow,
 * decision, per-signal scores, challenge sequence with outcomes, nonce, policy version and model bundle version.
 * Audit consumers can recompute it from the audited fields; the Registration Client stores it next to the
 * capture so a decision cannot be altered after the fact without detection.
 */
public final class EvidenceHash {

    private EvidenceHash() {}

    /** The canonical string that is hashed; exposed so verifiers can rebuild it. */
    public static String canonicalString(String sessionId, Workflow workflow, LivenessResult.Decision decision,
                                         Map<String, Double> signalScores, List<ChallengeOutcome> challenges,
                                         String nonceHex, String policyVersion, String modelBundleVersion) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("v1|").append(Objects.requireNonNull(sessionId)).append('|')
                .append(Objects.requireNonNull(workflow).name()).append('|')
                .append(Objects.requireNonNull(decision).name()).append('|');
        Map<String, Double> sorted = new TreeMap<>(signalScores == null ? Map.of() : signalScores);
        boolean first = true;
        for (Map.Entry<String, Double> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(String.format(Locale.ROOT, "%.6f", e.getValue()));
        }
        sb.append('|');
        first = true;
        for (ChallengeOutcome c : challenges == null ? List.<ChallengeOutcome>of() : challenges) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(c.challenge().type().name()).append(':').append(c.challenge().index()).append(':')
                    .append(c.result().name()).append(':').append(c.latencyMillis()).append(':').append(c.durationMillis());
        }
        sb.append('|').append(nonceHex == null ? "" : nonceHex)
                .append('|').append(policyVersion == null ? "" : policyVersion)
                .append('|').append(modelBundleVersion == null ? "" : modelBundleVersion);
        return sb.toString();
    }

    /** SHA-256 hex of {@link #canonicalString}. */
    public static String compute(String sessionId, Workflow workflow, LivenessResult.Decision decision,
                                 Map<String, Double> signalScores, List<ChallengeOutcome> challenges,
                                 String nonceHex, String policyVersion, String modelBundleVersion) {
        return sha256Hex(canonicalString(sessionId, workflow, decision, signalScores, challenges, nonceHex, policyVersion, modelBundleVersion));
    }

    /** Recomputes the hash of a result and compares it to the one it carries. */
    public static boolean verify(LivenessResult result) {
        Objects.requireNonNull(result, "result");
        String expected = compute(result.sessionId(), result.workflow(), result.decision(), result.signalScores(),
                result.challenges(), result.nonceHex(), result.policyVersion(), result.modelBundleVersion());
        return expected.equals(result.evidenceHash());
    }

    /** Lower-case hex SHA-256 of a UTF-8 string. */
    public static String sha256Hex(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return toHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Lower-case hex of bytes. */
    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
