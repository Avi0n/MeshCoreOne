package one.meshcore.shared.chat.reactions

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.ChannelReactionWire
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.chat.reactionTimestamp
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of the Swift `SyncCoordinator+ReactionHandlers` receive tests and `ChatViewModelReactionIndexingTests`:
 * the four inbound formats resolving a target and persisting, plus scope-keyed indexing of loaded pages with
 * the radioID sourced from the conversation and the `reactionExists` dedup guard.
 */
class ReactionHandlerTest {
    private val ts = 1_704_067_200L
    private val receiveTime = ts.toDouble()

    private fun channelWire(text: String, senderNodeName: String? = "Bob") = ChannelReactionWire(
        text = text,
        channelIndex = 3,
        senderNodeName = senderNodeName,
        selfNodeName = "Me",
        receiveTimeEpochSeconds = receiveTime,
        radioID = ChatFixtures.RADIO,
    )

    private fun service() = ReactionService(newReactionId = { ChatFixtures.freshUuid() }, now = { NOW })

    private fun handler(
        store: ReactionStore,
        svc: ReactionService,
        sink: RecordingSummarySink,
        notifier: ReactionNotifier = ReactionNotifier.NoOp,
        localNodeName: () -> String? = { null },
    ) = ReactionHandler(
        svc,
        store,
        sink,
        newReactionId = { ChatFixtures.freshUuid() },
        now = { NOW },
        edges = ReactionHandlerEdges(
            unknownSenderLabel = { UNKNOWN_SENDER_LABEL },
            notifier = notifier,
            localNodeName = localNodeName,
        ),
    )

    private fun channelMessage(
        sender: String? = "Alice",
        index: Int = 3,
        direction: MessageDirection = MessageDirection.INCOMING,
    ) = ChatFixtures.message(
        text = "Hello world",
        timestamp = ts,
        direction = direction,
        status = MessageStatus.DELIVERED,
        contactID = null,
        channelIndex = index,
        senderNodeName = sender,
    )

    // MARK: - Index-on-load: DM scope (ChatViewModelReactionIndexingTests)

    @Test
    fun dmScopeMatchesQueuedPendingPersistsScopedToContact() = runTest {
        val contact = ChatFixtures.contact("Alice")
        val message = deliveredDirectMessage(contact.id)
        val store = InMemoryReactionStore(messages = listOf(message), contacts = listOf(contact))
        val svc = service()
        val sink = RecordingSummarySink()

        val rawText = ReactionParser.buildDmReactionText("👍", message.text, message.reactionTimestamp)
        val parsed = assertNotNull(ReactionParser.parseDM(rawText))
        svc.queuePendingDmReaction(parsed, contact.id, "Bob", rawText, contact.radioID)

        handler(store, svc, sink).indexMessages(listOf(message), ReactionIndexScope.Direct(contact))

        assertEquals(1, store.reactions.size)
        val reaction = store.reactions.first()
        assertEquals("👍", reaction.emoji)
        assertEquals("Bob", reaction.senderName)
        assertEquals(contact.id, reaction.contactID)
        assertNull(reaction.channelIndex)
        assertEquals(contact.radioID, reaction.radioID)
        assertTrue(sink.summaryFor(message.id)?.contains("👍") == true)
    }

    @Test
    fun channelScopeMatchesQueuedPendingPersistsScopedToChannel() = runTest {
        val channel = ChatFixtures.channel("General", index = 3)
        val message = channelMessage(sender = "Alice", index = channel.index)
        val store = InMemoryReactionStore(messages = listOf(message))
        val svc = service()
        val sink = RecordingSummarySink()

        val rawText = svc.buildReactionText("🔥", "Alice", message.text, message.timestamp)
        val parsed = assertNotNull(ReactionParser.parse(rawText))
        svc.queuePendingReaction(parsed, channel.index, "Bob", rawText, channel.radioID)

        handler(store, svc, sink).indexMessages(
            listOf(message),
            ReactionIndexScope.Channel(channel, localNodeName = null),
        )

        assertEquals(1, store.reactions.size)
        val reaction = store.reactions.first()
        assertEquals("🔥", reaction.emoji)
        assertEquals("Bob", reaction.senderName)
        assertEquals(channel.index, reaction.channelIndex)
        assertNull(reaction.contactID)
        assertEquals(channel.radioID, reaction.radioID)
        assertTrue(sink.summaryFor(message.id)?.contains("🔥") == true)
    }

    @Test
    fun reQueuedDuplicateReactionDroppedByReactionExistsGuard() = runTest {
        val contact = ChatFixtures.contact("Alice")
        val message = deliveredDirectMessage(contact.id)
        val store = InMemoryReactionStore(messages = listOf(message), contacts = listOf(contact))
        val svc = service()
        val h = handler(store, svc, RecordingSummarySink())

        val rawText = ReactionParser.buildDmReactionText("👍", message.text, message.reactionTimestamp)
        val parsed = assertNotNull(ReactionParser.parseDM(rawText))
        repeat(2) {
            svc.queuePendingDmReaction(parsed, contact.id, "Bob", rawText, contact.radioID)
            h.indexMessages(listOf(message), ReactionIndexScope.Direct(contact))
        }
        assertEquals(1, store.reactions.size)
    }

    @Test
    fun channelScopeSkipsOutgoingWhenLocalNodeNameUnknown() = runTest {
        val channel = ChatFixtures.channel("General", index = 3)
        val message = channelMessage(sender = null, index = channel.index, direction = MessageDirection.OUTGOING)
        val store = InMemoryReactionStore(messages = listOf(message))
        val svc = service()

        val rawText = svc.buildReactionText("🔥", "Me", message.text, message.timestamp)
        val parsed = assertNotNull(ReactionParser.parse(rawText))
        svc.queuePendingReaction(parsed, channel.index, "Bob", rawText, channel.radioID)

        handler(store, svc, RecordingSummarySink()).indexMessages(
            listOf(message),
            ReactionIndexScope.Channel(channel, localNodeName = null),
        )
        assertTrue(store.reactions.isEmpty())
    }

    // MARK: - PocketMesh receive

    @Test
    fun handleChannelReactionResolvesTargetViaDbFallback() = runTest {
        val message = channelMessage(sender = "Alice")
        val store = InMemoryReactionStore(messages = listOf(message))
        val svc = service()
        val h = handler(store, svc, RecordingSummarySink())

        val text = svc.buildReactionText("👍", "Alice", message.text, message.reactionTimestamp)
        val consumed = h.handleChannelReaction(channelWire(text))
        assertTrue(consumed)
        assertEquals(1, store.reactions.size)
        assertEquals("👍", store.reactions.first().emoji)
        assertEquals("Bob", store.reactions.first().senderName)
    }

    @Test
    fun missingChannelReactionSenderUsesLocalizedLabel() = runTest {
        val message = channelMessage(sender = "Alice")
        val store = InMemoryReactionStore(messages = listOf(message))
        val svc = service()
        val h = handler(store, svc, RecordingSummarySink())
        val text = svc.buildReactionText("👍", "Alice", message.text, message.reactionTimestamp)

        assertTrue(h.handleChannelReaction(channelWire(text, senderNodeName = null)))

        assertEquals(UNKNOWN_SENDER_LABEL, store.reactions.single().senderName)
    }

    @Test
    fun handleDirectReactionResolvesTargetViaCache() = runTest {
        val contact = ChatFixtures.contact("Alice")
        val store = InMemoryReactionStore(contacts = listOf(contact))
        val svc = service()
        val h = handler(store, svc, RecordingSummarySink())

        val messageID = ChatFixtures.freshUuid()
        svc.indexDmMessage(messageID, contact.id, "Hello world", ts)
        val text = svc.buildDmReactionText("❤️", "Hello world", ts)

        assertTrue(h.handleDirectReaction(text, contact.resolved(), ChatFixtures.RADIO))
        assertEquals(1, store.reactions.size)
        assertEquals("❤️", store.reactions.first().emoji)
        assertEquals("Alice", store.reactions.first().senderName)
        assertEquals(messageID, store.reactions.first().messageID)
    }

    @Test
    fun handleChannelReactionReturnsFalseForNonReactionText() = runTest {
        val h = handler(InMemoryReactionStore(), service(), RecordingSummarySink())
        assertFalse(
            h.handleChannelReaction(channelWire("just a normal message")),
        )
    }

    @Test
    fun reactionWithNoTargetIsQueuedNotPersisted() = runTest {
        val store = InMemoryReactionStore()
        val svc = service()
        val h = handler(store, svc, RecordingSummarySink())
        val text = svc.buildReactionText("👍", "Alice", "Hello world", ts)

        assertTrue(
            h.handleChannelReaction(channelWire(text)),
        )
        assertTrue(store.reactions.isEmpty())
        // The queued reaction drains onto the target once it is indexed.
        assertEquals(1, svc.indexMessage(ChatFixtures.freshUuid(), 3, "Alice", "Hello world", ts).size)
    }

    // MARK: - meshcore-open receive

    @Test
    fun handleChannelReactionMatchesMeshCoreOpenV3ViaDartHash() = runTest {
        val message = channelMessage(sender = "Alice")
        val store = InMemoryReactionStore(messages = listOf(message))
        val h = handler(store, service(), RecordingSummarySink())

        val dartHash = MeshCoreOpenReactionParser.computeReactionHash(message.reactionTimestamp, "Alice", message.text)
        assertTrue(
            h.handleChannelReaction(channelWire("r:$dartHash:00")), // index 00 = 👍
        )
        assertEquals(1, store.reactions.size)
        assertEquals("👍", store.reactions.first().emoji)
        assertEquals(dartHash, store.reactions.first().messageHash)
    }

    @Test
    fun handleChannelReactionMatchesMeshCoreOpenV1ViaSenderAndTextHash() = runTest {
        val message = channelMessage(sender = "Alice")
        val store = InMemoryReactionStore(messages = listOf(message))
        val h = handler(store, service(), RecordingSummarySink())

        val senderHash = MeshCoreOpenReactionParser.dartStringHash("Alice")
        val textHash = MeshCoreOpenReactionParser.dartStringHash(message.text)
        assertTrue(
            h.handleChannelReaction(channelWire("r:${ts * 1000}_${senderHash}_$textHash:🔥")),
        )
        assertEquals(1, store.reactions.size)
        assertEquals("🔥", store.reactions.first().emoji)
    }

    @Test
    fun handleDirectReactionMatchesMeshCoreOpenV3ViaDbCandidates() = runTest {
        val contact = ChatFixtures.contact("Alice")
        val message = deliveredDirectMessage(contact.id)
        val store = InMemoryReactionStore(messages = listOf(message), contacts = listOf(contact))
        val h = handler(store, service(), RecordingSummarySink())

        val dartHash = MeshCoreOpenReactionParser.computeReactionHash(message.reactionTimestamp, null, message.text)
        assertTrue(h.handleDirectReaction("r:$dartHash:01", contact.resolved(), ChatFixtures.RADIO)) // 01 = ❤️
        assertEquals(1, store.reactions.size)
        assertEquals("❤️", store.reactions.first().emoji)
    }

    // MARK: - Reaction notification gating

    @Test
    fun reactionToOutgoingDirectMessageNotifies() = runTest {
        val contact = ChatFixtures.contact("Alice")
        val message = deliveredDirectMessage(contact.id, direction = MessageDirection.OUTGOING)
        val store = InMemoryReactionStore(messages = listOf(message), contacts = listOf(contact))
        val notifier = RecordingReactionNotifier()
        val h = handler(store, service(), RecordingSummarySink(), notifier = notifier, localNodeName = { "Me" })

        val text = ReactionParser.buildDmReactionText("👍", message.text, message.reactionTimestamp)
        assertTrue(h.handleDirectReaction(text, contact.resolved(), ChatFixtures.RADIO))

        val notified = notifier.notified.single()
        assertEquals("Alice", notified.reactorName)
        assertEquals("👍", notified.emoji)
        assertEquals(contact.id, notified.contactID)
    }

    @Test
    fun reactionToIncomingDirectMessageDoesNotNotify() = runTest {
        val contact = ChatFixtures.contact("Alice")
        val message = deliveredDirectMessage(contact.id, direction = MessageDirection.INCOMING)
        val store = InMemoryReactionStore(messages = listOf(message), contacts = listOf(contact))
        val notifier = RecordingReactionNotifier()
        val h = handler(store, service(), RecordingSummarySink(), notifier = notifier, localNodeName = { "Me" })

        val text = ReactionParser.buildDmReactionText("👍", message.text, message.reactionTimestamp)
        assertTrue(h.handleDirectReaction(text, contact.resolved(), ChatFixtures.RADIO))

        assertTrue(notifier.notified.isEmpty())
    }

    @Test
    fun selfReactionToOwnMessageDoesNotNotify() = runTest {
        val contact = ChatFixtures.contact("Me")
        val message = deliveredDirectMessage(contact.id, direction = MessageDirection.OUTGOING)
        val store = InMemoryReactionStore(messages = listOf(message), contacts = listOf(contact))
        val notifier = RecordingReactionNotifier()
        // The reactor name on a resolved DM contact is the contact's own display name ("Me" here),
        // matching the local node so the self-reaction gate fires.
        val h = handler(store, service(), RecordingSummarySink(), notifier = notifier, localNodeName = { "Me" })

        val text = ReactionParser.buildDmReactionText("👍", message.text, message.reactionTimestamp)
        assertTrue(h.handleDirectReaction(text, contact.resolved(), ChatFixtures.RADIO))

        assertTrue(notifier.notified.isEmpty())
    }

    @Test
    fun reactionOnMutedContactDoesNotNotify() = runTest {
        val contact = ChatFixtures.contact("Alice", isMuted = true)
        val message = deliveredDirectMessage(contact.id, direction = MessageDirection.OUTGOING)
        val store = InMemoryReactionStore(messages = listOf(message), contacts = listOf(contact))
        val notifier = RecordingReactionNotifier()
        val h = handler(store, service(), RecordingSummarySink(), notifier = notifier, localNodeName = { "Me" })

        val text = ReactionParser.buildDmReactionText("👍", message.text, message.reactionTimestamp)
        assertTrue(h.handleDirectReaction(text, contact.resolved(), ChatFixtures.RADIO))

        assertTrue(notifier.notified.isEmpty())
    }

    @Test
    fun reactionToOutgoingChannelMessageNotifies() = runTest {
        val message = channelMessage(sender = null, direction = MessageDirection.OUTGOING)
        val store = InMemoryReactionStore(messages = listOf(message))
        val notifier = RecordingReactionNotifier()
        val h = handler(store, service(), RecordingSummarySink(), notifier = notifier, localNodeName = { "Me" })

        val text = service().buildReactionText("🔥", "Me", message.text, message.reactionTimestamp)
        assertTrue(
            h.handleChannelReaction(channelWire(text)),
        )

        val notified = notifier.notified.single()
        assertEquals("Bob", notified.reactorName)
        assertEquals(3, notified.channelIndex)
    }

    @Test
    fun duplicateReactionDoesNotReNotify() = runTest {
        val contact = ChatFixtures.contact("Alice")
        val message = deliveredDirectMessage(contact.id, direction = MessageDirection.OUTGOING)
        val store = InMemoryReactionStore(messages = listOf(message), contacts = listOf(contact))
        val notifier = RecordingReactionNotifier()
        val svc = service()
        val h = handler(store, svc, RecordingSummarySink(), notifier = notifier, localNodeName = { "Me" })

        val text = ReactionParser.buildDmReactionText("👍", message.text, message.reactionTimestamp)
        h.handleDirectReaction(text, contact.resolved(), ChatFixtures.RADIO)
        h.handleDirectReaction(text, contact.resolved(), ChatFixtures.RADIO)

        assertEquals(1, notifier.notified.size)
    }

    private companion object {
        const val NOW = 1_704_067_500.0
        const val UNKNOWN_SENDER_LABEL = "Localized unknown sender"
    }
}
