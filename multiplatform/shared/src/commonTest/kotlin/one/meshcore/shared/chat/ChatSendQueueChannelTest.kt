package one.meshcore.shared.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Mirrors the Swift `ChatSendQueueServiceTests` channel-drain cases: the NOT_FOUND disambiguation gate
 * (park below the attempt threshold, spend a device round-trip at/above it) and the per-envelope
 * fetch-failure counter that must not cascade one envelope's terminal-fail onto another. The channel send
 * is forced to throw the firmware NOT_FOUND (`deviceError(2)`); the injected [FakeChannelResolver] answers
 * the disambiguation read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendQueueChannelTest {
    private companion object {
        const val NOW: Double = 1_700_000_000.0
        const val CHANNEL_INDEX = 0
        val RADIO: Uuid = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
        val FAST_BACKOFF = MessageServiceConfig(
            poolBackoff = PoolBackoffConfig(attemptCap = 2, baseDelaySeconds = 0.01),
        )
    }

    private class ChannelHarness(
        val store: FakeSendQueueStore,
        val session: FakeChatSession,
        val queue: ChatSendQueueService,
        val resolver: FakeChannelResolver,
        val messageID: Uuid,
    )

    private suspend fun buildChannelRow(
        store: FakeSendQueueStore,
        messageService: MessageService,
        idSuffix: Int,
        sequence: Int,
        attemptCount: Int,
    ): Uuid {
        val pending = messageService.createPendingChannelMessage("Hello channel $idSuffix", CHANNEL_INDEX, RADIO)
        val envelope = ChannelMessageEnvelope(
            messageID = pending.id,
            channelIndex = CHANNEL_INDEX,
            isResend = true,
            messageText = pending.text,
            messageTimestamp = pending.timestamp,
            localNodeName = "Test Radio",
        )
        store.pending.upsertPendingSend(
            envelope.toPendingSendDto(
                Uuid.normalized("EEEEEEEE-0000-0000-0000-" + idSuffix.toString().padStart(12, '0')),
                RADIO,
                sequence,
                NOW,
                attemptCount,
            ),
        )
        return pending.id
    }

    private suspend fun buildHarness(
        scope: CoroutineScope,
        attemptCount: Int,
        resolver: FakeChannelResolver,
        queueConfig: ChatSendQueueConfig = ChatSendQueueConfig(),
    ): ChannelHarness {
        val store = FakeSendQueueStore()
        val session = FakeChatSession()
        val messageService =
            MessageService(
                session,
                store.messages,
                scope,
                MessageServiceIdentity({ NOW }, SequentialIds()::next),
                FAST_BACKOFF,
            )
        val messageID = buildChannelRow(store, messageService, idSuffix = 1, sequence = 1, attemptCount = attemptCount)
        val queue = queueFor(store, messageService, resolver, scope, QueueOptions(queueConfig = queueConfig))
        session.directSendError = { ChatDeviceException(FirmwareDeviceErrorCode.CHANNEL_MESSAGE_NOT_FOUND) }
        return ChannelHarness(store, session, queue, resolver, messageID)
    }

    private data class QueueOptions(
        val queueConfig: ChatSendQueueConfig = ChatSendQueueConfig(),
        val reactionIndexer: ChannelReactionIndexer? = null,
    )

    private fun queueFor(
        store: FakeSendQueueStore,
        service: MessageService,
        resolver: FakeChannelResolver,
        scope: CoroutineScope,
        options: QueueOptions = QueueOptions(),
    ): ChatSendQueueService = ChatSendQueueService(
        radioID = RADIO,
        collaborators = ChatSendQueueCollaborators(
            store = store,
            messageService = service,
            channelResolver = resolver,
            scope = scope,
            now = { NOW },
            newPendingSendId = PendingSendIds()::next,
        ),
        edges = ChatSendQueueEdges(
            reactionIndexer = options.reactionIndexer,
            config = options.queueConfig,
        ),
    )

    @Test
    fun `channel drain indexes a successful send for reactions`() = runTest {
        val store = FakeSendQueueStore()
        val session = FakeChatSession()
        val messageService =
            MessageService(
                session,
                store.messages,
                backgroundScope,
                MessageServiceIdentity({ NOW }, SequentialIds()::next),
                FAST_BACKOFF,
            )
        val pending = messageService.createPendingChannelMessage("Hello channel", CHANNEL_INDEX, RADIO)
        val indexed = mutableListOf<String>()
        val queue = queueFor(
            store,
            messageService,
            FakeChannelResolver(),
            backgroundScope,
            QueueOptions(
                reactionIndexer = ChannelReactionIndexer { id, channelIndex, senderName, text, timestamp ->
                    indexed += "$id/$channelIndex/$senderName/$text/$timestamp"
                },
            ),
        )

        queue.enqueueChannel(
            ChannelMessageEnvelope(
                messageID = pending.id,
                channelIndex = CHANNEL_INDEX,
                isResend = false,
                messageText = pending.text,
                messageTimestamp = pending.timestamp,
                localNodeName = "Test Radio",
            ),
        )
        queue.awaitDrainCompletion()

        assertEquals(
            listOf("${pending.id}/$CHANNEL_INDEX/Test Radio/${pending.text}/${pending.timestamp.toLong()}"),
            indexed,
        )
        assertTrue(store.fetchPendingSends(RADIO).isEmpty())
    }

    private suspend fun rowFor(store: FakeSendQueueStore, messageID: Uuid): PendingSendDto? =
        store.fetchPendingSends(RADIO).firstOrNull { it.messageID == messageID }

    @Test
    fun `channel drain parks NOT_FOUND below the disambiguate threshold without a device round-trip`() = runTest {
        val resolver = FakeChannelResolver()
        val harness = buildHarness(backgroundScope, attemptCount = 0, resolver = resolver)

        harness.queue.hydrate()
        advanceTimeBy(2.seconds)

        assertEquals(0, resolver.calls, "postBumpCount 1 is below the threshold, so no channelExists round-trip runs")
        assertEquals(1, rowFor(harness.store, harness.messageID)?.attemptCount)
        assertEquals(MessageStatus.PENDING, harness.store.messages.fetchMessage(harness.messageID)?.status)
    }

    @Test
    fun `channel drain treats NOT_FOUND as terminal when the device confirms the channel is gone`() = runTest {
        val resolver = FakeChannelResolver(exists = false)
        val harness = buildHarness(backgroundScope, attemptCount = 7, resolver = resolver)

        harness.queue.hydrate()
        harness.queue.awaitDrainCompletion()

        assertTrue(resolver.calls >= 1, "at/above the threshold the drain spends a channelExists round-trip")
        assertTrue(rowFor(harness.store, harness.messageID) == null, "confirmed-gone channel is terminal")
        assertEquals(MessageStatus.FAILED, harness.store.messages.fetchMessage(harness.messageID)?.status)
    }

    @Test
    fun `channel drain parks NOT_FOUND when the device confirms the channel still exists`() = runTest {
        val resolver = FakeChannelResolver(exists = true)
        val harness = buildHarness(backgroundScope, attemptCount = 7, resolver = resolver)

        harness.queue.hydrate()
        advanceTimeBy(2.seconds)

        assertTrue(resolver.calls >= 1)
        assertTrue(rowFor(harness.store, harness.messageID) != null, "still-present channel: pool exhaustion, park")
        assertEquals(MessageStatus.PENDING, harness.store.messages.fetchMessage(harness.messageID)?.status)
    }

    @Test
    fun `channel fetchChannel failure counter is per-envelope and does not cascade`() = runTest {
        val resolver = FakeChannelResolver(fault = { RuntimeException("fetch failed") })
        val queueConfig = ChatSendQueueConfig(maxConsecutiveFetchChannelFailures = 2)
        val store = FakeSendQueueStore()
        val session = FakeChatSession()
        val messageService =
            MessageService(
                session,
                store.messages,
                backgroundScope,
                MessageServiceIdentity({ NOW }, SequentialIds()::next),
                FAST_BACKOFF,
            )
        // Envelope A drains repeatedly (postBumpCount well above the threshold) and cascades to the cap;
        // envelope B enters the disambiguate path once and must start its own counter at zero.
        val messageA = buildChannelRow(store, messageService, idSuffix = 1, sequence = 1, attemptCount = 7)
        val messageB = buildChannelRow(store, messageService, idSuffix = 2, sequence = 2, attemptCount = 2)
        session.directSendError = { ChatDeviceException(FirmwareDeviceErrorCode.CHANNEL_MESSAGE_NOT_FOUND) }
        val queue = queueFor(
            store,
            messageService,
            resolver,
            backgroundScope,
            QueueOptions(queueConfig = queueConfig),
        )

        queue.hydrate()
        advanceTimeBy(1.seconds)
        // A parked after its first fetch throw (counter A = 1 < 2). Wake it for its second attempt, which
        // hits the cap and terminal-fails, letting the serial drain advance to B.
        queue.transportDidOpen()
        advanceTimeBy(2.seconds)

        assertTrue(rowFor(store, messageA) == null, "envelope A hits the per-envelope cap and terminal-fails")
        assertEquals(MessageStatus.FAILED, store.messages.fetchMessage(messageA)?.status)
        assertTrue(
            rowFor(store, messageB) != null,
            "envelope B's counter starts fresh, so its first fetch throw parks rather than inheriting A's cap",
        )
        assertEquals(MessageStatus.PENDING, store.messages.fetchMessage(messageB)?.status)
    }
}
