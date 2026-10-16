package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The channel-drain boundary. Cancellation leaves the drain and keeps the thrown instance.
 * Any other failure, including a thrown error, is broadcast as failed and rethrown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendQueueChannelDrainFaultTest {
    @Test
    fun aCancelledBumpKeepsTheThrownInstanceAndDoesNotFail() = runTest {
        val stopped = CancellationException("superseded")
        val harness = harness(backgroundScope)
        val events = harness.service.statusEvents()
        harness.store.pending.incrementFault = { stopped }

        val cancelled = assertFailsWith<CancellationException> {
            harness.queue.drainChannelMessage(harness.envelope)
        }

        assertSame(stopped, cancelled)
        assertTrue(events.drain().failedIds().isEmpty())
    }

    @Test
    fun aSendErrorIsBroadcastFailedAndRethrown() = runTest {
        val probe = ChannelDrainProbeError()
        val harness = harness(backgroundScope)
        val events = harness.service.statusEvents()
        harness.session.directSendError = { probe }

        val thrown = assertFailsWith<ChannelDrainProbeError> {
            harness.queue.drainChannelMessage(harness.envelope)
        }

        assertSame(probe, thrown)
        assertEquals(listOf(harness.messageID), events.drain().failedIds())
    }

    @Test
    fun aSendExceptionIsBroadcastFailedAndRethrown() = runTest {
        val failure = IllegalStateException("db locked")
        val harness = harness(backgroundScope)
        val events = harness.service.statusEvents()
        harness.session.directSendError = { failure }

        val thrown = assertFailsWith<IllegalStateException> {
            harness.queue.drainChannelMessage(harness.envelope)
        }

        assertSame(failure, thrown)
        assertEquals(listOf(harness.messageID), events.drain().failedIds())
    }

    private suspend fun harness(scope: CoroutineScope): Harness {
        val store = FakeSendQueueStore()
        val session = FakeChatSession()
        val service = MessageService(
            session,
            store.messages,
            scope,
            MessageServiceIdentity({ NOW }, SequentialIds()::next),
        )
        val pending = service.createPendingChannelMessage("Hello channel", CHANNEL_INDEX, RADIO)
        val envelope = ChannelMessageEnvelope(
            messageID = pending.id,
            channelIndex = CHANNEL_INDEX,
            messageText = pending.text,
            messageTimestamp = pending.timestamp,
        )
        store.pending.upsertPendingSend(
            envelope.toPendingSendDto(PENDING, RADIO, sequence = 1, NOW, attemptCount = 0),
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
        )
        return Harness(store, session, service, queue, pending.id, envelope)
    }

    private class Harness(
        val store: FakeSendQueueStore,
        val session: FakeChatSession,
        val service: MessageService,
        val queue: ChatSendQueueService,
        val messageID: Uuid,
        val envelope: ChannelMessageEnvelope,
    )

    private companion object {
        const val NOW: Double = 1_700_000_000.0
        const val CHANNEL_INDEX = 0
        val RADIO: Uuid = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
        val PENDING: Uuid = Uuid.normalized("EEEEEEEE-0000-0000-0000-000000000001")
    }
}

private class ChannelDrainProbeError : Error("probe")
