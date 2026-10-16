package one.meshcore.shared.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A coordinator prewarmed while its chat is closed must pick up arrivals before the next open.
 * Mirrors iOS `ChatPrewarmRefresherTests`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatPrewarmRefresherTest {

    @Test
    fun existingCoordinatorIsAPureLookup() = runTest {
        val registry = ChatCoordinatorRegistry(FakeCoordinatorStore(), backgroundScope)
        val id = ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT)
        assertNull(registry.existingCoordinator(id))
        val created = registry.coordinator(id)
        assertTrue(registry.existingCoordinator(id) === created)
    }

    @Test
    fun aDirectArrivalReplacesAWarmCoordinatorTail() = runTest {
        val store = FakeCoordinatorStore()
        val registry = ChatCoordinatorRegistry(store, backgroundScope)
        val id = ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT)
        val old = ChatFixtures.dmMessage(timestamp = 1_000)
        val fresh = ChatFixtures.dmMessage(timestamp = 2_000)
        val coordinator = registry.coordinator(id)
        coordinator.replaceAll(listOf(old))
        store.directPage = listOf(old, fresh)

        val refresher = ChatPrewarmRefresher(
            hooks = hooks(registry, store),
            scope = backgroundScope,
            debounceMs = 0L,
        )
        refresher.noteDirectMessage(ChatFixtures.RADIO, ChatFixtures.CONTACT)
        runCurrent()

        assertEquals(2, coordinator.messages.size)
        assertEquals(fresh.id, coordinator.messages.last().id)
        assertTrue(refresher.inFlightIds().isEmpty())
    }

    @Test
    fun anArrivalSizesThePageFromUnreadCount() = runTest {
        val store = FakeCoordinatorStore()
        val registry = ChatCoordinatorRegistry(store, backgroundScope)
        val id = ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT)
        registry.coordinator(id).replaceAll(listOf(ChatFixtures.dmMessage(timestamp = 1_000)))
        store.directPage = listOf(ChatFixtures.dmMessage(timestamp = 2_000))
        val unread = 80

        val refresher = ChatPrewarmRefresher(
            hooks = hooks(registry, store, options = HookOptions(unreadCount = { unread })),
            scope = backgroundScope,
            debounceMs = 0L,
        )
        refresher.noteDirectMessage(ChatFixtures.RADIO, ChatFixtures.CONTACT)
        runCurrent()

        assertEquals(ChatCoordinator.initialPageSize(unread), store.lastDirectLimit)
    }

    @Test
    fun aMissingConversationDoesNotReplaceTheWarmTail() = runTest {
        val store = FakeCoordinatorStore()
        val registry = ChatCoordinatorRegistry(store, backgroundScope)
        val old = ChatFixtures.dmMessage(timestamp = 1_000)
        val coordinator = registry.coordinator(ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT))
        coordinator.replaceAll(listOf(old))
        store.directPage = listOf(old, ChatFixtures.dmMessage(timestamp = 2_000))

        val refresher = ChatPrewarmRefresher(
            hooks = hooks(registry, store, options = HookOptions(conversation = { null })),
            scope = backgroundScope,
            debounceMs = 0L,
        )
        refresher.noteDirectMessage(ChatFixtures.RADIO, ChatFixtures.CONTACT)
        runCurrent()

        assertEquals(1, coordinator.messages.size)
        assertEquals(old.id, coordinator.messages.single().id)
    }

    @Test
    fun aChannelArrivalWarmsSenderTablesThroughPrime() = runTest {
        IncomingAvatarJPEGStore.withIsolatedStorageSuspending {
            ChannelSenderTableStore.withIsolatedStorageSuspending {
                val store = FakeCoordinatorStore()
                val registry = ChatCoordinatorRegistry(store, backgroundScope)
                val id = ChatConversationID.channel(ChatFixtures.RADIO, CHANNEL_INDEX)
                registry.coordinator(id).replaceAll(
                    listOf(ChatFixtures.message(channelIndex = CHANNEL_INDEX, contactID = null, timestamp = 1_000)),
                )
                store.channelPage = listOf(
                    ChatFixtures.message(channelIndex = CHANNEL_INDEX, contactID = null, timestamp = 2_000),
                )
                val contact = ChatFixtures.contact(name = "Maya", id = ChatFixtures.CONTACT)
                    .copy(avatarImageData = byteArrayOf(1, 2, 3, 4))
                var indexed = 0

                val refresher = ChatPrewarmRefresher(
                    hooks = hooks(
                        registry,
                        store,
                        options = HookOptions(
                            loadContacts = { listOf(contact) },
                            indexPage = { _, page -> indexed = page.size },
                        ),
                    ),
                    scope = backgroundScope,
                    debounceMs = 0L,
                )
                refresher.noteChannelMessage(ChatFixtures.RADIO, CHANNEL_INDEX)
                runCurrent()

                assertEquals(contact.id, ChannelSenderTableStore.tables(ChatFixtures.RADIO).contacts.single().id)
                assertContentEquals(byteArrayOf(1, 2, 3, 4), IncomingAvatarJPEGStore.data(contact.id))
                assertEquals(1, indexed)
            }
        }
    }

    @Test
    fun aChannelArrivalReplacesAWarmCoordinatorTail() = runTest {
        val store = FakeCoordinatorStore()
        val registry = ChatCoordinatorRegistry(store, backgroundScope)
        val id = ChatConversationID.channel(ChatFixtures.RADIO, CHANNEL_INDEX)
        val old = ChatFixtures.message(channelIndex = CHANNEL_INDEX, contactID = null, timestamp = 1_000)
        val fresh = ChatFixtures.message(channelIndex = CHANNEL_INDEX, contactID = null, timestamp = 2_000)
        val coordinator = registry.coordinator(id)
        coordinator.replaceAll(listOf(old))
        store.channelPage = listOf(old, fresh)

        val refresher = ChatPrewarmRefresher(
            hooks = hooks(registry, store),
            scope = backgroundScope,
            debounceMs = 0L,
        )
        refresher.noteChannelMessage(ChatFixtures.RADIO, CHANNEL_INDEX)
        runCurrent()

        assertEquals(2, coordinator.messages.size)
        assertEquals(fresh.id, coordinator.messages.last().id)
    }

    @Test
    fun aSecondArrivalInTheWindowCoalesces() = runTest {
        val store = FakeCoordinatorStore()
        val registry = ChatCoordinatorRegistry(store, backgroundScope)
        val id = ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT)
        registry.coordinator(id).replaceAll(listOf(ChatFixtures.dmMessage(timestamp = 1_000)))

        val refresher = ChatPrewarmRefresher(
            hooks = hooks(registry, store),
            scope = backgroundScope,
            debounceMs = 250L,
        )
        refresher.noteDirectMessage(ChatFixtures.RADIO, ChatFixtures.CONTACT)
        refresher.noteDirectMessage(ChatFixtures.RADIO, ChatFixtures.CONTACT)
        runCurrent()
        assertEquals(setOf(id), refresher.inFlightIds())
        advanceTimeBy(250L)
        runCurrent()
        assertTrue(refresher.inFlightIds().isEmpty())
    }

    @Test
    fun cancelPendingStopsADebouncedRefresh() = runTest {
        val store = FakeCoordinatorStore()
        val registry = ChatCoordinatorRegistry(store, backgroundScope)
        val id = ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT)
        val old = ChatFixtures.dmMessage(timestamp = 1_000)
        val fresh = ChatFixtures.dmMessage(timestamp = 2_000)
        val coordinator = registry.coordinator(id)
        coordinator.replaceAll(listOf(old))
        store.directPage = listOf(old, fresh)
        val refresher = ChatPrewarmRefresher(
            hooks = hooks(registry, store),
            scope = backgroundScope,
            debounceMs = 250L,
        )

        refresher.noteDirectMessage(ChatFixtures.RADIO, ChatFixtures.CONTACT)
        runCurrent()
        assertEquals(setOf(id), refresher.inFlightIds())

        refresher.cancelPending()
        advanceTimeBy(250L)
        runCurrent()

        assertTrue(refresher.inFlightIds().isEmpty())
        assertEquals(listOf(old), coordinator.messages)
    }

    @Test
    fun anOpenConversationIsNotRefreshed() = runTest {
        val store = FakeCoordinatorStore()
        val registry = ChatCoordinatorRegistry(store, backgroundScope)
        val old = ChatFixtures.dmMessage(timestamp = 1_000)
        val coordinator = registry.coordinator(ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT))
        coordinator.replaceAll(listOf(old))
        store.directPage = listOf(old, ChatFixtures.dmMessage(timestamp = 2_000))

        val refresher = ChatPrewarmRefresher(
            hooks = hooks(registry, store, options = HookOptions(isActive = { true })),
            scope = backgroundScope,
            debounceMs = 0L,
        )
        refresher.noteDirectMessage(ChatFixtures.RADIO, ChatFixtures.CONTACT)
        runCurrent()

        assertEquals(1, coordinator.messages.size)
        assertEquals(old.id, coordinator.messages.single().id)
    }

    @Test
    fun anArrivalBakesPathLabelsFromEnvInputs() = runTest {
        val store = FakeCoordinatorStore()
        val registry = ChatCoordinatorRegistry(store, backgroundScope)
        val id = ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT)
        registry.coordinator(id).replaceAll(listOf(ChatFixtures.dmMessage(timestamp = 1_000)))
        store.directPage = listOf(ChatFixtures.dmMessage(timestamp = 2_000))
        val env = EnvInputs(
            currentUserName = "OpsRadio",
            showIncomingSendTime = false,
            incomingPathLabels = MessagePathLabels(direct = "Directo", flood = "Inundación"),
        )

        val refresher = ChatPrewarmRefresher(
            hooks = hooks(registry, store, options = HookOptions(envInputs = { env })),
            scope = backgroundScope,
            debounceMs = 0L,
        )
        refresher.noteDirectMessage(ChatFixtures.RADIO, ChatFixtures.CONTACT)
        runCurrent()

        val item = registry.existingCoordinator(id)!!.renderState.items.single()
        assertEquals("Inundación", item.footer.formattedPath)
    }

    @Test
    fun aColdConversationIsNotCreated() = runTest {
        val store = FakeCoordinatorStore()
        val registry = ChatCoordinatorRegistry(store, backgroundScope)
        store.directPage = listOf(ChatFixtures.dmMessage(timestamp = 1_000))
        val refresher = ChatPrewarmRefresher(
            hooks = hooks(registry, store),
            scope = backgroundScope,
            debounceMs = 0L,
        )
        refresher.noteDirectMessage(ChatFixtures.RADIO, ChatFixtures.CONTACT)
        runCurrent()
        assertNull(registry.existingCoordinator(ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT)))
    }

    private data class HookOptions(
        val isActive: (ChatConversationID) -> Boolean = { false },
        val unreadCount: (ChatConversationID) -> Int = { 0 },
        val conversation: (suspend (ChatConversationID) -> Conversation?)? = null,
        val loadContacts: suspend (Uuid) -> List<ContactDto> = { emptyList() },
        val indexPage: suspend (Conversation, List<MessageDto>) -> Unit = { _, _ -> },
        val envInputs: suspend () -> EnvInputs = { EnvInputs.DEFAULT },
        val unknownLabel: suspend () -> String = { "Unknown" },
    )

    private fun hooks(
        registry: ChatCoordinatorRegistry,
        store: ChatCoordinatorStore,
        options: HookOptions = HookOptions(),
    ) = ChatPrewarmRefresher.Hooks(
        registry = { registry },
        store = { store },
        isConversationActive = options.isActive,
        conversation = options.conversation ?: { id ->
            synthesizedConversation(id, options.unreadCount(id))
        },
        loadContacts = options.loadContacts,
        indexPage = options.indexPage,
        envInputs = options.envInputs,
        unknownLabel = options.unknownLabel,
    )

    private fun synthesizedConversation(id: ChatConversationID, unreadCount: Int): Conversation? =
        when (val key = id.conversation) {
            is ChatConversationID.ConversationKey.Dm ->
                Conversation.Direct(
                    ChatFixtures.contact(name = "Pat", id = key.contactID, unreadCount = unreadCount),
                )
            is ChatConversationID.ConversationKey.Channel ->
                Conversation.Channel(
                    ChatFixtures.channel(name = "ops", index = key.channelIndex, unreadCount = unreadCount),
                )
        }

    private companion object {
        const val CHANNEL_INDEX = 1
    }
}
