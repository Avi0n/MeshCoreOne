package one.meshcore.shared.persistence

import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Read-side session DTO mapping, including the lastMessageDate sync-timestamp fallback. */
class ConversationMappersTest {
    @Test
    fun sessionLastMessageDateWinsOverSyncTimestamp() {
        val dto = session(
            lastMessageDate = 1_700_000_100.0,
            lastSyncTimestamp = 1_700_000_000L,
        ).toDto()
        assertEquals(1_700_000_100.0, dto.lastMessageDate)
    }

    @Test
    fun sessionWithoutLastMessageDateFallsBackToPositiveSyncTimestamp() {
        val dto = session(lastMessageDate = null, lastSyncTimestamp = 1_700_000_000L).toDto()
        assertEquals(1_700_000_000.0, dto.lastMessageDate)
    }

    @Test
    fun sessionWithZeroSyncTimestampAndNoLastMessageDateStaysNull() {
        val dto = session(lastMessageDate = null, lastSyncTimestamp = 0L).toDto()
        assertNull(dto.lastMessageDate)
    }

    private fun session(lastMessageDate: Double?, lastSyncTimestamp: Long) = RemoteNodeSessionEntity(
        id = Uuid.normalized("CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC"),
        radioID = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"),
        publicKey = ByteArray(PUBLIC_KEY_BYTES),
        name = "Room",
        roleRawValue = RemoteNodeRole.ROOM_SERVER.raw,
        latitude = 0.0,
        longitude = 0.0,
        isConnected = false,
        permissionLevelRawValue = RoomPermissionLevel.GUEST.raw,
        unreadCount = 0,
        lastSyncTimestamp = lastSyncTimestamp,
        lastMessageDate = lastMessageDate,
    )

    private companion object {
        const val PUBLIC_KEY_BYTES = 32
    }
}
