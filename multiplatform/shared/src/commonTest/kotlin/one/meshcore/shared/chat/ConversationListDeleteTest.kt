package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.channel.ChannelBatchRead
import one.meshcore.shared.channel.ChannelSecret
import one.meshcore.shared.channel.ChannelSession
import one.meshcore.shared.channel.FakeChannelSession
import one.meshcore.shared.channel.InMemoryChannelStore
import one.meshcore.shared.channel.RecordingSlotOccupantChanged
import one.meshcore.shared.channel.secret16
import one.meshcore.shared.channel.testChannelService
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The firmware slot the channel-delete tests operate on (never slot 0, the reserved public channel). */
private const val CHANNEL_SLOT = 3

/** A [ChannelSession] whose every command hangs, so the bounded radio-delete wait is observable. */
private class HangingChannelSession : ChannelSession {
    override suspend fun getChannel(index: Int): ChannelInfo = awaitCancellation()

    override suspend fun getChannels(indices: List<Int>): ChannelBatchRead = awaitCancellation()

    override suspend fun setChannel(index: Int, name: String, secret: ByteArray) = awaitCancellation()
}

/**
 * Optimistic delete, notification-level, and pinned writes on [ConversationListViewModel].
 * A masked stale reload keeps the generation still, and a channel delete clears the radio slot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationListDeleteTest {
    private val messages = ChatErrorMessages.developerEnglish()

    @Test
    fun maskedStaleReloadProducesNoGenerationBump() = runTest {
        val room = ChatFixtures.room(name = "Room")
        val store = FakeConversationListStore().apply { rooms = listOf(room) }
        val vm = conversationListViewModel(store)
        vm.reload()

        vm.removeConversation(Conversation.Room(room))
        val generationAfterRemove = vm.state.value.snapshotGeneration
        assertTrue(vm.state.value.snapshot.all.none { it.id == room.id })

        // Stale reload: the buffer still holds the room, but the mask keeps it hidden.
        vm.reload()
        assertEquals(generationAfterRemove, vm.state.value.snapshotGeneration)
        assertTrue(room.id in vm.state.value.pendingRemovalIds)
    }

    @Test
    fun confirmingReloadSelfHealsWithoutBump() = runTest {
        val room = ChatFixtures.room(name = "Room")
        val store = FakeConversationListStore().apply { rooms = listOf(room) }
        val vm = conversationListViewModel(store)
        vm.reload()

        vm.removeConversation(Conversation.Room(room))
        val generationAfterRemove = vm.state.value.snapshotGeneration

        store.rooms = emptyList()
        vm.reload()
        assertTrue(vm.state.value.pendingRemovalIds.isEmpty())
        assertEquals(generationAfterRemove, vm.state.value.snapshotGeneration)
    }

    @Test
    fun restoreReadmitsHeldRowExactlyOnce() = runTest {
        val room = ChatFixtures.room(name = "Room")
        val store = FakeConversationListStore().apply { rooms = listOf(room) }
        val vm = conversationListViewModel(store)
        vm.reload()

        vm.removeConversation(Conversation.Room(room))
        val generationAfterRemove = vm.state.value.snapshotGeneration

        vm.restoreConversation(Conversation.Room(room))
        assertTrue(vm.state.value.snapshot.all.any { it.id == room.id })
        assertTrue(vm.state.value.pendingRemovalIds.isEmpty())
        assertEquals(generationAfterRemove + 1, vm.state.value.snapshotGeneration)

        vm.restoreConversation(Conversation.Room(room))
        assertEquals(1, vm.state.value.rooms.count { it.id == room.id })
        assertEquals(generationAfterRemove + 1, vm.state.value.snapshotGeneration)
    }

    @Test
    fun isDeletePendingReflectsBothSets() = runTest {
        val contact = ChatFixtures.contact(name = "Alice")
        val store = FakeConversationListStore().apply { contacts = listOf(contact) }
        val vm = conversationListViewModel(store)
        vm.reload()
        assertFalse(vm.state.value.isDeletePending(contact.id))

        vm.removeConversation(Conversation.Direct(contact))
        assertTrue(vm.state.value.isDeletePending(contact.id))
    }

    @Test
    fun deleteDirectIsRefusedWhenNotReady() = runTest {
        val contact = ChatFixtures.contact(name = "Alice", lastMessageDate = 1_000.0)
        val store = FakeConversationListStore().apply { contacts = listOf(contact) }
        val vm = conversationListViewModel(store, hooks = ConversationListTestHooks(isReady = false))
        vm.reload()

        assertFalse(vm.deleteConversation(Conversation.Direct(contact)))
        assertTrue(store.clearedDirects.isEmpty())
        assertTrue(vm.state.value.snapshot.all.any { it.id == contact.id })
        assertEquals(messages.notConnectedToDelete, vm.state.value.errorMessage)
    }

    @Test
    fun deleteDirectHidesOptimisticallyThenConfirms() = runTest {
        val contact = ChatFixtures.contact(name = "Alice", lastMessageDate = 1_000.0)
        val store = FakeConversationListStore().apply { contacts = listOf(contact) }
        val vm = conversationListViewModel(store)
        vm.reload()

        assertTrue(vm.deleteConversation(Conversation.Direct(contact)))
        assertTrue(store.clearedDirects.contains(contact.id))
        assertTrue(vm.state.value.snapshot.all.none { it.id == contact.id })
        assertTrue(vm.state.value.pendingRemovalIds.isEmpty())
    }

    @Test
    fun deleteDirectFailureRestoresRowAndSurfacesError() = runTest {
        val contact = ChatFixtures.contact(name = "Alice", lastMessageDate = 1_000.0)
        val store = FakeConversationListStore().apply {
            contacts = listOf(contact)
            failingIds += contact.id
        }
        val vm = conversationListViewModel(store)
        vm.reload()

        assertFalse(vm.deleteConversation(Conversation.Direct(contact)))
        assertTrue(vm.state.value.snapshot.all.any { it.id == contact.id })
        assertTrue(vm.state.value.pendingRemovalIds.isEmpty())
        assertEquals(messages.deleteConversationFailed, vm.state.value.errorMessage)
    }

    @Test
    fun deleteChannelVacatesTheRadioSlotAndClearsMessagesDraftsAndNotifications() = runTest {
        // A local-only delete would leave the slot configured on the radio, still decrypting the channel,
        // and the next sync would re-create the row: the whole clearChannel cascade has to run.
        val session = FakeChannelSession()
        val channelStore = InMemoryChannelStore()
        val drafts = RecordingSlotOccupantChanged()
        val service = testChannelService(session = session, store = channelStore, slotOccupantChanged = drafts)
        service.setChannelWithSecret(ChatFixtures.RADIO, index = CHANNEL_SLOT, name = "Ops", secret = secret16(1))
        channelStore.seedMessages(channelIndex = CHANNEL_SLOT, count = 4)
        val channel = assertNotNull(service.getChannel(ChatFixtures.RADIO, CHANNEL_SLOT))
        val store = FakeConversationListStore().apply { channels = listOf(channel) }
        val clearedNotifications = mutableListOf<Pair<Int, Uuid>>()
        val vm = conversationListViewModel(
            store,
            hooks = ConversationListTestHooks(
                channelService = { service },
                clearChannelNotifications = { index, radioID -> clearedNotifications += index to radioID },
            ),
        )
        vm.reload()

        vm.deleteConversation(Conversation.Channel(channel))

        val clearWrite = session.setChannelCalls.last()
        assertEquals(CHANNEL_SLOT, clearWrite.index)
        assertEquals("", clearWrite.name)
        assertContentEquals(ByteArray(ChannelSecret.SIZE_BYTES), clearWrite.secret)
        assertNull(service.getChannel(ChatFixtures.RADIO, CHANNEL_SLOT))
        assertEquals(0, channelStore.remainingMessageCount(CHANNEL_SLOT))
        assertEquals(listOf(ChatFixtures.RADIO to setOf(CHANNEL_SLOT)), drafts.received)
        assertEquals(listOf(CHANNEL_SLOT to ChatFixtures.RADIO), clearedNotifications)
        assertTrue(vm.state.value.snapshot.all.none { it.id == channel.id })
        assertFalse(channel.id in vm.state.value.deletingIds)
    }

    @Test
    fun deleteChannelWithoutAChannelServiceKeepsTheRowAndSurfacesTheError() = runTest {
        val channel = ChatFixtures.channel(name = "General", index = CHANNEL_SLOT)
        val store = FakeConversationListStore().apply { channels = listOf(channel) }
        val vm = conversationListViewModel(store, hooks = ConversationListTestHooks(channelService = { null }))
        vm.reload()

        vm.deleteConversation(Conversation.Channel(channel))

        // The row is still there and the reason names the missing service; a generic "delete failed"
        // would leave the user with nothing to act on.
        assertEquals(messages.servicesUnavailable, vm.state.value.errorMessage)
        assertTrue(vm.state.value.snapshot.all.any { it.id == channel.id })
        assertFalse(channel.id in vm.state.value.deletingIds)
    }

    @Test
    fun deleteChannelStopsSpinningWhenTheRadioNeverAnswers() = runTest {
        val service = testChannelService(session = HangingChannelSession())
        val channel = ChatFixtures.channel(name = "General", index = CHANNEL_SLOT)
        val store = FakeConversationListStore().apply { channels = listOf(channel) }
        val vm = conversationListViewModel(store, hooks = ConversationListTestHooks(channelService = { service }))
        vm.reload()

        vm.deleteConversation(Conversation.Channel(channel))

        // The bounded wait expires: the row stays visible with an error rather than spinning forever.
        assertEquals(messages.deleteTimedOut, vm.state.value.channelDeleteFailure?.message)
        assertNull(vm.state.value.errorMessage)
        assertEquals(channel.id, vm.state.value.channelDeleteFailure?.channel?.id)
        assertTrue(vm.state.value.snapshot.all.any { it.id == channel.id })
        assertFalse(channel.id in vm.state.value.deletingIds)
    }

    @Test
    fun dismissingAChannelDeleteFailureDoesNotRadio() = runTest {
        val session = FakeChannelSession()
        var radioWrites = 0
        session.setChannelError = {
            radioWrites += 1
            IllegalStateException("radio offline")
        }
        var notificationClears = 0
        val channel = ChatFixtures.channel(name = "General", index = CHANNEL_SLOT)
        val store = FakeConversationListStore().apply { channels = listOf(channel) }
        val vm = conversationListViewModel(
            store,
            hooks = ConversationListTestHooks(
                channelService = { testChannelService(session = session) },
                clearChannelNotifications = { _, _ -> notificationClears += 1 },
            ),
        )
        vm.reload()

        assertFalse(vm.deleteConversation(Conversation.Channel(channel)))
        assertEquals(1, radioWrites)
        assertEquals(messages.deleteConversationFailed, vm.state.value.channelDeleteFailure?.message)
        assertEquals(channel.id, vm.state.value.channelDeleteFailure?.channel?.id)
        assertNull(vm.state.value.errorMessage)
        val gets = session.getChannelIndices.size
        val batches = session.getChannelsCalls.size
        val recordedWrites = session.setChannelCalls.size

        vm.clearChannelDeleteFailure()

        assertEquals(1, radioWrites)
        assertEquals(gets, session.getChannelIndices.size)
        assertEquals(batches, session.getChannelsCalls.size)
        assertEquals(recordedWrites, session.setChannelCalls.size)
        assertEquals(0, notificationClears)
        assertNull(vm.state.value.channelDeleteFailure)
        assertNull(vm.state.value.errorMessage)
        assertTrue(vm.state.value.snapshot.all.any { it.id == channel.id })
        assertFalse(channel.id in vm.state.value.deletingIds)
    }

    // MARK: Optimistic notification-level / pinned writes

    @Test
    fun setNotificationLevelRollsBackOnFailure() = runTest {
        val channel = ChatFixtures.channel(name = "General", notificationLevel = NotificationLevel.ALL)
        val store = FakeConversationListStore().apply {
            channels = listOf(channel)
            failingIds += channel.id
        }
        val vm = conversationListViewModel(store)
        vm.reload()

        vm.setNotificationLevel(Conversation.Channel(channel), NotificationLevel.MUTED)
        assertEquals(NotificationLevel.ALL, vm.state.value.channels.single().notificationLevel)
    }

    @Test
    fun setNotificationLevelPersistsChannelLevel() = runTest {
        val channel = ChatFixtures.channel(name = "General", notificationLevel = NotificationLevel.ALL)
        val store = FakeConversationListStore().apply { channels = listOf(channel) }
        val vm = conversationListViewModel(store)
        vm.reload()

        vm.setNotificationLevel(Conversation.Channel(channel), NotificationLevel.MENTIONS_ONLY)
        assertEquals(NotificationLevel.MENTIONS_ONLY, vm.state.value.channels.single().notificationLevel)
        assertEquals(channel.id to NotificationLevel.MENTIONS_ONLY, store.notificationLevelWrites.single())
    }

    @Test
    fun setNotificationLevelRefreshesTheUnreadBadge() = runTest {
        val channel = ChatFixtures.channel(name = "General", notificationLevel = NotificationLevel.ALL)
        val store = FakeConversationListStore().apply { channels = listOf(channel) }
        var badgeUpdates = 0
        val vm = conversationListViewModel(store, hooks = ConversationListTestHooks(updateBadge = { badgeUpdates++ }))
        vm.reload()

        vm.setNotificationLevel(Conversation.Channel(channel), NotificationLevel.MUTED)
        assertEquals(1, badgeUpdates)
    }

    @Test
    fun togglePinnedContactPersistsLocally() = runTest {
        val contact = ChatFixtures.contact(name = "Alice", isPinned = false)
        val store = FakeConversationListStore().apply { contacts = listOf(contact) }
        val vm = conversationListViewModel(store)
        vm.reload()

        vm.togglePinned(Conversation.Direct(contact))
        assertTrue(vm.state.value.contacts.single().isPinned)
        assertEquals(contact.id to true, store.pinnedWrites.single())
    }

    @Test
    fun togglePinnedChannelRollsBackOnFailure() = runTest {
        val channel = ChatFixtures.channel(name = "General", isPinned = false)
        val store = FakeConversationListStore().apply {
            channels = listOf(channel)
            failingIds += channel.id
        }
        val vm = conversationListViewModel(store)
        vm.reload()

        vm.togglePinned(Conversation.Channel(channel))
        assertFalse(vm.state.value.channels.single().isPinned)
    }

    @Test
    fun optimisticWritesAreNoOpsWhenNotReady() = runTest {
        val channel = ChatFixtures.channel(name = "General", notificationLevel = NotificationLevel.ALL)
        val store = FakeConversationListStore().apply { channels = listOf(channel) }
        val vm = conversationListViewModel(store, hooks = ConversationListTestHooks(isReady = false))
        vm.reload()

        vm.setNotificationLevel(Conversation.Channel(channel), NotificationLevel.MUTED)
        assertEquals(NotificationLevel.ALL, vm.state.value.channels.single().notificationLevel)
        assertTrue(store.notificationLevelWrites.isEmpty())
    }

    @Test
    fun mutingADisconnectedDirectChatDoesNotRadio() = runTest {
        val contact = ChatFixtures.contact(name = "Ada", isMuted = false)
        val store = FakeConversationListStore().apply { contacts = listOf(contact) }
        var readyReads = 0
        var channelReads = 0
        var badgeUpdates = 0
        val vm = ConversationListViewModel(
            dependencies = ConversationListViewModel.Dependencies(
                errorMessages = { messages },
                store = { store },
                radioId = { ChatFixtures.RADIO },
                isReady = {
                    readyReads += 1
                    false
                },
                channelService = {
                    channelReads += 1
                    error("channel service")
                },
                updateBadge = { badgeUpdates += 1 },
            ),
        )
        vm.reload()
        assertEquals(0, readyReads)
        assertEquals(0, channelReads)
        assertEquals(0, badgeUpdates)
        assertEquals("Ada", vm.state.value.contacts.single().name)
        assertFalse(vm.state.value.contacts.single().isMuted)
        assertTrue(store.notificationLevelWrites.isEmpty())

        vm.toggleMute(Conversation.Direct(contact))

        assertEquals(1, readyReads)
        assertEquals(0, channelReads)
        assertEquals(0, badgeUpdates)
        assertFalse(vm.state.value.contacts.single().isMuted)
        assertTrue(store.notificationLevelWrites.isEmpty())
        assertTrue(store.pinnedWrites.isEmpty())
        assertTrue(store.clearedDirects.isEmpty())
        assertEquals(null, vm.state.value.errorMessage)
    }
}

/** Cancellation and error propagation for conversation delete. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationListDeleteFaultTest {
    @Test
    fun aCancelledDirectDeletePropagatesAndLeavesTheRowHidden() = runTest {
        val contact = ChatFixtures.contact(name = "Alice", lastMessageDate = 1_000.0)
        val store = FakeConversationListStore().apply {
            contacts = listOf(contact)
            clearDirectFailure = CancellationException("superseded")
        }
        val vm = conversationListViewModel(store)
        vm.reload()

        val cancelled = assertFailsWith<CancellationException> {
            vm.deleteConversation(Conversation.Direct(contact))
        }

        assertEquals("superseded", cancelled.message)
        assertNull(vm.state.value.errorMessage)
        assertTrue(vm.state.value.snapshot.all.none { it.id == contact.id })
        assertTrue(contact.id in vm.state.value.pendingRemovalIds)
    }

    @Test
    fun aDirectDeleteErrorPropagatesAndLeavesTheRowHidden() = runTest {
        val contact = ChatFixtures.contact(name = "Alice", lastMessageDate = 1_000.0)
        val store = FakeConversationListStore().apply {
            contacts = listOf(contact)
            clearDirectFailure = ConversationDeleteProbeError()
        }
        val vm = conversationListViewModel(store)
        vm.reload()

        assertFailsWith<ConversationDeleteProbeError> {
            vm.deleteConversation(Conversation.Direct(contact))
        }

        assertNull(vm.state.value.errorMessage)
        assertTrue(vm.state.value.snapshot.all.none { it.id == contact.id })
        assertTrue(contact.id in vm.state.value.pendingRemovalIds)
    }

    @Test
    fun aCancelledChannelDeletePropagatesAndKeepsTheRow() = runTest {
        val channel = ChatFixtures.channel(name = "General", index = CHANNEL_SLOT)
        val store = FakeConversationListStore().apply { channels = listOf(channel) }
        val vm = conversationListViewModel(
            store,
            hooks = ConversationListTestHooks(
                channelService = { testChannelService(session = FakeChannelSession()) },
                clearChannelNotifications = { _, _ -> throw CancellationException("superseded") },
            ),
        )
        vm.reload()

        val cancelled = assertFailsWith<CancellationException> {
            vm.deleteConversation(Conversation.Channel(channel))
        }

        assertEquals("superseded", cancelled.message)
        assertNull(vm.state.value.channelDeleteFailure)
        assertNull(vm.state.value.errorMessage)
        assertTrue(vm.state.value.snapshot.all.any { it.id == channel.id })
        assertFalse(channel.id in vm.state.value.deletingIds)
    }

    @Test
    fun aChannelDeleteErrorPropagatesAndKeepsTheRow() = runTest {
        val channel = ChatFixtures.channel(name = "General", index = CHANNEL_SLOT)
        val store = FakeConversationListStore().apply { channels = listOf(channel) }
        val vm = conversationListViewModel(
            store,
            hooks = ConversationListTestHooks(
                channelService = { testChannelService(session = FakeChannelSession()) },
                clearChannelNotifications = { _, _ -> throw ConversationDeleteProbeError() },
            ),
        )
        vm.reload()

        assertFailsWith<ConversationDeleteProbeError> {
            vm.deleteConversation(Conversation.Channel(channel))
        }

        assertNull(vm.state.value.channelDeleteFailure)
        assertNull(vm.state.value.errorMessage)
        assertTrue(vm.state.value.snapshot.all.any { it.id == channel.id })
        assertFalse(channel.id in vm.state.value.deletingIds)
    }
}

private class ConversationDeleteProbeError : Error("probe")
