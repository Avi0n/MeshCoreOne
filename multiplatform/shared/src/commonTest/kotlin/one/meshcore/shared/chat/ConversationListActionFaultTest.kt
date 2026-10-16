package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.NotificationLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Optimistic conversation writes. A store failure still rolls the row back, or leaves a direct
 * pinned unchanged. Cancellation and an error propagate and do not roll the row back.
 */
class ConversationListActionFaultTest {
    @Test
    fun aCancelledNotificationLevelKeepsTheOptimisticMute() = runTest {
        val channel = ChatFixtures.channel(name = "General", notificationLevel = NotificationLevel.ALL)
        val store = FakeConversationListStore().apply { channels = listOf(channel) }
        val vm = conversationListViewModel(store)
        vm.reload()
        store.writeFailure = CancellationException("superseded")

        val error = assertFailsWith<CancellationException> {
            vm.setNotificationLevel(Conversation.Channel(channel), NotificationLevel.MUTED)
        }

        assertEquals("superseded", error.message)
        assertEquals(NotificationLevel.MUTED, vm.state.value.channels.single().notificationLevel)
        assertTrue(store.notificationLevelWrites.isEmpty())
    }

    @Test
    fun aNotificationLevelErrorKeepsTheOptimisticMute() = runTest {
        val channel = ChatFixtures.channel(name = "General", notificationLevel = NotificationLevel.ALL)
        val store = FakeConversationListStore().apply { channels = listOf(channel) }
        val vm = conversationListViewModel(store)
        vm.reload()
        store.writeFailure = ActionProbeError()

        assertFailsWith<ActionProbeError> {
            vm.setNotificationLevel(Conversation.Channel(channel), NotificationLevel.MUTED)
        }

        assertEquals(NotificationLevel.MUTED, vm.state.value.channels.single().notificationLevel)
        assertTrue(store.notificationLevelWrites.isEmpty())
    }

    @Test
    fun aCancelledChannelPinnedKeepsTheOptimisticStar() = runTest {
        val channel = ChatFixtures.channel(name = "General", isPinned = false)
        val store = FakeConversationListStore().apply { channels = listOf(channel) }
        val vm = conversationListViewModel(store)
        vm.reload()
        store.writeFailure = CancellationException("superseded")

        val error = assertFailsWith<CancellationException> {
            vm.togglePinned(Conversation.Channel(channel))
        }

        assertEquals("superseded", error.message)
        assertFalse(vm.state.value.channels.single().isPinned)
        assertTrue(store.pinnedWrites.isEmpty())
    }

    @Test
    fun aChannelPinnedErrorKeepsTheOptimisticStar() = runTest {
        val channel = ChatFixtures.channel(name = "General", isPinned = false)
        val store = FakeConversationListStore().apply { channels = listOf(channel) }
        val vm = conversationListViewModel(store)
        vm.reload()
        store.writeFailure = ActionProbeError()

        assertFailsWith<ActionProbeError> {
            vm.togglePinned(Conversation.Channel(channel))
        }

        assertFalse(vm.state.value.channels.single().isPinned)
        assertTrue(store.pinnedWrites.isEmpty())
    }

    @Test
    fun aCancelledDirectPinnedLeavesTheRowAndRollsBackThePin() = runTest {
        val contact = ChatFixtures.contact(name = "Alice", isPinned = false)
        val store = FakeConversationListStore().apply { contacts = listOf(contact) }
        val vm = conversationListViewModel(store)
        vm.reload()
        store.writeFailure = CancellationException("superseded")

        val error = assertFailsWith<CancellationException> {
            vm.togglePinned(Conversation.Direct(contact))
        }

        assertEquals("superseded", error.message)
        assertFalse(vm.state.value.contacts.single().isPinned)
        assertTrue(store.pinnedWrites.isEmpty())
    }

    @Test
    fun aDirectPinnedErrorLeavesTheRowAndRollsBackThePin() = runTest {
        val contact = ChatFixtures.contact(name = "Alice", isPinned = false)
        val store = FakeConversationListStore().apply { contacts = listOf(contact) }
        val vm = conversationListViewModel(store)
        vm.reload()
        store.writeFailure = ActionProbeError()

        assertFailsWith<ActionProbeError> {
            vm.togglePinned(Conversation.Direct(contact))
        }

        assertFalse(vm.state.value.contacts.single().isPinned)
        assertTrue(store.pinnedWrites.isEmpty())
    }
}

private class ActionProbeError : Error("probe")
