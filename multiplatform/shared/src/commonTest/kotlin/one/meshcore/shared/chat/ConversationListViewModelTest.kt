package one.meshcore.shared.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The firmware slot the channel-filter tests use (never slot 0, the reserved public channel). */
private const val CHANNEL_SLOT = 3

/**
 * Snapshot recompute, search, and filter behavior for [ConversationListViewModel].
 * Pins sort first, repeaters and blocked contacts drop out, and choosing a filter does not query the radio.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationListViewModelTest {
    @Test
    fun reloadWithoutARadioClearsAPreviouslyLoadedSnapshot() = runTest {
        val backing = FakeConversationListStore().apply {
            contacts = listOf(ChatFixtures.contact(name = "Pat", lastMessageDate = 1_000.0))
        }
        var store: FakeConversationListStore? = backing
        var radio: Uuid? = ChatFixtures.RADIO
        val vm = ConversationListViewModel(
            dependencies = ConversationListViewModel.Dependencies(
                errorMessages = { ChatErrorMessages.developerEnglish() },
                store = { store },
                radioId = { radio },
            ),
        )
        vm.reload()
        assertEquals(listOf("Pat"), vm.state.value.snapshot.all.map { it.displayName })

        store = null
        radio = null
        vm.reload()

        assertTrue(vm.state.value.snapshot.all.isEmpty())
        assertTrue(vm.state.value.contacts.isEmpty())
        assertTrue(vm.state.value.previews.isEmpty())
        assertTrue(vm.state.value.hasLoadedOnce)
    }

    @Test
    fun reloadWhenDisconnectedMarksLoadedSoEmptyStateShowsNotSpinner() = runTest {
        // No live store/radio: the conversation list must render the empty state, so hasLoadedOnce flips
        // even though there is nothing to load. A stuck-false flag is the endless-spinner bug.
        val vm = ConversationListViewModel(
            dependencies = ConversationListViewModel.Dependencies(
                errorMessages = { ChatErrorMessages.developerEnglish() },
                store = { null },
                radioId = { null },
            ),
        )
        vm.reload()
        assertTrue(vm.state.value.hasLoadedOnce)
        assertTrue(vm.state.value.snapshot.all.isEmpty())
    }

    @Test
    fun clearingAnAlreadyEmptyChannelDeleteNoticeDoesNotRadio() = runTest {
        val quiet = quietConversationList(countChannel = true)
        val vm = quiet.vm
        assertNull(vm.state.value.channelDeleteFailure)
        assertEquals(null, vm.state.value.errorMessage)
        assertEquals(ChatFilter.ALL, vm.state.value.filter)
        assertEquals("", vm.state.value.searchText)
        assertFalse(vm.state.value.hasLoadedOnce)
        assertEquals(0, quiet.reads.storeReads)
        assertEquals(0, quiet.reads.radioReads)
        assertEquals(0, quiet.reads.readyReads)
        assertEquals(0, quiet.reads.channelReads)

        vm.clearChannelDeleteFailure()

        assertEquals(0, quiet.reads.storeReads)
        assertEquals(0, quiet.reads.radioReads)
        assertEquals(0, quiet.reads.readyReads)
        assertEquals(0, quiet.reads.channelReads)
        assertNull(vm.state.value.channelDeleteFailure)
        assertEquals(null, vm.state.value.errorMessage)
        assertEquals(ChatFilter.ALL, vm.state.value.filter)
        assertEquals("", vm.state.value.searchText)
        assertFalse(vm.state.value.isSearching)
        assertFalse(vm.state.value.hasLoadedOnce)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun keepingTheAllChatsFilterDoesNotRadio() = runTest {
        val quiet = quietConversationList()
        val vm = quiet.vm
        assertEquals(ChatFilter.ALL, vm.state.value.filter)
        assertEquals("", vm.state.value.searchText)
        assertFalse(vm.state.value.isSearching)
        assertFalse(vm.state.value.hasLoadedOnce)
        assertEquals(0, quiet.reads.storeReads)
        assertEquals(0, quiet.reads.radioReads)
        assertEquals(0, quiet.reads.readyReads)

        vm.setFilter(ChatFilter.ALL)

        assertEquals(0, quiet.reads.storeReads)
        assertEquals(0, quiet.reads.radioReads)
        assertEquals(0, quiet.reads.readyReads)
        assertEquals(ChatFilter.ALL, vm.state.value.filter)
        assertEquals("", vm.state.value.searchText)
        assertFalse(vm.state.value.isSearching)
        assertFalse(vm.state.value.hasLoadedOnce)
        assertFalse(vm.state.value.isLoading)
        assertEquals(null, vm.state.value.errorMessage)
    }

    @Test
    fun keepingAnEmptyChatListSearchDoesNotRadio() = runTest {
        val quiet = quietConversationList()
        val vm = quiet.vm
        assertEquals("", vm.state.value.searchText)
        assertEquals(ChatFilter.ALL, vm.state.value.filter)
        assertFalse(vm.state.value.isSearching)
        assertFalse(vm.state.value.hasLoadedOnce)
        assertEquals(0, quiet.reads.storeReads)
        assertEquals(0, quiet.reads.radioReads)
        assertEquals(0, quiet.reads.readyReads)

        vm.setSearchText("")

        assertEquals(0, quiet.reads.storeReads)
        assertEquals(0, quiet.reads.radioReads)
        assertEquals(0, quiet.reads.readyReads)
        assertEquals("", vm.state.value.searchText)
        assertEquals(ChatFilter.ALL, vm.state.value.filter)
        assertFalse(vm.state.value.isSearching)
        assertFalse(vm.state.value.hasLoadedOnce)
        assertFalse(vm.state.value.isLoading)
        assertEquals(null, vm.state.value.errorMessage)
    }

    @Test
    fun typingAChatListSearchDoesNotRadio() = runTest {
        var storeReads = 0
        val vm = ConversationListViewModel(
            dependencies = ConversationListViewModel.Dependencies(
                errorMessages = { ChatErrorMessages.developerEnglish() },
                store = {
                    storeReads += 1
                    null
                },
                radioId = { null },
            ),
        )
        assertEquals("", vm.state.value.searchText)
        assertEquals(ChatFilter.ALL, vm.state.value.filter)
        assertFalse(vm.state.value.hasLoadedOnce)
        assertEquals(0, storeReads)

        vm.setSearchText("harbor")

        assertEquals("harbor", vm.state.value.searchText)
        assertEquals(ChatFilter.ALL, vm.state.value.filter)
        assertTrue(vm.state.value.isSearching)
        assertFalse(vm.state.value.hasLoadedOnce)
        assertFalse(vm.state.value.isLoading)
        assertEquals(null, vm.state.value.errorMessage)
        assertEquals(0, storeReads)
    }

    @Test
    fun clearingAChatListErrorDoesNotRadio() = runTest {
        val quiet = quietConversationList()
        val vm = quiet.vm
        assertEquals(null, vm.state.value.errorMessage)
        assertEquals(null, vm.state.value.channelDeleteFailure)
        assertEquals("", vm.state.value.searchText)
        assertEquals(ChatFilter.ALL, vm.state.value.filter)
        assertFalse(vm.state.value.hasLoadedOnce)
        assertEquals(0, quiet.reads.storeReads)
        assertEquals(0, quiet.reads.radioReads)
        assertEquals(0, quiet.reads.readyReads)

        vm.clearError()

        assertEquals(0, quiet.reads.storeReads)
        assertEquals(0, quiet.reads.radioReads)
        assertEquals(0, quiet.reads.readyReads)
        assertEquals(null, vm.state.value.errorMessage)
        assertEquals(null, vm.state.value.channelDeleteFailure)
        assertEquals("", vm.state.value.searchText)
        assertEquals(ChatFilter.ALL, vm.state.value.filter)
        assertFalse(vm.state.value.hasLoadedOnce)
        assertFalse(vm.state.value.isLoading)
    }

    // MARK: Snapshot recompute

    @Test
    fun reloadOrdersPinsFirstThenOthers() = runTest {
        val store = FakeConversationListStore().apply {
            contacts = listOf(
                ChatFixtures.contact(name = "NonFav", isPinned = false, lastMessageDate = 2_000.0),
                ChatFixtures.contact(name = "Fav", isPinned = true, lastMessageDate = 1_000.0),
            )
        }
        val vm = conversationListViewModel(store)
        vm.reload()

        assertEquals(listOf("Fav", "NonFav"), vm.state.value.snapshot.all.map { it.displayName })
        assertTrue(vm.state.value.hasLoadedOnce)
    }

    @Test
    fun pinsSortByLastMessageDateDescendingNullsLast() = runTest {
        val store = FakeConversationListStore().apply {
            contacts = listOf(
                ChatFixtures.contact(name = "Older", isPinned = true, lastMessageDate = 1_000.0),
                ChatFixtures.contact(name = "NoDate", isPinned = true, lastMessageDate = null),
                ChatFixtures.contact(name = "Newer", isPinned = true, lastMessageDate = 2_000.0),
            )
        }
        val vm = conversationListViewModel(store)
        vm.reload()

        assertEquals(listOf("Newer", "Older", "NoDate"), vm.state.value.snapshot.pinned.map { it.displayName })
    }

    @Test
    fun recomputeExcludesRepeaterBlockedAndKeepsSecretChannel() = runTest {
        val store = FakeConversationListStore().apply {
            contacts = listOf(
                ChatFixtures.contact(name = "Chat"),
                ChatFixtures.contact(name = "Repeater").copy(typeRawValue = ContactType.REPEATER.raw),
                ChatFixtures.contact(name = "Blocked", isBlocked = true),
            )
            channels = listOf(
                ChatFixtures.channel(name = "Named"),
                ChatFixtures.channel(name = "").copy(secret = byteArrayOf(1)),
                ChatFixtures.channel(name = ""),
            )
        }
        val vm = conversationListViewModel(store)
        vm.reload()

        val names = vm.state.value.snapshot.all.map { it.displayName }.toSet()
        assertTrue("Chat" in names)
        assertFalse("Repeater" in names)
        assertFalse("Blocked" in names)
        assertTrue("Named" in names)
        // The secret-but-nameless channel is kept (its default name is applied at render); the empty one is dropped.
        assertEquals(3, vm.state.value.snapshot.all.size)
    }

    @Test
    fun recomputeKeepsRoomContactsAsDirectsAndRoomSessions() = runTest {
        val roomSession = ChatFixtures.room(name = "Ops Room")
        val store = FakeConversationListStore().apply {
            contacts = listOf(
                ChatFixtures.contact(name = "Alice"),
                ChatFixtures.contact(name = "Ops Room").copy(typeRawValue = ContactType.ROOM.raw),
            )
            rooms = listOf(roomSession)
        }
        val vm = conversationListViewModel(store)
        vm.reload()

        val all = vm.state.value.snapshot.all
        assertEquals(1, all.count { it is Conversation.Direct && it.displayName == "Alice" })
        assertEquals(1, all.count { it is Conversation.Direct && it.displayName == "Ops Room" })
        assertEquals(1, all.count { it is Conversation.Room && it.displayName == "Ops Room" })
    }

    @Test
    fun searchIgnoresFilterAndMatchesDisplayName() = runTest {
        val store = FakeConversationListStore().apply {
            contacts = listOf(ChatFixtures.contact(name = "Alice"), ChatFixtures.contact(name = "Bob"))
        }
        val vm = conversationListViewModel(store)
        vm.reload()
        vm.setFilter(ChatFilter.CHANNELS) // would exclude the direct rows...
        vm.setSearchText("ali") // ...but a non-empty search overrides the filter.

        val results = vm.filteredOthers(vm.state.value, ::unnamedChannelName).map { it.displayName }
        assertEquals(listOf("Alice"), results)
    }

    @Test
    fun choosingChannelsFilterDoesNotQueryTheRadio() = runTest {
        var radioReads = 0
        val contact = ChatFixtures.contact(name = "Alice")
        val channel = ChatFixtures.channel(index = CHANNEL_SLOT, name = "Ops")
        val store = FakeConversationListStore().apply {
            contacts = listOf(contact)
            channels = listOf(channel)
        }
        val vm = conversationListViewModel(
            store,
            hooks = ConversationListTestHooks(
                channelService = {
                    radioReads += 1
                    null
                },
            ),
        )
        vm.reload()
        val readsAfterLoad = radioReads

        vm.setFilter(ChatFilter.CHANNELS)

        assertEquals(readsAfterLoad, radioReads)
        assertEquals(ChatFilter.CHANNELS, vm.state.value.filter)
        assertEquals(listOf("Ops"), vm.filteredOthers(vm.state.value, ::unnamedChannelName).map { it.displayName })
        assertEquals("", vm.state.value.searchText)
    }

    @Test
    fun choosingRoomsFilterDoesNotQueryTheRadio() = runTest {
        var radioReads = 0
        val room = ChatFixtures.room(name = "Hall")
        val channel = ChatFixtures.channel(index = CHANNEL_SLOT, name = "Ops")
        val store = FakeConversationListStore().apply {
            rooms = listOf(room)
            channels = listOf(channel)
        }
        val vm = conversationListViewModel(
            store,
            hooks = ConversationListTestHooks(
                channelService = {
                    radioReads += 1
                    null
                },
            ),
        )
        vm.reload()
        val readsAfterLoad = radioReads

        vm.setFilter(ChatFilter.ROOMS)

        assertEquals(readsAfterLoad, radioReads)
        assertEquals(ChatFilter.ROOMS, vm.state.value.filter)
        assertEquals(listOf("Hall"), vm.filteredOthers(vm.state.value, ::unnamedChannelName).map { it.displayName })
        assertEquals("", vm.state.value.searchText)
    }
}

private fun unnamedChannelName(index: Int): String = "Channel $index"

/** A chat list with no store and no radio, so an edit can prove it did not read them. */
private fun quietConversationList(countChannel: Boolean = false): QuietConversationList {
    val reads = QuietConversationListReads()
    val vm = ConversationListViewModel(
        dependencies = ConversationListViewModel.Dependencies(
            errorMessages = { ChatErrorMessages.developerEnglish() },
            store = {
                reads.storeReads += 1
                null
            },
            radioId = {
                reads.radioReads += 1
                null
            },
            isReady = {
                reads.readyReads += 1
                false
            },
            channelService = if (countChannel) {
                {
                    reads.channelReads += 1
                    null
                }
            } else {
                { null }
            },
        ),
    )
    return QuietConversationList(vm, reads)
}

private class QuietConversationList(val vm: ConversationListViewModel, val reads: QuietConversationListReads)

private class QuietConversationListReads {
    var storeReads = 0
    var radioReads = 0
    var readyReads = 0
    var channelReads = 0
}
