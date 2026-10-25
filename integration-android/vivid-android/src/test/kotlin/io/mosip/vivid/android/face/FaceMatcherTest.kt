package io.mosip.vivid.android.face

import io.mosip.vivid.api.Workflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class FaceMatcherTest {

    private fun unit(vararg v: Float): FloatArray { val n = sqrt(v.fold(0f) { a, x -> a + x * x }); return FloatArray(v.size) { v[it] / n } }
    private val policy = FaceMatchPolicy(enabled = true, threshold = 0.5, requireEnrolment = false)

    @Test fun cosineOfIdenticalAndOrthogonalVectors() {
        assertEquals(1.0, FaceMatcher.cosineSimilarity(unit(1f, 2f, 3f), unit(1f, 2f, 3f)), 1e-6)
        assertEquals(0.0, FaceMatcher.cosineSimilarity(unit(1f, 0f), unit(0f, 1f)), 1e-6)
        // not normalised input still works
        assertEquals(1.0, FaceMatcher.cosineSimilarity(floatArrayOf(2f, 0f), floatArrayOf(5f, 0f)), 1e-6)
    }

    @Test fun bestReferenceDecides() {
        val anchor = unit(1f, 0.2f, 0f)
        val far = unit(0f, 0f, 1f)
        val near = unit(1f, 0.25f, 0.05f)
        val o = FaceMatcher.decide(policy, anchor, listOf(far, near), 2)
        assertEquals(FaceMatchOutcome.Status.MATCHED, o.status)
        assertTrue(o.accepted)
        assertEquals(2, o.referencesCompared)
        assertTrue(o.similarity > 0.99)
    }

    @Test fun belowThresholdIsRejected() {
        val o = FaceMatcher.decide(policy, unit(1f, 0f, 0f), listOf(unit(0.4f, 1f, 0f)), 1)
        assertEquals(FaceMatchOutcome.Status.NOT_MATCHED, o.status)
        assertFalse(o.accepted)
        assertTrue(o.similarity < 0.5)
    }

    @Test fun thresholdIsInclusive() {
        val a = unit(1f, 0f); val r = unit(0.6f, 0.8f)
        val exact = FaceMatcher.cosineSimilarity(a, r)
        assertEquals(0.6, exact, 1e-6)
        assertTrue(FaceMatcher.decide(policy.copy(threshold = exact), a, listOf(r), 1).accepted)
        assertFalse(FaceMatcher.decide(policy.copy(threshold = exact + 1e-9), a, listOf(r), 1).accepted)
    }

    @Test fun missingEnrolmentFollowsPolicy() {
        val anchor = unit(1f, 0f)
        val lenient = FaceMatcher.decide(policy, anchor, emptyList(), 0)
        assertEquals(FaceMatchOutcome.Status.NO_ENROLLED_FACE, lenient.status)
        assertTrue(lenient.accepted)
        val strict = FaceMatcher.decide(policy.copy(requireEnrolment = true), anchor, emptyList(), 0)
        assertFalse(strict.accepted)
        val unusable = FaceMatcher.decide(policy, anchor, emptyList(), 2)
        assertEquals(FaceMatchOutcome.Status.ENROLMENT_UNUSABLE, unusable.status)
        assertTrue(unusable.accepted)
    }

    @Test fun noAnchorFailsClosedAndDisabledPasses() {
        assertFalse(FaceMatcher.decide(policy, null, listOf(unit(1f, 0f)), 1).accepted)
        val off = FaceMatcher.decide(policy.copy(enabled = false), null, emptyList(), 0)
        assertEquals(FaceMatchOutcome.Status.DISABLED, off.status)
        assertTrue(off.accepted)
    }

    @Test fun dimensionMismatchIsUnusableNotCrash() {
        val o = FaceMatcher.decide(policy, unit(1f, 0f, 0f), listOf(unit(1f, 0f)), 1)
        assertEquals(FaceMatchOutcome.Status.ENROLMENT_UNUSABLE, o.status)
    }

    @Test fun policyParsing() {
        val d = FaceMatchPolicy.parse(Workflow.OPERATOR, "{}")
        assertEquals(FaceMatchPolicy.defaults(Workflow.OPERATOR), d)
        val p = FaceMatchPolicy.parse(Workflow.SUPERVISOR,
            """{"enabled":true,"faceMatch":{"enabled":false,"threshold":0.62,"requireEnrolment":true}}""")
        assertFalse(p.enabled); assertEquals(0.62, p.threshold, 1e-9); assertTrue(p.requireEnrolment)
        // out-of-range threshold falls back to the default
        assertEquals(FaceMatchPolicy.DEFAULT_THRESHOLD, FaceMatchPolicy.parse(Workflow.OPERATOR, """{"faceMatch":{"threshold":1.7}}""").threshold, 1e-9)
        // garbage JSON -> defaults
        assertEquals(d, FaceMatchPolicy.parse(Workflow.OPERATOR, "not json"))
    }
}
