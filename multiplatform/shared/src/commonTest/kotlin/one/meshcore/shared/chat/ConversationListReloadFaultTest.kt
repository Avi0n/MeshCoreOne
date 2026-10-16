package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Faults during a conversation-list reload. A contact fetch failure reports the load fault and keeps
 * the rows. A contact preview fault clears that preview, and a reload's failed-send fault clears the
 * badge. Cancellation and an error propagate and leave the loaded list in place.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationListReloadFaultTest {
    @Test
    fun aReloadFaultUsesTheLatestLocalizedCopy() = runTest {
        var messages = ChatErrorMessages.developerEnglish().copy(loadConversationsFailed = "First locale")
        val store = FakeConversationListStore().apply {
            fetchContactsFailure = IllegalStateException("db locked")
        }
        val vm = conversationListViewModel(store, errorMessages = { messages })

        vm.reload()
        assertEquals("First locale", vm.state.value.errorMessage)

        messages = messages.copy(loadConversationsFailed = "Second locale")
        vm.reload()

        assertEquals("Second locale", vm.state.value.errorMessage)
    }

    @Test
    fun aContactFetchExceptionKeepsTheRowsAndReportsTheLoadFault() = runTest {
        val contact = ChatFixtures.contact(name = "Alice", lastMessageDate = 1_000.0)
        val store = FakeConversationListStore().apply { contacts = listOf(contact) }
        val vm = conversationListViewModel(store)
        vm.reload()

        store.contacts = emptyList()
        store.fetchContactsFailure = IllegalStateException("db locked")
        vm.reload()

        assertEquals("Couldn't load conversations.", vm.state.value.errorMessage)
        assertEquals(listOf("Alice"), vm.state.value.snapshot.all.map { it.displayName })
        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.hasLoadedOnce)
    }

    @Test
    fun aCancelledContactFetchPropagatesAndLeavesTheRows() = runTest {
        val contact = ChatFixtures.contact(name = "Alice", lastMessageDate = 1_000.0)
        val store = FakeConversationListStore().apply { contacts = listOf(contact) }
        val vm = conversationListViewModel(store)
        vm.reload()
        val stopped = CancellationException("superseded")
        store.fetchContactsFailure = stopped

        val cancelled = assertFailsWith<CancellationException> { vm.reload() }

        assertSame(stopped, cancelled)
        assertNull(vm.state.value.errorMessage)
        assertEquals(listOf("Alice"), vm.state.value.snapshot.all.map { it.displayName })
        assertTrue(vm.state.value.isLoading)
        assertTrue(vm.state.value.hasLoadedOnce)
    }

    @Test
    fun aContactFetchErrorPropagatesAndLeavesTheRows() = runTest {
        val contact = ChatFixtures.contact(name = "Alice", lastMessageDate = 1_000.0)
        val store = FakeConversationListStore().apply { contacts = listOf(contact) }
        val vm = conversationListViewModel(store)
        vm.reload()
        store.fetchContactsFailure = ConversationReloadProbeError()

        assertFailsWith<ConversationReloadProbeError> { vm.reload() }

        assertNull(vm.state.value.errorMessage)
        assertEquals(listOf("Alice"), vm.state.value.snapshot.all.map { it.displayName })
        assertTrue(vm.state.value.isLoading)
        assertTrue(vm.state.value.hasLoadedOnce)
    }

    @Test
    fun aChannelFetchExceptionKeepsThePriorChannel() = runTest {
        val channel = ChatFixtures.channel(name = "General", lastMessageDate = 1_000.0)
        val store = FakeConversationListStore().apply { channels = listOf(channel) }
        val vm = conversationListViewModel(store)
        vm.reload()

        store.channels = emptyList()
        store.fetchChannelsFailure = IllegalStateException("db locked")
        vm.reload()

        assertNull(vm.state.value.errorMessage)
        assertEquals(listOf("General"), vm.state.value.snapshot.all.map { it.displayName })
        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.hasLoadedOnce)
    }

    @Test
    fun aRoomFetchExceptionKeepsThePriorRoom() = runTest {
        val room = ChatFixtures.room(name = "Ops Room", lastMessageDate = 1_000.0)
        val store = FakeConversationListStore().apply { rooms = listOf(room) }
        val vm = conversationListViewModel(store)
        vm.reload()

        store.rooms = emptyList()
        store.fetchRoomsFailure = IllegalStateException("db locked")
        vm.reload()

        assertNull(vm.state.value.errorMessage)
        assertEquals(listOf("Ops Room"), vm.state.value.snapshot.all.map { it.displayName })
        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.hasLoadedOnce)
    }

    @Test
    fun aCancelledRoomFetchPropagatesAndKeepsTheRoom() = runTest {
        val room = ChatFixtures.room(name = "Ops Room", lastMessageDate = 1_000.0)
        val store = FakeConversationListStore().apply { rooms = listOf(room) }
        val vm = conversationListViewModel(store)
        vm.reload()
        val stopped = CancellationException("superseded")
        store.fetchRoomsFailure = stopped

        val cancelled = assertFailsWith<CancellationException> { vm.reload() }

        assertSame(stopped, cancelled)
        assertNull(vm.state.value.errorMessage)
        assertEquals(listOf("Ops Room"), vm.state.value.snapshot.all.map { it.displayName })
        assertTrue(vm.state.value.isLoading)
        assertTrue(vm.state.value.hasLoadedOnce)
    }

    @Test
    fun aRoomFetchErrorPropagatesAndKeepsTheRoom() = runTest {
        val room = ChatFixtures.room(name = "Ops Room", lastMessageDate = 1_000.0)
        val store = FakeConversationListStore().apply { rooms = listOf(room) }
        val vm = conversationListViewModel(store)
        vm.reload()
        store.fetchRoomsFailure = ConversationReloadProbeError()

        assertFailsWith<ConversationReloadProbeError> { vm.reload() }

        assertNull(vm.state.value.errorMessage)
        assertEquals(listOf("Ops Room"), vm.state.value.snapshot.all.map { it.displayName })
        assertTrue(vm.state.value.isLoading)
        assertTrue(vm.state.value.hasLoadedOnce)
    }

    @Test
    fun aFailedSendFetchExceptionClearsTheBadgeOnReload() = runTest {
        val contact = ChatFixtures.contact(name = "Pat", lastMessageDate = 1_000.0)
        val store = FakeConversationListStore().apply {
            contacts = listOf(contact)
            failedSendKeys = FailedSendConversationKeys(contactIDs = setOf(contact.id))
        }
        val vm = conversationListViewModel(store)
        vm.reload()
        assertTrue(vm.conversationHasFailedSend(contact.id))

        store.fetchFailedSendFailure = IllegalStateException("db locked")
        vm.reload()

        assertFalse(vm.conversationHasFailedSend(contact.id))
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun aCancelledFailedSendFetchOnReloadKeepsTheBadge() = runTest {
        val contact = ChatFixtures.contact(name = "Pat", lastMessageDate = 1_000.0)
        val store = FakeConversationListStore().apply {
            contacts = listOf(contact)
            failedSendKeys = FailedSendConversationKeys(contactIDs = setOf(contact.id))
        }
        val vm = conversationListViewModel(store)
        vm.reload()
        val stopped = CancellationException("superseded")
        store.fetchFailedSendFailure = stopped

        val cancelled = assertFailsWith<CancellationException> { vm.reload() }

        assertSame(stopped, cancelled)
        assertTrue(vm.conversationHasFailedSend(contact.id))
        assertNull(vm.state.value.errorMessage)
        assertTrue(vm.state.value.isLoading)
    }

    @Test
    fun aFailedSendFetchErrorOnReloadKeepsTheBadge() = runTest {
        val contact = ChatFixtures.contact(name = "Pat", lastMessageDate = 1_000.0)
        val store = FakeConversationListStore().apply {
            contacts = listOf(contact)
            failedSendKeys = FailedSendConversationKeys(contactIDs = setOf(contact.id))
        }
        val vm = conversationListViewModel(store)
        vm.reload()
        store.fetchFailedSendFailure = ConversationReloadProbeError()

        assertFailsWith<ConversationReloadProbeError> { vm.reload() }

        assertTrue(vm.conversationHasFailedSend(contact.id))
        assertNull(vm.state.value.errorMessage)
        assertTrue(vm.state.value.isLoading)
    }

    @Test
    fun aCancelledChannelFetchPropagatesAndKeepsTheChannel() = runTest {
        val channel = ChatFixtures.channel(name = "General", lastMessageDate = 1_000.0)
        val store = FakeConversationListStore().apply { channels = listOf(channel) }
        val vm = conversationListViewModel(store)
        vm.reload()
        val stopped = CancellationException("superseded")
        store.fetchChannelsFailure = stopped

        val cancelled = assertFailsWith<CancellationException> { vm.reload() }

        assertSame(stopped, cancelled)
        assertNull(vm.state.value.errorMessage)
        assertEquals(listOf("General"), vm.state.value.snapshot.all.map { it.displayName })
        assertTrue(vm.state.value.isLoading)
    }

    @Test
    fun aChannelFetchErrorPropagatesAndKeepsTheChannel() = runTest {
        val channel = ChatFixtures.channel(name = "General", lastMessageDate = 1_000.0)
        val store = FakeConversationListStore().apply { channels = listOf(channel) }
        val vm = conversationListViewModel(store)
        vm.reload()
        store.fetchChannelsFailure = ConversationReloadProbeError()

        assertFailsWith<ConversationReloadProbeError> { vm.reload() }

        assertNull(vm.state.value.errorMessage)
        assertEquals(listOf("General"), vm.state.value.snapshot.all.map { it.displayName })
        assertTrue(vm.state.value.isLoading)
    }

    @Test
    fun aFailedSendRefreshExceptionKeepsTheBadge() = runTest {
        val contact = ChatFixtures.contact(name = "Pat", lastMessageDate = 1_000.0)
        val store = failingSendStore(contact)
        val vm = conversationListViewModel(store)
        vm.reload()
        store.fetchFailedSendFailure = IllegalStateException("db locked")

        vm.refreshFailedSendIndicators()

        assertTrue(vm.conversationHasFailedSend(contact.id))
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun aCancelledFailedSendRefreshPropagatesAndKeepsTheBadge() = runTest {
        val contact = ChatFixtures.contact(name = "Pat", lastMessageDate = 1_000.0)
        val store = failingSendStore(contact)
        val vm = conversationListViewModel(store)
        vm.reload()
        val stopped = CancellationException("superseded")
        store.fetchFailedSendFailure = stopped

        val cancelled = assertFailsWith<CancellationException> { vm.refreshFailedSendIndicators() }

        assertSame(stopped, cancelled)
        assertTrue(vm.conversationHasFailedSend(contact.id))
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun aFailedSendRefreshErrorPropagatesAndKeepsTheBadge() = runTest {
        val contact = ChatFixtures.contact(name = "Pat", lastMessageDate = 1_000.0)
        val store = failingSendStore(contact)
        val vm = conversationListViewModel(store)
        vm.reload()
        store.fetchFailedSendFailure = ConversationReloadProbeError()

        assertFailsWith<ConversationReloadProbeError> { vm.refreshFailedSendIndicators() }

        assertTrue(vm.conversationHasFailedSend(contact.id))
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun aContactPreviewExceptionClearsThatPreview() = runTest {
        val contact = ChatFixtures.contact(name = "Alice", lastMessageDate = 1_000.0)
        val store = previewStore(contact)
        val vm = conversationListViewModel(store)
        vm.reload()
        assertEquals("hi", vm.previewText(contact.id))

        store.fetchContactPreviewFailure = IllegalStateException("db locked")
        vm.reload()

        assertNull(vm.previewText(contact.id))
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun aCancelledContactPreviewPropagatesAndKeepsThePreview() = runTest {
        val contact = ChatFixtures.contact(name = "Alice", lastMessageDate = 1_000.0)
        val store = previewStore(contact)
        val vm = conversationListViewModel(store)
        vm.reload()
        val stopped = CancellationException("superseded")
        store.fetchContactPreviewFailure = stopped

        val cancelled = assertFailsWith<CancellationException> { vm.reload() }

        assertSame(stopped, cancelled)
        assertEquals("hi", vm.previewText(contact.id))
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun aContactPreviewErrorPropagatesAndKeepsThePreview() = runTest {
        val contact = ChatFixtures.contact(name = "Alice", lastMessageDate = 1_000.0)
        val store = previewStore(contact)
        val vm = conversationListViewModel(store)
        vm.reload()
        store.fetchContactPreviewFailure = ConversationReloadProbeError()

        assertFailsWith<ConversationReloadProbeError> { vm.reload() }

        assertEquals("hi", vm.previewText(contact.id))
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun aChannelPreviewExceptionKeepsThePriorPreview() = runTest {
        val channel = ChatFixtures.channel(name = "General", lastMessageDate = 1_000.0)
        val store = channelPreviewStore(channel)
        val vm = conversationListViewModel(store)
        vm.reload()
        assertEquals("ops", vm.previewText(channel.id))

        store.fetchChannelPreviewFailure = IllegalStateException("db locked")
        vm.reload()

        assertEquals("ops", vm.previewText(channel.id))
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun aCancelledChannelPreviewPropagatesAndKeepsThePreview() = runTest {
        val channel = ChatFixtures.channel(name = "General", lastMessageDate = 1_000.0)
        val store = channelPreviewStore(channel)
        val vm = conversationListViewModel(store)
        vm.reload()
        val stopped = CancellationException("superseded")
        store.fetchChannelPreviewFailure = stopped

        val cancelled = assertFailsWith<CancellationException> { vm.reload() }

        assertSame(stopped, cancelled)
        assertEquals("ops", vm.previewText(channel.id))
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun aChannelPreviewErrorPropagatesAndKeepsThePreview() = runTest {
        val channel = ChatFixtures.channel(name = "General", lastMessageDate = 1_000.0)
        val store = channelPreviewStore(channel)
        val vm = conversationListViewModel(store)
        vm.reload()
        store.fetchChannelPreviewFailure = ConversationReloadProbeError()

        assertFailsWith<ConversationReloadProbeError> { vm.reload() }

        assertEquals("ops", vm.previewText(channel.id))
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isLoading)
    }
}

private class ConversationReloadProbeError : Error("probe")

private fun failingSendStore(contact: ContactDto): FakeConversationListStore = FakeConversationListStore().apply {
    contacts = listOf(contact)
    failedSendKeys = FailedSendConversationKeys(contactIDs = setOf(contact.id))
}

private fun previewStore(contact: ContactDto): FakeConversationListStore = FakeConversationListStore().apply {
    contacts = listOf(contact)
    contactPreviews = mapOf(contact.id to listOf(ChatFixtures.message(text = "hi")))
}

private fun channelPreviewStore(channel: ChannelDto): FakeConversationListStore = FakeConversationListStore().apply {
    channels = listOf(channel)
    channelPreviews = mapOf(channel.id to listOf(ChatFixtures.message(text = "ops")))
}
