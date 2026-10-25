package io.mosip.vivid.android.bridge

import io.mosip.vivid.android.bridge.gen.ChallengeOutcomeDto
import io.mosip.vivid.android.bridge.gen.LivenessResultDto
import io.mosip.vivid.android.bridge.gen.SessionStateDto
import io.mosip.vivid.api.FaceObservation
import io.mosip.vivid.api.LivenessResult
import io.mosip.vivid.api.SessionState
import io.mosip.vivid.device.DeviceHealth

/** Converts liveness-api objects to the Pigeon DTOs (generated into `bridge.gen`). */
internal object SessionDtoMapper {

    fun toDto(s: SessionState, health: DeviceHealth?, lastFace: FaceObservation?): SessionStateDto {
        val ch = s.challenge().orElse(null)
        val f = s.failure().orElse(null)
        return SessionStateDto(
            phase = s.phase().name,
            hint = s.hint().name,
            challengeType = ch?.type()?.name,
            challengeIndex = ch?.index()?.toLong(),
            challengeTotal = ch?.total()?.toLong(),
            failureReason = f?.name,
            failureSecuritySensitive = f?.isSecuritySensitive,
            attempt = s.attempt().toLong(),
            maxAttempts = s.maxAttempts().toLong(),
            progress = s.progress(),
            passiveScore = s.passiveScore(),
            remainingChallengeMillis = s.remainingChallengeMillis(),
            deviceHealth = health?.state()?.name,
            deviceReason = health?.reason()?.takeIf { it.isNotEmpty() },
            faceBox = lastFace?.let { listOf(it.boxX(), it.boxY(), it.boxW(), it.boxH()) }
        )
    }

    fun toDto(r: LivenessResult): LivenessResultDto = LivenessResultDto(
        sessionId = r.sessionId(),
        workflow = r.workflow().name,
        decision = r.decision().name,
        failureReason = r.failure().map { it.name }.orElse(null),
        fusedLivenessScore = r.fusedLivenessScore(),
        signalScores = HashMap<String?, Double?>(r.signalScores()),
        vetoSignal = r.vetoSignal().orElse(null),
        attackClass = r.attackClass().orElse(null),
        challenges = r.challenges().map { c ->
            ChallengeOutcomeDto(type = c.challenge().type().name, result = c.result().name,
                latencyMillis = c.latencyMillis(), durationMillis = c.durationMillis())
        },
        attemptsUsed = r.attemptsUsed().toLong(),
        passiveMillis = r.passiveMillis(),
        totalMillis = r.totalMillis(),
        framesAnalyzed = r.framesAnalyzed().toLong(),
        engineId = r.engineId() ?: "",
        modelBundleVersion = r.modelBundleVersion() ?: "",
        policyVersion = r.policyVersion() ?: "",
        nonceHex = r.nonceHex() ?: "",
        evidenceHash = r.evidenceHash() ?: ""
    )
}
