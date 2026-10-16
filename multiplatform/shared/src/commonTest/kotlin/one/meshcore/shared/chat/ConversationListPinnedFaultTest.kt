package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pin writes roll back on persistence failure. Cancellation and fatal errors retain their identity.
 */
class ConversationListPinnedFaultTest {
    @Test
    fun aCancelledContactWriteRethrowsTheSameExceptionAndRollsBackThePin() = runTest {
        val cancelled = CancellationException("stopped")
        val contact = ChatFixtures.contact(name = "Alice", isPinned = false)
        val (store, vm) = loadedPins(cancelled) { contacts = listOf(contact) }

        val thrown = assertFailsWith<CancellationException> {
            vm.togglePinned(Conversation.Direct(contact))
        }

        assertSame(cancelled, thrown)
        assertFalse(vm.state.value.contacts.single().isPinned)
        assertTrue(store.pinnedWrites.isEmpty())
    }

    @Test
    fun aContactWriteErrorRethrowsTheSameErrorAndRollsBackThePin() = runTest {
        val probe = PinToggleProbeError()
        val contact = ChatFixtures.contact(name = "Alice", isPinned = false)
        val (store, vm) = loadedPins(probe) { contacts = listOf(contact) }

        val thrown = assertFailsWith<PinToggleProbeError> {
            vm.togglePinned(Conversation.Direct(contact))
        }

        assertSame(probe, thrown)
        assertFalse(vm.state.value.contacts.single().isPinned)
        assertTrue(store.pinnedWrites.isEmpty())
    }

    @Test
    fun aContactStoreExceptionLeavesTheRowUnchangedAndRollsBackThePin() = runTest {
        val contact = ChatFixtures.contact(name = "Alice", isPinned = false)
        val (store, vm) = loadedPins(PinToggleStoreException("datastore down")) {
            contacts = listOf(contact)
        }

        vm.togglePinned(Conversation.Direct(contact))

        assertFalse(vm.state.value.contacts.single().isPinned)
        assertTrue(store.pinnedWrites.isEmpty())
    }

    @Test
    fun aCancelledChannelWriteRethrowsTheSameExceptionAndRollsBackThePin() = runTest {
        val cancelled = CancellationException("stopped")
        val channel = ChatFixtures.channel(name = "General", isPinned = false)
        val (store, vm) = loadedPins(cancelled) { channels = listOf(channel) }

        val thrown = assertFailsWith<CancellationException> {
            vm.togglePinned(Conversation.Channel(channel))
        }

        assertSame(cancelled, thrown)
        assertFalse(vm.state.value.channels.single().isPinned)
        assertTrue(store.pinnedWrites.isEmpty())
    }

    @Test
    fun aChannelWriteErrorRethrowsTheSameErrorAndRollsBackThePin() = runTest {
        val probe = PinToggleProbeError()
        val channel = ChatFixtures.channel(name = "General", isPinned = false)
        val (store, vm) = loadedPins(probe) { channels = listOf(channel) }

        val thrown = assertFailsWith<PinToggleProbeError> {
            vm.togglePinned(Conversation.Channel(channel))
        }

        assertSame(probe, thrown)
        assertFalse(vm.state.value.channels.single().isPinned)
        assertTrue(store.pinnedWrites.isEmpty())
    }

    @Test
    fun aChannelStoreExceptionRollsThePinnedBack() = runTest {
        val channel = ChatFixtures.channel(name = "General", isPinned = false)
        val (store, vm) = loadedPins(PinToggleStoreException("datastore down")) {
            channels = listOf(channel)
        }

        vm.togglePinned(Conversation.Channel(channel))

        assertFalse(vm.state.value.channels.single().isPinned)
        assertTrue(store.pinnedWrites.isEmpty())
    }
}

private class PinToggleProbeError : Error("probe")

private class PinToggleStoreException(message: String) : Exception(message)

private suspend fun loadedPins(
    failure: Throwable,
    prepare: FakeConversationListStore.() -> Unit,
): Pair<FakeConversationListStore, ConversationListViewModel> {
    val store = FakeConversationListStore().apply(prepare)
    val vm = conversationListViewModel(store)
    vm.reload()
    store.writeFailure = failure
    return store to vm
}
