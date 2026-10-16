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
 * One channel send inside the drain. Cancellation keeps the thrown instance and does not park.
 * A transient send parks. Every other failure, including a thrown error, is rethrown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendQueueChannelSendFaultTest {
    @Test
    fun aCancelledSendKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("superseded")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val harness = harness(this, recorded)
        harness.session.directSendError = { stopped }

        val cancelled = assertFailsWith<CancellationException> {
            harness.queue.sendChannelOrHandle(harness.envelope, PREFLIGHT)
        }

        assertSame(stopped, cancelled)
        assertEquals(MessageStatus.FAILED, harness.store.messages.fetchMessage(harness.messageID)?.status)
        assertTrue(recorded.none { it.message.contains("channel drain ") })
    }

    @Test
    fun aTransientSendParks() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val harness = harness(this, recorded)
        harness.session.directSendError = { MessageServiceException.NotConnected() }
        harness.queue.triggers.fire()

        val parked = assertFailsWith<CancellationException> {
            harness.queue.sendChannelOrHandle(harness.envelope, PREFLIGHT)
        }

        assertEquals("send-queue park", parked.message)
        assertEquals(MessageStatus.PENDING, harness.store.messages.fetchMessage(harness.messageID)?.status)
        assertTrue(recorded.any { it.message.contains("channel drain transient") })
    }

    @Test
    fun aSendErrorIsRethrown() = runTest(UnconfinedTestDispatcher()) {
        val probe = ChannelSendProbeError()
        val recorded = mutableListOf<DebugLogEntryDto>()
        val harness = harness(this, recorded)
        harness.session.directSendError = { probe }

        val thrown = assertFailsWith<ChannelSendProbeError> {
            harness.queue.sendChannelOrHandle(harness.envelope, PREFLIGHT)
        }

        assertSame(probe, thrown)
        assertEquals(MessageStatus.FAILED, harness.store.messages.fetchMessage(harness.messageID)?.status)
        assertTrue(recorded.any { it.message.contains("channel drain terminal") })
    }

    @Test
    fun aSendExceptionIsRethrown() = runTest(UnconfinedTestDispatcher()) {
        val failure = IllegalStateException("db locked")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val harness = harness(this, recorded)
        harness.session.directSendError = { failure }

        val thrown = assertFailsWith<IllegalStateException> {
            harness.queue.sendChannelOrHandle(harness.envelope, PREFLIGHT)
        }

        assertSame(failure, thrown)
        assertEquals(MessageStatus.FAILED, harness.store.messages.fetchMessage(harness.messageID)?.status)
        assertTrue(recorded.any { it.message.contains("channel drain terminal") })
    }

    private suspend fun harness(scope: CoroutineScope, recorded: MutableList<DebugLogEntryDto>): Harness {
        val store = FakeSendQueueStore()
        val session = FakeChatSession()
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
                channelResolver = FakeChannelResolver(),
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
        return Harness(store, session, queue, pending.id, envelope)
    }

    private class Harness(
        val store: FakeSendQueueStore,
        val session: FakeChatSession,
        val queue: ChatSendQueueService,
        val messageID: Uuid,
        val envelope: ChannelMessageEnvelope,
    )

    private companion object {
        const val NOW: Double = 1_700_000_000.0
        const val CHANNEL_INDEX = 0
        val RADIO: Uuid = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
        val PREFLIGHT = PreflightResult(postBumpCount = 1, preserveTimestamp = false)
    }
}

private class ChannelSendProbeError : Error("probe")
