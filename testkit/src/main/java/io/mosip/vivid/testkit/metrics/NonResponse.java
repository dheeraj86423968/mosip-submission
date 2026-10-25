package io.mosip.vivid.testkit.metrics;

import java.util.Locale;
import java.util.Set;

/**
 * Which session outcomes are <b>non-responses</b> (ISO/IEC 30107-3 APNRR / BPNRR) rather than classification
 * decisions. A non-response is a presentation for which the PAD subsystem produced no bona-fide/attack decision:
 * the session ended in {@code ERROR}, or failed for a reason that is a failure to acquire or a system fault rather
 * than a PAD verdict.
 * <ul>
 *   <li>Non-response failure reasons: {@code FACE_NOT_DETECTED}, {@code MULTIPLE_FACES}, {@code POOR_FACE_QUALITY}
 *       (failure to acquire), {@code SESSION_TIMEOUT}, {@code DEVICE_UNAVAILABLE}, {@code DEVICE_CONNECTION_FAILURE},
 *       {@code CAMERA_UNAVAILABLE}, {@code INVALID_FRAME_DATA}, {@code ENGINE_ERROR} (system faults).</li>
 *   <li>Decisions: {@code ERROR} is always a non-response. {@code CANCELLED} (operator abort) is neither a response nor
 *       a non-response and is {@linkplain #isExcluded excluded} from every rate.</li>
 *   <li>Everything else ({@code LIVE}, {@code NOT_LIVE}, {@code ATTACK} with {@code PAD_DETECTED},
 *       {@code LIVENESS_BELOW_THRESHOLD}, {@code CHALLENGE_TIMEOUT}, {@code ACTIVE_CHALLENGE_FAILED},
 *       {@code IDENTITY_DRIFT}, ...) is a response and enters APCER/BPCER.</li>
 * </ul>
 * Strings are used (not the liveness-api enums) so that score files from any engine can be read.
 */
public final class NonResponse {
    /** Failure reasons that mean "no PAD decision was made". */
    public static final Set<String> FAILURES = Set.of(
            "FACE_NOT_DETECTED", "MULTIPLE_FACES", "POOR_FACE_QUALITY", "SESSION_TIMEOUT",
            "DEVICE_UNAVAILABLE", "DEVICE_CONNECTION_FAILURE", "CAMERA_UNAVAILABLE", "INVALID_FRAME_DATA", "ENGINE_ERROR");

    private NonResponse() {}

    /** True when a session with this decision and failure reason (either may be null) produced no PAD decision. */
    public static boolean isNonResponse(String decision, String failure) {
        if (decision != null && norm(decision).equals("ERROR")) return true;
        return failure != null && FAILURES.contains(norm(failure));
    }

    /** True for operator-cancelled sessions, which are left out of every rate. */
    public static boolean isExcluded(String decision, String failure) {
        return (decision != null && norm(decision).equals("CANCELLED")) || (failure != null && norm(failure).equals("CANCELLED"));
    }

    private static String norm(String s) { return s.trim().toUpperCase(Locale.ROOT); }
}
