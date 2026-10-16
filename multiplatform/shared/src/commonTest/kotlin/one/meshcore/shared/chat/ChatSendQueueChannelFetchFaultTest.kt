package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The channel-exists read after a NOT_FOUND send. Cancellation keeps the thrown instance and does not
 * park. A fetch exception or a thrown error still parks below the consecutive-failure cap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendQueueChannelFetchFaultTest {
    @Test
    fun aCancelledChannelFetchKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("superseded")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val harness = harness(this, recorded, fault = { stopped })
        harness.store.updateMessageStatus(harness.messageID, MessageStatus.FAILED)
        harness.queue.triggers.fire()

        val cancelled = assertFailsWith<CancellationException> {
            harness.queue.disambiguateChannelNotFound(harness.envelope, POST_BUMP_COUNT, SEND_ERROR)
        }

        assertSame(stopped, cancelled)
        assertEquals(MessageStatus.FAILED, harness.store.messages.fetchMessage(harness.messageID)?.status)
        assertEquals(1, harness.resolver.calls)
        assertTrue(recorded.none { it.message.contains("fetchChannel failed") })
    }

    @Test
    fun aChannelFetchExceptionParks() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val harness = harness(this, recorded, fault = { IllegalStateException("db locked") })
        harness.store.updateMessageStatus(harness.messageID, MessageStatus.FAILED)
        harness.queue.triggers.fire()

        val parked = assertFailsWith<CancellationException> {
            harness.queue.disambiguateChannelNotFound(harness.envelope, POST_BUMP_COUNT, SEND_ERROR)
        }

        assertEquals("send-queue park", parked.message)
        assertEquals(MessageStatus.PENDING, harness.store.messages.fetchMessage(harness.messageID)?.status)
        assertTrue(recorded.any { it.message.contains("fetchChannel failed") })
    }

    @Test
    fun aChannelFetchErrorParks() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val harness = harness(this, recorded, fault = { ChannelFetchProbeError() })
        harness.store.updateMessageStatus(harness.messageID, MessageStatus.FAILED)
        harness.queue.triggers.fire()

        val parked = assertFailsWith<CancellationException> {
            harness.queue.disambiguateChannelNotFound(harness.envelope, POST_BUMP_COUNT, SEND_ERROR)
        }

        assertEquals("send-queue park", parked.message)
        assertEquals(MessageStatus.PENDING, harness.store.messages.fetchMessage(harness.messageID)?.status)
        assertTrue(recorded.any { it.message.contains("fetchChannel failed") })
    }

    private suspend fun harness(
        scope: CoroutineScope,
        recorded: MutableList<DebugLogEntryDto>,
        fault: () -> Throwable,
    ): Harness {
        val store = FakeSendQueueStore()
        val session = FakeChatSession()
        val resolver = FakeChannelResolver(fault = fault)
        val service = MessageService(
            session,
            store.messages,
            scope,
            MessageServiceIdentity({ NOW }, SequentialIds()::next),
            MessageServiceConfig(poolBackoff = PoolBackoffConfig(attemptCap = 2, baseDelaySeconds = 0.01)),
        )
        val pending = service.createPendingChannelMessage("Hello channel", CHANNEL_INDEX, RADIO)
        val envelope = ChannelMessageEnvelope(
            messageID = pending.id,
            channelIndex = CHANNEL_INDEX,
            messageText = pending.text,
            messageTimestamp = pending.timestamp,
        )
        val queue = ChatSendQueueService(
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
                logger = PersistentLogger(
                    scope = scope,
                    category = "ChatSendQueueService",
                    record = { recorded += it },
                ),
            ),
        )
        return Harness(store, resolver, queue, pending.id, envelope)
    }

    private class Harness(
        val store: FakeSendQueueStore,
        val resolver: FakeChannelResolver,
        val queue: ChatSendQueueService,
        val messageID: Uuid,
        val envelope: ChannelMessageEnvelope,
    )

    private companion object {
        const val NOW: Double = 1_700_000_000.0
        const val CHANNEL_INDEX = 0
        val RADIO: Uuid = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
        val POST_BUMP_COUNT = ChatSendQueueConfig().disambiguateAfterAttempts
        val SEND_ERROR = ChatDeviceException(FirmwareDeviceErrorCode.CHANNEL_MESSAGE_NOT_FOUND)
    }
}

private class ChannelFetchProbeError : Error("probe")
