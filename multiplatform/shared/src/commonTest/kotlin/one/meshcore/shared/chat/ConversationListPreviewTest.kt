package one.meshcore.shared.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.reactions.ReactionParser
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.room.RoomServerEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Last-message preview and failed-send badge behavior for [ConversationListViewModel].
 * A sent outgoing reaction stays out of the preview unless that send failed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationListPreviewTest {
    private val messages = ChatErrorMessages.developerEnglish()

    @Test
    fun previewSkipsOutgoingReactionUnlessFailed() = runTest {
        val contact = ChatFixtures.contact(name = "Alice", lastMessageDate = 1_000.0)
        val incoming = ChatFixtures.message(
            text = "hi",
            direction = MessageDirection.INCOMING,
            status = MessageStatus.DELIVERED,
        )
        val reaction = ChatFixtures.message(
            text = "R:like",
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.SENT,
        )
        val store = FakeConversationListStore().apply {
            contacts = listOf(contact)
            contactPreviews = mapOf(contact.id to listOf(incoming, reaction))
        }
        val vm =
            conversationListViewModel(
                store,
                hooks = ConversationListTestHooks(isReaction = {
                    it.text.startsWith("R:")
                }),
            )
        vm.reload()

        // The trailing outgoing reaction is skipped, so the preview is the earlier real message.
        assertEquals("hi", vm.previewText(contact.id))
    }

    @Test
    fun previewKeepsFailedOutgoingReaction() = runTest {
        val contact = ChatFixtures.contact(name = "Alice", lastMessageDate = 1_000.0)
        val failedReaction = ChatFixtures.message(
            text = "R:like",
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.FAILED,
        )
        val store = FakeConversationListStore().apply {
            contacts = listOf(contact)
            contactPreviews = mapOf(contact.id to listOf(failedReaction))
        }
        val vm =
            conversationListViewModel(
                store,
                hooks = ConversationListTestHooks(isReaction = {
                    it.text.startsWith("R:")
                }),
            )
        vm.reload()

        assertEquals("R:like", vm.previewText(contact.id))
    }

    @Test
    fun onlyRoomServerSessionsBecomeConversationRows() = runTest {
        // Every repeater admin login opens a session; listing one would add a chat row for a node that
        // carries no room messages.
        val store = FakeConversationListStore().apply {
            rooms = listOf(
                ChatFixtures.room(name = "Ops Room", role = RemoteNodeRole.ROOM_SERVER),
                ChatFixtures.room(name = "Hilltop Repeater", role = RemoteNodeRole.REPEATER),
            )
        }
        val vm = conversationListViewModel(store)
        vm.reload()

        assertEquals(listOf("Ops Room"), vm.state.value.snapshot.all.map { it.displayName })
    }

    @Test
    fun previewSkipsSentOutgoingReactionWithoutAnInjectedDetector() = runTest {
        // Production wires no detector, so the default must be the real one: otherwise the row preview
        // shows the reaction's raw wire text where the last real message belongs.
        val contact = ChatFixtures.contact(name = "Alice", lastMessageDate = 1_000.0)
        val incoming = ChatFixtures.message(
            text = "hi",
            direction = MessageDirection.INCOMING,
            status = MessageStatus.DELIVERED,
        )
        val wireText = ReactionParser.buildDmReactionText("👍", incoming.text, incoming.timestamp)
        val reaction = ChatFixtures.message(
            text = wireText,
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.SENT,
        )
        val store = FakeConversationListStore().apply {
            contacts = listOf(contact)
            contactPreviews = mapOf(contact.id to listOf(incoming, reaction))
        }
        val vm = ConversationListViewModel(
            dependencies = ConversationListViewModel.Dependencies(
                errorMessages = { ChatErrorMessages.developerEnglish() },
                store = { store },
                radioId = { ChatFixtures.RADIO },
                isReady = { true },
            ),
        )
        vm.reload()

        assertEquals("hi", vm.previewText(contact.id))
    }

    @Test
    fun previewEvictsWhenNoMessagesRemain() = runTest {
        val contact = ChatFixtures.contact(name = "Alice", lastMessageDate = 1_000.0)
        val store = FakeConversationListStore().apply {
            contacts = listOf(contact)
            contactPreviews = mapOf(contact.id to emptyList())
        }
        val vm = conversationListViewModel(store)
        vm.reload()

        assertNull(vm.previewText(contact.id))
    }

    @Test
    fun reloadLoadsFailedSendConversationIds() = runTest {
        val contact = ChatFixtures.contact(name = "Pat", lastMessageDate = 1_000.0)
        val other = ChatFixtures.contact(name = "Other", lastMessageDate = 900.0)
        val store = FakeConversationListStore().apply {
            contacts = listOf(contact, other)
            failedSendKeys = FailedSendConversationKeys(contactIDs = setOf(contact.id))
        }
        val vm = conversationListViewModel(store)
        vm.reload()
        assertTrue(vm.conversationHasFailedSend(contact.id))
        assertFalse(vm.conversationHasFailedSend(other.id))
        assertEquals(setOf(contact.id), vm.state.value.failedSendConversationIDs)
    }

    @Test
    fun aStatusResolvedAckDropsTheFailedSendBadge() = runTest {
        val contact = ChatFixtures.contact(name = "Pat", lastMessageDate = 1_000.0)
        val store = FakeConversationListStore().apply {
            contacts = listOf(contact)
            failedSendKeys = FailedSendConversationKeys(contactIDs = setOf(contact.id))
        }
        val vm = conversationListViewModel(store)
        vm.reload()
        assertTrue(vm.conversationHasFailedSend(contact.id))
        assertTrue(
            vm.shouldRefreshFailedSendIndicators(
                MessageStatusEvent.StatusResolved(contact.id, MessageStatus.SENT),
            ),
        )

        store.failedSendKeys = FailedSendConversationKeys()
        vm.refreshFailedSendIndicators()
        assertFalse(vm.conversationHasFailedSend(contact.id))
        assertFalse(
            vm.shouldRefreshFailedSendIndicators(
                MessageStatusEvent.StatusResolved(contact.id, MessageStatus.SENT),
            ),
        )
    }

    @Test
    fun aFailAlwaysRefreshesFailedSendBadges() {
        val event = MessageStatusEvent.Failed(ChatFixtures.contact(name = "Pat").id)
        val vm = ConversationListViewModel(
            dependencies = ConversationListViewModel.Dependencies(
                errorMessages = { messages },
                store = { FakeConversationListStore() },
                radioId = { ChatFixtures.RADIO },
            ),
        )
        assertTrue(vm.shouldRefreshFailedSendIndicators(event))
        assertTrue(event.shouldRefreshFailedSendIndicators(hasFailedSendBadges = false))
    }

    @Test
    fun aRoomStatusUpdateRefreshesOnlyWhenABadgeIsShowing() = runTest {
        val sessionId = ChatFixtures.contact(name = "Room").id
        val store = FakeConversationListStore().apply {
            failedSendKeys = FailedSendConversationKeys(roomSessionIDs = setOf(sessionId))
        }
        val vm = conversationListViewModel(store)
        vm.reload()
        val delivered = RoomServerEvent.StatusUpdated(sessionId, MessageStatus.DELIVERED)
        assertTrue(vm.shouldRefreshFailedSendIndicators(delivered))
        store.failedSendKeys = FailedSendConversationKeys()
        vm.refreshFailedSendIndicators()
        assertFalse(vm.shouldRefreshFailedSendIndicators(delivered))
        assertTrue(
            vm.shouldRefreshFailedSendIndicators(
                RoomServerEvent.StatusUpdated(sessionId, MessageStatus.FAILED),
            ),
        )
    }
}
