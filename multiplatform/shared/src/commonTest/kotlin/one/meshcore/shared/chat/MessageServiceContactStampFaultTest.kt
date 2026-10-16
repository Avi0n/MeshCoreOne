package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A contact last-message stamp fault is logged and the acknowledgement still finishes. Cancellation
 * stops that finish and is not logged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageServiceContactStampFaultTest {
    @Test
    fun anOrdinaryContactStampFaultStillFinishesTheAcknowledgement() = runTest(UnconfinedTestDispatcher()) {
        val prepared = prepare(IllegalStateException("db locked"))

        prepared.service.handleAcknowledgement(prepared.ack, tripTime = 250)

        val line = prepared.recorded.single { it.message.contains("Failed to update contact lastMessageDate") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals("MessageService", line.category)
        assertTrue(line.message.contains("db locked"))
        assertFinishedAfterStampFault(prepared)
    }

    @Test
    fun aContactStampErrorStillFinishesTheAcknowledgement() = runTest(UnconfinedTestDispatcher()) {
        val prepared = prepare(ContactStampProbeError())

        prepared.service.handleAcknowledgement(prepared.ack, tripTime = 250)

        val line = prepared.recorded.single { it.message.contains("Failed to update contact lastMessageDate") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("contact stamp probe"))
        assertFinishedAfterStampFault(prepared)
    }

    @Test
    fun aCancelledContactStampStopsTheAcknowledgement() = runTest(UnconfinedTestDispatcher()) {
        val cancelled = CancellationException("superseded")
        val prepared = prepare(cancelled)

        val thrown = assertFailsWith<CancellationException> {
            prepared.service.handleAcknowledgement(prepared.ack, tripTime = 250)
        }

        assertSame(cancelled, thrown)
        assertTrue(prepared.recorded.none { it.message.contains("Failed to update contact lastMessageDate") })
        assertTrue(prepared.recorded.none { it.message.contains("[ack-diag] ACK matched") })
        assertEquals(MessageStatus.DELIVERED, prepared.store.fetchMessage(prepared.id)?.status)
        assertEquals(1, prepared.service.pendingAckCount())
        assertEquals(true, prepared.service.peekPendingAck(prepared.id)?.isDelivered)
        assertTrue(prepared.events.drain().isEmpty())
    }

    private suspend fun assertFinishedAfterStampFault(prepared: ContactStampCase) {
        assertEquals(MessageStatus.DELIVERED, prepared.store.fetchMessage(prepared.id)?.status)
        assertEquals(0, prepared.service.pendingAckCount())
        assertEquals(listOf(prepared.id to MessageStatus.DELIVERED), prepared.events.drain().resolvedTo())
        assertTrue(prepared.recorded.any { it.message.contains("[ack-diag] ACK matched") })
    }

    private suspend fun TestScope.prepare(failure: Throwable): ContactStampCase {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = ContactStampStore().apply { contactStampFailure = failure }
        val service = testMessageService(
            FakeChatSession(),
            store,
            this,
            logger = PersistentLogger(
                scope = this,
                category = "MessageService",
                record = { recorded += it },
            ),
        )
        val id = ChatFixtures.freshUuid()
        val ack = ackBytes(0x10, 0x20, 0x30, 0x40)
        store.saveMessage(
            ChatFixtures.message(id = id, status = MessageStatus.SENT, contactID = ChatFixtures.CONTACT)
                .copy(ackCode = ack.ackCodeLeUInt32()),
        )
        service.setPendingAck(
            pendingAck(id, contactID = ChatFixtures.CONTACT, codes = setOf(ack.ackCodeLeUInt32()), ageSeconds = 10.0),
        )
        return ContactStampCase(store, service, recorded, id, ack, service.statusEvents())
    }
}

private class ContactStampCase(
    val store: ContactStampStore,
    val service: MessageService,
    val recorded: MutableList<DebugLogEntryDto>,
    val id: Uuid,
    val ack: ByteArray,
    val events: ReceiveChannel<MessageStatusEvent>,
)

private class ContactStampStore(private val inner: InMemoryMessageStore = InMemoryMessageStore()) :
    MessageStore by inner {
    var contactStampFailure: Throwable? = null

    override suspend fun updateContactLastMessage(contactID: Uuid, dateEpochSeconds: Double) {
        contactStampFailure?.let { throw it }
        inner.updateContactLastMessage(contactID, dateEpochSeconds)
    }
}

private class ContactStampProbeError : Error("contact stamp probe")
