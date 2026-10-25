package io.mosip.vivid.android.policy

import io.mosip.vivid.api.ChallengeType
import io.mosip.vivid.api.LivenessPolicy
import io.mosip.vivid.api.Workflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PolicyJsonTest {

    @Test fun emptyJsonYieldsCompiledInDefaults() {
        for (wf in Workflow.values()) {
            val d = LivenessPolicy.defaults(wf)
            for (json in listOf(null, "", "  ", "{}")) {
                val p = PolicyJson.parse(wf, json)
                assertEquals(d.passiveThreshold(), p.passiveThreshold(), 0.0)
                assertEquals(d.activeMode(), p.activeMode())
                assertEquals(d.maxRetries(), p.maxRetries())
            }
        }
    }

    @Test fun overridesAreApplied() {
        val p = PolicyJson.parse(Workflow.RESIDENT, """
            {"policyVersion":"2026.04","enabled":false,"passiveThreshold":0.82,"activeMode":"always",
             "minChallenges":1,"maxChallenges":2,"challengePool":["BLINK","turn_left"," nod "],
             "maxRetries":5,"onMaxRetries":"LOCK_AND_ALERT","signalWeights":{"rppg":0.5}}
        """.trimIndent())
        assertEquals("2026.04", p.policyVersion())
        assertFalse(p.enabled())
        assertEquals(0.82, p.passiveThreshold(), 0.0)
        assertEquals(LivenessPolicy.ActiveMode.ALWAYS, p.activeMode())
        assertEquals(setOf(ChallengeType.BLINK, ChallengeType.TURN_LEFT, ChallengeType.NOD), p.challengePool())
        assertEquals(5, p.maxRetries())
        assertEquals(LivenessPolicy.OnMaxRetries.LOCK_AND_ALERT, p.onMaxRetries())
        assertEquals(0.5, p.signalWeights()["rppg"]!!, 0.0)
    }

    @Test fun unknownEnumsAndChallengesFallBack() {
        val d = LivenessPolicy.defaults(Workflow.OPERATOR)
        val p = PolicyJson.parse(Workflow.OPERATOR, """{"activeMode":"SOMETIMES","onMaxRetries":null,"challengePool":["WINK"]}""")
        assertEquals(d.activeMode(), p.activeMode())
        assertEquals(d.onMaxRetries(), p.onMaxRetries())
        assertEquals(d.challengePool(), p.challengePool())
    }
}
