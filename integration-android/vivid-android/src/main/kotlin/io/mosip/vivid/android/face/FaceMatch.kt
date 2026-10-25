package io.mosip.vivid.android.face

import io.mosip.vivid.api.Workflow
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * Face-match settings for one workflow, read from the `faceMatch` object of the same per-workflow policy
 * JSON that [io.mosip.vivid.android.policy.PolicyJson] parses (PolicyJson ignores the object):
 *
 * ```
 * {"enabled":true, ..., "faceMatch":{"enabled":true,"threshold":0.5,"requireEnrolment":false}}
 * ```
 *
 * - OPERATOR / SUPERVISOR: the live anchor embedding of a PASSED session is compared with the user's
 *   enrolled face (ARC `user_biometric`, written at operator onboarding).
 * - RESIDENT: the anchor is compared with the face in the signed SBI `rcapture` image (capture binding).
 *
 * [threshold] is a cosine **similarity** (higher = more alike) on L2-normalised MobileFaceNet embeddings.
 * The 0.50 default is a starting point, not a calibrated value: the enrolled image comes from the SBI
 * camera and the live one from the tablet's front camera, so same-person scores run lower than the
 * in-session identity-continuity check (policy `identityDriftThreshold` 0.45 cosine distance = 0.55
 * similarity). Calibrate on the deployment's own devices before relying on it.
 */
data class FaceMatchPolicy(
    val enabled: Boolean,
    val threshold: Double,
    /** When true, a user with no usable enrolled face is rejected instead of passing on liveness alone. */
    val requireEnrolment: Boolean
) {
    companion object {
        const val DEFAULT_THRESHOLD = 0.50

        fun defaults(@Suppress("UNUSED_PARAMETER") workflow: Workflow): FaceMatchPolicy =
            FaceMatchPolicy(enabled = true, threshold = DEFAULT_THRESHOLD, requireEnrolment = false)

        fun parse(workflow: Workflow, policyJson: String?): FaceMatchPolicy {
            val d = defaults(workflow)
            if (policyJson.isNullOrBlank()) return d
            val o = runCatching { JSONObject(policyJson).optJSONObject("faceMatch") }.getOrNull() ?: return d
            val t = o.optDouble("threshold", d.threshold)
            return FaceMatchPolicy(
                enabled = o.optBoolean("enabled", d.enabled),
                threshold = if (t.isNaN() || t <= 0.0 || t >= 1.0) d.threshold else t,
                requireEnrolment = o.optBoolean("requireEnrolment", d.requireEnrolment)
            )
        }
    }
}

/** Outcome of comparing a live anchor with reference faces. [similarity] is the best cosine similarity, NaN if none. */
data class FaceMatchOutcome(
    val status: Status,
    val accepted: Boolean,
    val similarity: Double,
    val threshold: Double,
    val referencesCompared: Int,
    val detail: String? = null
) {
    enum class Status {
        /** Best similarity >= threshold. */
        MATCHED,
        /** At least one reference compared, all below threshold. */
        NOT_MATCHED,
        /** No enrolled face stored for the user (e.g. onboarded with fingerprints only, or not onboarded). */
        NO_ENROLLED_FACE,
        /** Enrolled records exist but none could be decoded or contained a detectable face. */
        ENROLMENT_UNUSABLE,
        /** No PASSED session (or it expired, or it produced no anchor embedding). */
        NO_ANCHOR,
        /** No face found in the probe image (capture binding). */
        NO_FACE,
        /** Face matching is switched off by policy. */
        DISABLED,
        /** Models missing or an internal error. */
        UNAVAILABLE
    }
}

/** Pure decision logic, kept free of Android types so it runs in JVM unit tests. */
object FaceMatcher {

    /** Cosine similarity in [-1, 1]. Throws on length mismatch or a zero vector. */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size && a.isNotEmpty()) { "embeddings must have the same non-zero length (${a.size} vs ${b.size})" }
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i].toDouble() * b[i]; na += a[i].toDouble() * a[i]; nb += b[i].toDouble() * b[i] }
        require(na > 0 && nb > 0) { "zero embedding vector" }
        return (dot / (sqrt(na) * sqrt(nb))).coerceIn(-1.0, 1.0)
    }

    /**
     * Compares [anchor] with each reference embedding and accepts on the best score.
     * [storedRecords] is how many enrolled records existed before decoding (0 -> NO_ENROLLED_FACE,
     * >0 but no usable [references] -> ENROLMENT_UNUSABLE). Both of those are accepted only when
     * the policy does not require enrolment.
     */
    fun decide(policy: FaceMatchPolicy, anchor: FloatArray?, references: List<FloatArray>, storedRecords: Int): FaceMatchOutcome {
        val t = policy.threshold
        if (!policy.enabled) return FaceMatchOutcome(FaceMatchOutcome.Status.DISABLED, true, Double.NaN, t, 0)
        if (anchor == null) return FaceMatchOutcome(FaceMatchOutcome.Status.NO_ANCHOR, false, Double.NaN, t, 0)
        if (references.isEmpty()) {
            val status = if (storedRecords == 0) FaceMatchOutcome.Status.NO_ENROLLED_FACE else FaceMatchOutcome.Status.ENROLMENT_UNUSABLE
            return FaceMatchOutcome(status, !policy.requireEnrolment, Double.NaN, t, 0)
        }
        var best = Double.NEGATIVE_INFINITY
        var compared = 0
        for (r in references) {
            val s = runCatching { cosineSimilarity(anchor, r) }.getOrNull() ?: continue
            compared++
            if (s > best) best = s
        }
        if (compared == 0) return FaceMatchOutcome(FaceMatchOutcome.Status.ENROLMENT_UNUSABLE, !policy.requireEnrolment, Double.NaN, t, 0,
            "embedding dimension mismatch")
        val ok = best >= t
        return FaceMatchOutcome(if (ok) FaceMatchOutcome.Status.MATCHED else FaceMatchOutcome.Status.NOT_MATCHED, ok, best, t, compared)
    }
}
