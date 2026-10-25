package io.mosip.vivid.android.policy

import io.mosip.vivid.api.ChallengeType
import io.mosip.vivid.api.LivenessPolicy
import io.mosip.vivid.api.Workflow
import org.json.JSONObject
import java.util.EnumSet

/**
 * Minimal JSON -> [LivenessPolicy] mapping for Android, using the platform `org.json` so the
 * `policy` module (Jackson, schema validation) is not required on-device. Field names follow
 * PLAN 5.1. Unknown fields are ignored; absent fields keep [LivenessPolicy.defaults] values.
 *
 * Example:
 * {"policyVersion":"2026.04","enabled":true,"passiveThreshold":0.82,"activeMode":"ON_LOW_CONFIDENCE",
 *  "minChallenges":2,"maxChallenges":3,"challengePool":["BLINK","SMILE","TURN_LEFT","TURN_RIGHT","NOD"],
 *  "accessibilityPool":["BLINK","SMILE","HOLD_STILL"],"challengeTimeoutMillis":8000,"maxRetries":3,
 *  "onMaxRetries":"ASSISTED_CAPTURE_WITH_SUPERVISOR","signalWeights":{"rppg":0.5}}
 */
object PolicyJson {

    fun parse(workflow: Workflow, json: String?): LivenessPolicy {
        val defaults = LivenessPolicy.defaults(workflow)
        if (json.isNullOrBlank() || json.trim() == "{}") return defaults
        val o = JSONObject(json)
        val b = LivenessPolicy.builder(workflow)
            .policyVersion(o.optString("policyVersion", defaults.policyVersion()))
            .enabled(o.optBoolean("enabled", defaults.enabled()))
            .passiveThreshold(o.optDouble("passiveThreshold", defaults.passiveThreshold()))
            .padHardThreshold(o.optDouble("padHardThreshold", defaults.padHardThreshold()))
            .passiveWindowMillis(o.optLong("passiveWindowMillis", defaults.passiveWindowMillis()))
            .passiveMinFrames(o.optInt("passiveMinFrames", defaults.passiveMinFrames()))
            .activeMode(enumOr(o.optStringOrNull("activeMode"), defaults.activeMode()))
            .minChallenges(o.optInt("minChallenges", defaults.minChallenges()))
            .maxChallenges(o.optInt("maxChallenges", defaults.maxChallenges()))
            .challengePool(challengeSet(o, "challengePool", defaults.challengePool()))
            .accessibilityPool(challengeSet(o, "accessibilityPool", defaults.accessibilityPool()))
            .requireDepthRevealingChallenge(o.optBoolean("requireDepthRevealingChallenge", defaults.requireDepthRevealingChallenge()))
            .challengeTimeoutMillis(o.optLong("challengeTimeoutMillis", defaults.challengeTimeoutMillis()))
            .minActionLatencyMillis(o.optLong("minActionLatencyMillis", defaults.minActionLatencyMillis()))
            .maxActionLatencyMillis(o.optLong("maxActionLatencyMillis", defaults.maxActionLatencyMillis()))
            .maxRetries(o.optInt("maxRetries", defaults.maxRetries()))
            .onMaxRetries(enumOr(o.optStringOrNull("onMaxRetries"), defaults.onMaxRetries()))
            .sessionTimeoutMillis(o.optLong("sessionTimeoutMillis", defaults.sessionTimeoutMillis()))
            .minFaceQuality(o.optDouble("minFaceQuality", defaults.minFaceQuality()))
            .identityDriftThreshold(o.optDouble("identityDriftThreshold", defaults.identityDriftThreshold()))
            .requireIdentityContinuity(o.optBoolean("requireIdentityContinuity", defaults.requireIdentityContinuity()))
            .signalWeights(defaults.signalWeights())
        o.optJSONObject("signalWeights")?.let { w -> for (k in w.keys()) b.signalWeight(k, w.getDouble(k)) }
        return b.build()
    }

    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key)

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        if (name.isNullOrBlank()) fallback else runCatching { enumValueOf<E>(name.trim().uppercase()) }.getOrDefault(fallback)

    private fun challengeSet(o: JSONObject, key: String, fallback: Set<ChallengeType>): Set<ChallengeType> {
        val arr = o.optJSONArray(key) ?: return fallback
        val set = EnumSet.noneOf(ChallengeType::class.java)
        for (i in 0 until arr.length()) runCatching { set.add(ChallengeType.valueOf(arr.getString(i).trim().uppercase())) }
        return if (set.isEmpty()) fallback else set
    }
}
