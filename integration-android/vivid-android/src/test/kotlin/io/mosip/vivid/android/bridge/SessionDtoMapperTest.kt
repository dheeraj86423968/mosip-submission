package io.mosip.vivid.android.bridge

import io.mosip.vivid.api.Challenge
import io.mosip.vivid.api.ChallengeType
import io.mosip.vivid.api.FailureReason
import io.mosip.vivid.api.SessionState
import io.mosip.vivid.device.DeviceHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDtoMapperTest {

    @Test fun activeChallengeStateMapsToDto() {
        val s = SessionState(SessionState.Phase.ACTIVE_CHALLENGE, SessionState.Hint.PLEASE_CONTINUE,
            Challenge(ChallengeType.TURN_LEFT, 1, 3, 0L, 8000L), null, 1, 3, 0.4, 0.7, 5000L)
        val dto = SessionDtoMapper.toDto(s, DeviceHealth.connected(), null)
        assertEquals("ACTIVE_CHALLENGE", dto.phase)
        assertEquals("PLEASE_CONTINUE", dto.hint)
        assertEquals("TURN_LEFT", dto.challengeType)
        assertEquals(1L, dto.challengeIndex); assertEquals(3L, dto.challengeTotal)
        assertEquals(5000L, dto.remainingChallengeMillis)
        assertEquals("CONNECTED", dto.deviceHealth)
        assertNull(dto.failureReason); assertNull(dto.faceBox)
        // Must survive the Pigeon wire format round trip.
        assertEquals(dto, io.mosip.vivid.android.bridge.gen.SessionStateDto.fromList(dto.toList()))
    }

    @Test fun securitySensitiveFailureIsFlagged() {
        val s = SessionState(SessionState.Phase.FAILED, SessionState.Hint.TRY_AGAIN, null, FailureReason.PAD_DETECTED, 1, 3, 1.0, 0.1, 0L)
        val dto = SessionDtoMapper.toDto(s, null, null)
        assertEquals("PAD_DETECTED", dto.failureReason)
        assertTrue(dto.failureSecuritySensitive == true)
        assertNull(dto.deviceHealth)
    }
}
