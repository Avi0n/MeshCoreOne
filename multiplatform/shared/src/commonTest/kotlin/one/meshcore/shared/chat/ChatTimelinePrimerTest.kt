package one.meshcore.shared.chat

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.reactions.ReactionParser
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatTimelinePrimerTest {
    @Test
    fun primingADirectChatLoadsPageZeroIntoANewCoordinator() = runTest {
        val store = FakeCoordinatorStore()
        val page = listOf(ChatFixtures.dmMessage(timestamp = 2_000))
        store.directPage = page
        val registry = ChatCoordinatorRegistry(store, backgroundScope)
        val conversation = Conversation.Direct(ChatFixtures.contact(name = "Pat", id = ChatFixtures.CONTACT))

        ChatTimelinePrimer(hooks(registry, store)).prime(conversation)

        val coordinator = registry.existingCoordinator(
            ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT),
        )
        assertTrue(coordinator != null)
        assertEquals(ChatRenderState.LoadPhase.LOADED, coordinator!!.renderState.phase)
        assertEquals(page.map { it.id }, coordinator.messages.map { it.id })
        assertEquals(page.size, coordinator.renderState.totalFetchedCount)
        assertFalse(coordinator.renderState.hasMoreMessages)
        assertFalse(coordinator.renderState.isLoadingOlder)
        assertEquals(page.map { it.id }, coordinator.renderState.items.map { it.id })
    }

    @Test
    fun primingHidesASentOutgoingReactionFromBakedItems() = runTest {
        val visible = ChatFixtures.dmMessage(timestamp = 1_000)
        val sentReaction = ChatFixtures.message(
            text = ReactionParser.buildDmReactionText("👍", "meet", 1_700_000_000L),
            timestamp = 2_000,
            createdAt = 2_000.0,
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.SENT,
            contactID = ChatFixtures.CONTACT,
        )
        val store = FakeCoordinatorStore()
        store.directPage = listOf(visible, sentReaction)
        val registry = ChatCoordinatorRegistry(store, backgroundScope)
        val conversation = Conversation.Direct(ChatFixtures.contact(name = "Pat", id = ChatFixtures.CONTACT))

        ChatTimelinePrimer(hooks(registry, store)).prime(conversation)

        val coordinator = registry.existingCoordinator(
            ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT),
        )!!
        assertEquals(listOf(visible.id), coordinator.messages.map { it.id })
        assertEquals(listOf(visible.id), coordinator.renderState.items.map { it.id })
        assertEquals(2, coordinator.renderState.totalFetchedCount)
    }

    @Test
    fun prefetchLeavesALoadedCoordinatorAlone() = runTest {
        val store = FakeCoordinatorStore()
        val old = ChatFixtures.dmMessage(timestamp = 1_000)
        val registry = ChatCoordinatorRegistry(store, backgroundScope)
        val id = ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT)
        registry.coordinator(id).replaceAll(listOf(old))
        store.directPage = listOf(old, ChatFixtures.dmMessage(timestamp = 2_000))
        val conversation = Conversation.Direct(ChatFixtures.contact(name = "Pat", id = ChatFixtures.CONTACT))

        ChatTimelinePrimer(hooks(registry, store)).prefetch(conversation)

        val coordinator = registry.existingCoordinator(id)!!
        assertEquals(1, coordinator.messages.size)
        assertEquals(old.id, coordinator.messages.single().id)
    }

    @Test
    fun primeReplacesALoadedCoordinatorTail() = runTest {
        val store = FakeCoordinatorStore()
        val old = ChatFixtures.dmMessage(timestamp = 1_000)
        val fresh = ChatFixtures.dmMessage(timestamp = 2_000)
        val registry = ChatCoordinatorRegistry(store, backgroundScope)
        val id = ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT)
        registry.coordinator(id).replaceAll(listOf(old))
        store.directPage = listOf(old, fresh)
        val conversation = Conversation.Direct(ChatFixtures.contact(name = "Pat", id = ChatFixtures.CONTACT))

        ChatTimelinePrimer(hooks(registry, store)).prime(conversation)

        val coordinator = registry.existingCoordinator(id)!!
        assertEquals(listOf(old.id, fresh.id), coordinator.messages.map { it.id })
    }

    @Test
    fun primeSizesThePageFromUnreadCount() = runTest {
        val store = FakeCoordinatorStore()
        store.directPage = listOf(ChatFixtures.dmMessage(timestamp = 2_000))
        val registry = ChatCoordinatorRegistry(store, backgroundScope)
        val unread = 80
        val conversation = Conversation.Direct(
            ChatFixtures.contact(name = "Pat", id = ChatFixtures.CONTACT, unreadCount = unread),
        )

        ChatTimelinePrimer(hooks(registry, store)).prime(conversation)

        assertEquals(ChatCoordinator.initialPageSize(unread), store.lastDirectLimit)
    }

    @Test
    fun anOpenConversationIsNotPrimed() = runTest {
        val store = FakeCoordinatorStore()
        store.directPage = listOf(ChatFixtures.dmMessage(timestamp = 1_000))
        val registry = ChatCoordinatorRegistry(store, backgroundScope)
        val conversation = Conversation.Direct(ChatFixtures.contact(name = "Pat", id = ChatFixtures.CONTACT))

        ChatTimelinePrimer(hooks(registry, store, options = HookOptions(isActive = { true }))).prime(conversation)

        assertNull(registry.existingCoordinator(ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT)))
    }

    @Test
    fun roomsAreNotPrimed() = runTest {
        val store = FakeCoordinatorStore()
        val registry = ChatCoordinatorRegistry(store, backgroundScope)
        ChatTimelinePrimer(hooks(registry, store)).prime(Conversation.Room(ChatFixtures.room(name = "Ops")))
        assertNull(registry.existingCoordinator(ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT)))
    }

    @Test
    fun primingAChannelWarmsSenderTablesAndJpegBytes() = runTest {
        IncomingAvatarJPEGStore.withIsolatedStorageSuspending {
            ChannelSenderTableStore.withIsolatedStorageSuspending {
                val store = FakeCoordinatorStore()
                store.channelPage = listOf(ChatFixtures.channelMessage(timestamp = 2_000))
                val registry = ChatCoordinatorRegistry(store, backgroundScope)
                val contact = ChatFixtures.contact(name = "Maya", id = ChatFixtures.CONTACT)
                    .copy(avatarImageData = JPEG)
                val conversation = Conversation.Channel(ChatFixtures.channel(name = "ops", index = 0))

                ChatTimelinePrimer(
                    hooks(registry, store, options = HookOptions(loadContacts = { listOf(contact) })),
                ).prime(conversation)

                val tables = ChannelSenderTableStore.tables(ChatFixtures.RADIO)
                assertEquals(contact.id, tables.contacts.single().id)
                assertEquals(contact.id, tables.incomingAvatars["maya"]?.matchedContactID)
                assertContentEquals(JPEG, IncomingAvatarJPEGStore.data(contact.id))
            }
        }
    }

    @Test
    fun primingIndexesTheFetchedPage() = runTest {
        val store = FakeCoordinatorStore()
        val page = listOf(ChatFixtures.dmMessage(timestamp = 2_000))
        store.directPage = page
        val registry = ChatCoordinatorRegistry(store, backgroundScope)
        val conversation = Conversation.Direct(ChatFixtures.contact(name = "Pat", id = ChatFixtures.CONTACT))
        var indexed: List<Uuid> = emptyList()

        ChatTimelinePrimer(
            hooks(
                registry,
                store,
                options = HookOptions(indexPage = { _, messages ->
                    indexed = messages.map { it.id }
                }),
            ),
        ).prime(conversation)

        assertEquals(page.map { it.id }, indexed)
    }

    @Test
    fun primingBakesPathLabelsFromEnvInputs() = runTest {
        val store = FakeCoordinatorStore()
        store.directPage = listOf(ChatFixtures.dmMessage(timestamp = 2_000))
        val registry = ChatCoordinatorRegistry(store, backgroundScope)
        val conversation = Conversation.Direct(ChatFixtures.contact(name = "Pat", id = ChatFixtures.CONTACT))
        val env = EnvInputs(
            currentUserName = "OpsRadio",
            showIncomingSendTime = false,
            incomingPathLabels = MessagePathLabels(direct = "Directo", flood = "Inundación"),
        )

        ChatTimelinePrimer(hooks(registry, store, options = HookOptions(envInputs = { env }))).prime(conversation)

        val item = registry.existingCoordinator(
            ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT),
        )!!.renderState.items.single()
        assertEquals("Inundación", item.footer.formattedPath)
    }

    @Test
    fun primingBakesTheUnreadDividerOntoTheWarmPage() = runTest {
        val store = FakeCoordinatorStore()
        val page = (0 until 20).map { ChatFixtures.dmMessage(timestamp = 1_000L + it) }
        store.directPage = page
        val registry = ChatCoordinatorRegistry(store, backgroundScope)
        val unread = 12
        val conversation = Conversation.Direct(
            ChatFixtures.contact(name = "Pat", id = ChatFixtures.CONTACT, unreadCount = unread),
        )

        ChatTimelinePrimer(hooks(registry, store)).prime(conversation)

        val items = registry.existingCoordinator(
            ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT),
        )!!.renderState.items
        val dividerId = computeNewMessagesDivider(page, unread, isDM = true)
        assertEquals(page[8].id, dividerId)
        assertEquals(dividerId, items.first { it.grouping.showNewMessagesDivider }.id)
        assertEquals(1, items.count { it.grouping.showNewMessagesDivider })
    }

    @Test
    fun primingUsesUnknownSenderLabelForUnresolvedChannelSenders() = runTest {
        IncomingAvatarJPEGStore.withIsolatedStorageSuspending {
            ChannelSenderTableStore.withIsolatedStorageSuspending {
                val store = FakeCoordinatorStore()
                store.channelPage = listOf(ChatFixtures.channelMessage(timestamp = 2_000))
                val registry = ChatCoordinatorRegistry(store, backgroundScope)
                val conversation = Conversation.Channel(ChatFixtures.channel(name = "ops", index = 0))

                ChatTimelinePrimer(
                    hooks(registry, store, options = HookOptions(unknownLabel = { "Unknown" })),
                ).prime(conversation)

                val item = registry.existingCoordinator(
                    ChatConversationID.channel(ChatFixtures.RADIO, 0),
                )!!.renderState.items.single()
                assertEquals("Unknown", item.envelope.senderName)
            }
        }
    }

    private data class HookOptions(
        val isActive: (ChatConversationID) -> Boolean = { false },
        val loadContacts: suspend (Uuid) -> List<ContactDto> = { emptyList() },
        val indexPage: suspend (Conversation, List<MessageDto>) -> Unit = { _, _ -> },
        val envInputs: suspend () -> EnvInputs = { EnvInputs.DEFAULT },
        val unknownLabel: suspend () -> String = { "Unknown" },
    )

    private fun hooks(
        registry: ChatCoordinatorRegistry,
        store: ChatCoordinatorStore,
        options: HookOptions = HookOptions(),
    ) = ChatTimelinePrimer.Hooks(
        registry = { registry },
        store = { store },
        isConversationActive = options.isActive,
        loadContacts = options.loadContacts,
        indexPage = options.indexPage,
        envInputs = options.envInputs,
        unknownLabel = options.unknownLabel,
    )

    private companion object {
        val JPEG = byteArrayOf(1, 2, 3, 4)
    }
}
