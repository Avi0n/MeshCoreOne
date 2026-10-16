package one.meshcore.shared.room.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.chat.RoomMessageNotice
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.notification.ActiveConversationState
import one.meshcore.shared.notification.NotificationCoordinator
import one.meshcore.shared.notification.NotificationCoordinatorEdges
import one.meshcore.shared.notification.RecordingPoster
import one.meshcore.shared.notification.UnreadCounts
import one.meshcore.shared.notification.clearActiveConversation
import one.meshcore.shared.notification.setActiveRoom
import one.meshcore.shared.room.RoomNotificationSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The room screen's half of the active-conversation contract: opening a room claims the slot (so its own
 * arriving messages raise no banner) and clears what its tray already delivered; leaving hands the room
 * back. Mirrors iOS `RoomConversationView`'s `setActiveConversation(roomSessionID:)` / `onDisappear` pair.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomConversationNotificationTest {
    @Test
    fun `opening a room claims the slot and clears its delivered notifications`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)
        viewModel(CoordinatorRoomSink(this, coordinator))
        advanceUntilIdle()

        assertEquals(listOf(SESSION), poster.clearedRooms)
        assertEquals(listOf(UNREAD_ROOMS), poster.badges)
    }

    @Test
    fun `a message in the room on screen raises no notification`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)
        viewModel(CoordinatorRoomSink(this, coordinator))
        advanceUntilIdle()

        coordinator.postRoomMessage(
            RoomMessageNotice(
                roomName = "Ops",
                sessionID = SESSION,
                senderName = "Bob",
                text = "status?",
                messageID = MESSAGE,
                notificationLevel = NotificationLevel.ALL,
            ),
        )

        assertTrue(poster.roomPosts.isEmpty())
    }

    @Test
    fun `leaving the room hands its notifications back`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)
        val vm = viewModel(CoordinatorRoomSink(this, coordinator))
        advanceUntilIdle()

        vm.dispose()
        advanceUntilIdle()
        coordinator.postRoomMessage(
            RoomMessageNotice(
                roomName = "Ops",
                sessionID = SESSION,
                senderName = "Bob",
                text = "status?",
                messageID = MESSAGE,
                notificationLevel = NotificationLevel.ALL,
            ),
        )

        assertEquals(1, poster.roomPosts.size)
    }

    @Test
    fun `the screen names its own session on both hooks`() = runTest {
        val sink = RecordingRoomSink()
        val vm = viewModel(sink)
        advanceUntilIdle()
        vm.dispose()

        assertEquals(listOf(SESSION), sink.claimed)
        assertEquals(listOf(SESSION), sink.released)
    }

    private fun TestScope.viewModel(notifications: RoomNotificationSink) = RoomConversationViewModel(
        scope = this,
        session = session(),
        dependencies = RoomConversationViewModel.Dependencies(
            roomServerService = { null },
            notifications = notifications,
            loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
            sendFailedMessage = { ROOM_TEST_SEND_ERROR },
        ),
    )

    private fun TestScope.coordinator(poster: RecordingPoster) = NotificationCoordinator(
        poster = poster,
        activeConversation = ActiveConversationState(),
        scope = this,
        now = { 0.0 },
        edges = NotificationCoordinatorEdges(
            unreadCounts = { UnreadCounts(contacts = 0, channels = 0, rooms = UNREAD_ROOMS) },
        ),
    )

    private fun session() = RemoteNodeSessionDto(
        id = SESSION,
        radioID = RADIO,
        publicKey = ByteArray(PUBLIC_KEY_BYTES) { 3 },
        name = "Ops",
        role = RemoteNodeRole.ROOM_SERVER,
        latitude = 0.0,
        longitude = 0.0,
        isConnected = false,
        permissionLevel = RoomPermissionLevel.GUEST,
        unreadCount = 0,
        notificationLevel = NotificationLevel.ALL,
        isPinned = false,
        neighborCount = 0,
        lastSyncTimestamp = 0,
    )

    /** The adapter shape the Android container installs, over a real coordinator. */
    private class CoordinatorRoomSink(
        private val scope: CoroutineScope,
        private val coordinator: NotificationCoordinator,
    ) : RoomNotificationSink {
        override suspend fun setActiveRoom(sessionID: Uuid) = coordinator.setActiveRoom(sessionID)

        override fun clearActiveRoomAsync(sessionID: Uuid) {
            scope.launch { coordinator.clearActiveConversation() }
        }
    }

    private class RecordingRoomSink : RoomNotificationSink {
        val claimed = mutableListOf<Uuid>()
        val released = mutableListOf<Uuid>()

        override suspend fun setActiveRoom(sessionID: Uuid) {
            claimed += sessionID
        }

        override fun clearActiveRoomAsync(sessionID: Uuid) {
            released += sessionID
        }
    }

    private companion object {
        val SESSION: Uuid = Uuid.normalized("D0000000-0000-0000-0000-000000000001")
        val RADIO: Uuid = Uuid.normalized("D0000000-0000-0000-0000-000000000002")
        val MESSAGE: Uuid = Uuid.normalized("D0000000-0000-0000-0000-000000000003")
        const val UNREAD_ROOMS = 4
        const val PUBLIC_KEY_BYTES = 32
    }
}
