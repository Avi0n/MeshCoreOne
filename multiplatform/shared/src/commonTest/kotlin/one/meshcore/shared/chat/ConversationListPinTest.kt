package one.meshcore.shared.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationListPinTest {
    @Test
    fun pinsAllConversationKindsOfflineWithoutChangingRadioFavorites() = runTest {
        val contact = ChatFixtures.contact(name = "Alice", isFavorite = true, lastMessageDate = 1.0)
        val channel = ChatFixtures.channel(name = "Ops")
        val room = ChatFixtures.room(name = "Room")
        val store = FakeConversationListStore().apply {
            contacts = listOf(contact)
            channels = listOf(channel)
            rooms = listOf(room)
        }
        val vm = conversationListViewModel(store, ConversationListTestHooks(isReady = false))
        vm.reload()
        assertTrue(vm.state.value.snapshot.pinned.isEmpty())
        vm.state.value.snapshot.all.forEach { vm.togglePinned(it) }
        assertEquals(3, vm.state.value.snapshot.pinned.size)
        assertTrue(vm.state.value.contacts.single().isFavorite)
        assertEquals(listOf(contact.id to true, channel.id to true, room.id to true), store.pinnedWrites)
    }

    @Test
    fun rapidTogglesReadTheLivePinAfterThePreviousWrite() = runTest {
        val contact = ChatFixtures.contact(name = "Alice", lastMessageDate = 1.0)
        val store = FakeConversationListStore().apply { contacts = listOf(contact) }
        val vm = conversationListViewModel(store)
        vm.reload()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        store.beforePinWrite = {
            calls += 1
            if (calls == 1) release.await()
        }
        val stale = Conversation.Direct(contact)
        val first = launch { vm.togglePinned(stale) }
        runCurrent()
        val second = launch { vm.togglePinned(stale) }
        runCurrent()
        assertEquals(1, calls)
        release.complete(Unit)
        first.join()
        second.join()
        assertFalse(vm.state.value.contacts.single().isPinned)
        assertEquals(listOf(contact.id to true, contact.id to false), store.pinnedWrites)
    }

    @Test
    fun unavailableStoreReportsThePinFailure() = runTest {
        val contact = ChatFixtures.contact(name = "Alice")
        val vm = ConversationListViewModel(
            conversationListViewModel(FakeConversationListStore()).dependencies.copy(store = { null }),
        )
        vm.togglePinned(Conversation.Direct(contact))
        assertEquals(ChatErrorMessages.developerEnglish().pinSaveFailed, vm.state.value.errorMessage)
    }
}
