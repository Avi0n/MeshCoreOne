package one.meshcore.shared.persistence

import one.meshcore.shared.backup.RoomMessageDto
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A backup DTO field and its Room column land in the same change. These three were the
 * freeze-then-defer set (lastHeard / failureSeen / regionScopeMatches); import must not drop them.
 */
class BackupDtoRoomColumnTest {
    @Test
    fun contactLastHeardRoundTripsThroughRoom() {
        val dto = ChatFixtures.contact(name = "Heard").copy(lastHeardTimestamp = HEARD_AT)
        val restored = dto.toEntity().toDto()
        assertEquals(HEARD_AT, restored.lastHeardTimestamp)
    }

    @Test
    fun messageFailureSeenAndRegionMatchesRoundTripThroughRoom() {
        val dto = ChatFixtures.message().copy(
            failureSeen = true,
            regionScopeMatches = listOf("de-by", "de-hh"),
        )
        val restored = dto.toEntity().toDto()
        assertTrue(restored.failureSeen)
        assertEquals(listOf("de-by", "de-hh"), restored.regionScopeMatches)
    }

    @Test
    fun roomMessageFailureSeenRoundTripsThroughRoom() {
        val dto = RoomMessageDto(
            id = Uuid.normalized("CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC"),
            sessionID = Uuid.normalized("DDDDDDDD-DDDD-DDDD-DDDD-DDDDDDDDDDDD"),
            authorKeyPrefix = byteArrayOf(0x11),
            text = "hello",
            timestamp = 1L,
            createdAt = 1.0,
            isFromSelf = true,
            deduplicationKey = "k",
            statusRawValue = MessageStatus.SENT.raw,
            retryAttempt = 0,
            maxRetryAttempts = 0,
            failureSeen = true,
        )
        assertTrue(dto.toEntity().toDto().failureSeen)
    }

    private companion object {
        const val HEARD_AT: Long = 1_700_000_042L
    }
}
