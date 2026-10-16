package one.meshcore.shared.notification

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.chat.RoomMessageNotice
import one.meshcore.shared.model.NotificationLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The quick-reply failure warning, the parked-draft key both sides derive, and the room half of the
 * active-conversation contract: suppression while the room is on screen, and clearing its delivered rows
 * on open. Mirrors iOS `NotificationActionHandler.handleQuickReply`'s failure branch,
 * `ChatViewModel.loadDraftIfExists`, and `NotificationService.willPresent`'s room slot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickReplyAndRoomNotificationTest {
    private val dmKey = ConversationKey.direct(NotificationFixtures.CONTACT)

    @Test
    fun `a failed quick reply posts the message-not-sent warning naming the conversation`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)
        coordinator.postQuickReplyFailed(dmKey, "Alice")
        assertEquals(
            listOf(QuickReplyFailedNotification(dmKey, "Alice")),
            poster.quickReplyFailedPosts,
        )
    }

    @Test
    fun `the failure warning still posts inside the sync-suppression window`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)
        coordinator.setSyncNotificationSuppression(true)
        coordinator.postQuickReplyFailed(dmKey, "Alice")
        assertEquals(1, poster.quickReplyFailedPosts.size)
    }

    @Test
    fun `an unauthorized app posts no failure warning`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster, authorized = false)
        coordinator.postQuickReplyFailed(dmKey, "Alice")
        assertTrue(poster.quickReplyFailedPosts.isEmpty())
    }

    @Test
    fun `a draft parked by the reply action is reachable from the open thread's conversation id`() = runTest {
        val coordinator = coordinator(RecordingPoster())
        val conversation = ChatConversationID.dm(NotificationFixtures.RADIO, NotificationFixtures.CONTACT)
        coordinator.saveQuickReplyDraft(dmKey, "on my way")
        assertEquals("on my way", coordinator.consumeQuickReplyDraft(conversation.notificationKey()))
        assertNull(coordinator.consumeQuickReplyDraft(conversation.notificationKey()))
    }

    @Test
    fun `a channel draft is reachable from the open channel thread's conversation id`() = runTest {
        val coordinator = coordinator(RecordingPoster())
        val conversation = ChatConversationID.channel(NotificationFixtures.RADIO, CHANNEL_INDEX)
        coordinator.saveQuickReplyDraft(ConversationKey.channel(NotificationFixtures.RADIO, CHANNEL_INDEX), "roger")
        assertEquals("roger", coordinator.consumeQuickReplyDraft(conversation.notificationKey()))
    }

    @Test
    fun `the room on screen suppresses its own message notification`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)
        coordinator.setActiveRoom(NotificationFixtures.RADIO)
        coordinator.postRoomMessage(
            RoomMessageNotice(
                roomName = "Ops",
                sessionID = NotificationFixtures.RADIO,
                senderName = "Bob",
                text = "status?",
                messageID = NotificationFixtures.CONTACT,
                notificationLevel = NotificationLevel.ALL,
            ),
        )
        assertTrue(poster.roomPosts.isEmpty())
    }

    @Test
    fun `another room still posts while one room is on screen`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)
        coordinator.setActiveRoom(NotificationFixtures.RADIO)
        coordinator.postRoomMessage(
            RoomMessageNotice(
                roomName = "Ops",
                sessionID = NotificationFixtures.CHANNEL,
                senderName = "Bob",
                text = "status?",
                messageID = NotificationFixtures.CONTACT,
                notificationLevel = NotificationLevel.ALL,
            ),
        )
        assertEquals(1, poster.roomPosts.size)
    }

    @Test
    fun `opening a room clears its delivered notifications and refreshes the badge`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster, unread = { UnreadCounts(contacts = 0, channels = 0, rooms = 2) })
        coordinator.setActiveRoom(NotificationFixtures.RADIO)
        advanceUntilIdle()
        assertEquals(listOf(NotificationFixtures.RADIO), poster.clearedRooms)
        assertEquals(listOf(2), poster.badges)
    }

    @Test
    fun `opening a DM clears its delivered notifications and refreshes the badge`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster, unread = { UnreadCounts(contacts = 3, channels = 0, rooms = 0) })
        coordinator.setActiveContact(NotificationFixtures.CONTACT)
        advanceUntilIdle()
        assertEquals(listOf(NotificationFixtures.CONTACT), poster.clearedContacts)
        assertEquals(listOf(3), poster.badges)
    }

    @Test
    fun `opening a channel clears its delivered notifications and refreshes the badge`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster, unread = { UnreadCounts(contacts = 0, channels = 5, rooms = 0) })
        coordinator.setActiveChannel(NotificationFixtures.RADIO, CHANNEL_INDEX)
        advanceUntilIdle()
        assertEquals(listOf(NotificationFixtures.RADIO to CHANNEL_INDEX), poster.clearedChannels)
        assertEquals(listOf(5), poster.badges)
    }

    @Test
    fun `opening a DM releases the room slot so that room notifies again`() = runTest {
        val poster = RecordingPoster()
        val coordinator = coordinator(poster)
        coordinator.setActiveRoom(NotificationFixtures.RADIO)
        coordinator.setActiveContact(NotificationFixtures.CONTACT)
        coordinator.postRoomMessage(
            RoomMessageNotice(
                roomName = "Ops",
                sessionID = NotificationFixtures.RADIO,
                senderName = "Bob",
                text = "status?",
                messageID = NotificationFixtures.CONTACT,
                notificationLevel = NotificationLevel.ALL,
            ),
        )
        assertEquals(1, poster.roomPosts.size)
    }

    private fun TestScope.coordinator(
        poster: RecordingPoster,
        authorized: Boolean = true,
        unread: suspend () -> UnreadCounts = { UnreadCounts.Zero },
    ) = NotificationCoordinator(
        poster = poster,
        activeConversation = ActiveConversationState(),
        scope = this,
        now = { NotificationFixtures.NOW_SECONDS },
        edges = NotificationCoordinatorEdges(
            isAuthorized = { authorized },
            unreadCounts = unread,
        ),
    )

    private companion object {
        const val CHANNEL_INDEX = 2
    }
}
