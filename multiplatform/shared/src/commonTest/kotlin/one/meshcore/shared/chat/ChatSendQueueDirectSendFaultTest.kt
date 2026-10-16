package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
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
 * One direct send inside the drain. Cancellation keeps the thrown instance and does not park.
 * A transient device error parks. Every other failure, including a thrown error, is rethrown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendQueueDirectSendFaultTest {
    @Test
    fun aCancelledSendKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("superseded")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val harness = harness(this, recorded)
        harness.session.directSendError = { stopped }

        val cancelled = assertFailsWith<CancellationException> {
            harness.queue.sendDirectOrPark(harness.envelope, harness.contact, preserveTimestamp = false)
        }

        assertSame(stopped, cancelled)
        assertEquals(MessageStatus.FAILED, harness.store.messages.fetchMessage(harness.messageID)?.status)
        assertTrue(recorded.none { it.message.contains("DM drain ") })
    }

    @Test
    fun aTransientSendParks() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val harness = harness(this, recorded)
        harness.session.directSendError = {
            ChatDeviceException(FirmwareDeviceErrorCode.DIRECT_MESSAGE_TABLE_FULL)
        }
        harness.queue.triggers.fire()

        val parked = assertFailsWith<CancellationException> {
            harness.queue.sendDirectOrPark(harness.envelope, harness.contact, preserveTimestamp = false)
        }

        assertEquals("send-queue park", parked.message)
        assertEquals(MessageStatus.PENDING, harness.store.messages.fetchMessage(harness.messageID)?.status)
        assertTrue(recorded.any { it.message.contains("DM drain transient") })
    }

    @Test
    fun aSendErrorIsRethrown() = runTest(UnconfinedTestDispatcher()) {
        val probe = DirectSendProbeError()
        val recorded = mutableListOf<DebugLogEntryDto>()
        val harness = harness(this, recorded)
        harness.session.directSendError = { probe }

        val thrown = assertFailsWith<DirectSendProbeError> {
            harness.queue.sendDirectOrPark(harness.envelope, harness.contact, preserveTimestamp = false)
        }

        assertSame(probe, thrown)
        assertEquals(MessageStatus.FAILED, harness.store.messages.fetchMessage(harness.messageID)?.status)
        assertTrue(recorded.any { it.message.contains("DM drain terminal") })
    }

    @Test
    fun aSendExceptionIsRethrown() = runTest(UnconfinedTestDispatcher()) {
        val failure = IllegalStateException("db locked")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val harness = harness(this, recorded)
        harness.session.directSendError = { failure }

        val thrown = assertFailsWith<IllegalStateException> {
            harness.queue.sendDirectOrPark(harness.envelope, harness.contact, preserveTimestamp = false)
        }

        assertSame(failure, thrown)
        assertEquals(MessageStatus.FAILED, harness.store.messages.fetchMessage(harness.messageID)?.status)
        assertTrue(recorded.any { it.message.contains("DM drain terminal") })
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
        val contact = ChatFixtures.contact("Test Contact")
        store.contacts[contact.id] = contact
        val pending = service.createPendingMessage("Hello", contact)
        store.pending.upsertPendingSend(
            DirectMessageEnvelope(pending.id, contact.id)
                .toPendingSendDto(PENDING, RADIO, sequence = 1, NOW, attemptCount = 0),
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
        return Harness(store, session, queue, contact, pending.id, DirectMessageEnvelope(pending.id, contact.id))
    }

    private class Harness(
        val store: FakeSendQueueStore,
        val session: FakeChatSession,
        val queue: ChatSendQueueService,
        val contact: ContactDto,
        val messageID: Uuid,
        val envelope: DirectMessageEnvelope,
    )

    private companion object {
        const val NOW: Double = 1_700_000_000.0
        val RADIO: Uuid = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
        val PENDING: Uuid = Uuid.normalized("EEEEEEEE-0000-0000-0000-000000000001")
    }
}

private class DirectSendProbeError : Error("probe")
