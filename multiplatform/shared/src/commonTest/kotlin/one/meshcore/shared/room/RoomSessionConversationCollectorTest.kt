package one.meshcore.shared.room

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.RoomMessageDto
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.RemoteNodeEvent
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class RoomSessionConversationCollectorTest {
    private val sessionId = Uuid.normalized("11111111-1111-1111-1111-111111111111")

    @Test
    fun aSessionDropReloadsTheConversationList() = runTest(UnconfinedTestDispatcher()) {
        val remote = MutableSharedFlow<RemoteNodeEvent>()
        val rooms = MutableSharedFlow<RoomServerEvent>()
        var reloads = 0
        val collector = RoomSessionConversationCollector(
            remoteNodeEvents = remote,
            roomServerEvents = rooms,
            scope = this,
            onConversationsChanged = { reloads++ },
        )
        collector.start()
        remote.emit(RemoteNodeEvent.SessionStateChanged(sessionId, isConnected = false))
        assertEquals(1, reloads)
        rooms.emit(RoomServerEvent.ConnectionRecovered(sessionId))
        assertEquals(2, reloads)
        rooms.emit(
            RoomServerEvent.StatusUpdated(
                Uuid.normalized("22222222-2222-2222-2222-222222222222"),
                MessageStatus.FAILED,
            ),
        )
        assertEquals(3, reloads)
        rooms.emit(
            RoomServerEvent.StatusUpdated(
                Uuid.normalized("22222222-2222-2222-2222-222222222222"),
                MessageStatus.SENT,
            ),
        )
        assertEquals(3, reloads)
        rooms.emit(RoomServerEvent.MessageReceived(roomMessage(sessionId), sessionId))
        assertEquals(4, reloads)
        collector.stop()
    }

    private fun roomMessage(sessionId: Uuid) = RoomMessageDto(
        id = Uuid.normalized("33333333-3333-3333-3333-333333333333"),
        sessionID = sessionId,
        authorKeyPrefix = ByteArray(4),
        text = "hi",
        timestamp = 1L,
        createdAt = 1.0,
        isFromSelf = false,
        deduplicationKey = "k",
        statusRawValue = MessageStatus.DELIVERED.raw,
        retryAttempt = 0,
        maxRetryAttempts = 0,
    )
}
