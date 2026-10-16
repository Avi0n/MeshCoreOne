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
 * A delivered-status write fault is logged and the acknowledgement still finishes. Cancellation
 * stops that finish and is not logged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageServiceDeliveredStatusFaultTest {
    @Test
    fun anOrdinaryDeliveredStatusFaultStillFinishesTheAcknowledgement() = runTest(UnconfinedTestDispatcher()) {
        val prepared = prepare(IllegalStateException("db locked"))

        prepared.service.handleAcknowledgement(prepared.ack, tripTime = 250)

        val line = prepared.recorded.single { it.message.contains("Failed to write delivered status") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals("MessageService", line.category)
        assertTrue(line.message.contains("db locked"))
        assertFinishedAfterFault(prepared)
    }

    @Test
    fun aDeliveredStatusErrorStillFinishesTheAcknowledgement() = runTest(UnconfinedTestDispatcher()) {
        val prepared = prepare(DeliveredStatusProbeError())

        prepared.service.handleAcknowledgement(prepared.ack, tripTime = 250)

        val line = prepared.recorded.single { it.message.contains("Failed to write delivered status") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("delivered status probe"))
        assertFinishedAfterFault(prepared)
    }

    @Test
    fun aCancelledDeliveredStatusWriteStopsTheAcknowledgement() = runTest(UnconfinedTestDispatcher()) {
        val cancelled = CancellationException("superseded")
        val prepared = prepare(cancelled)

        val thrown = assertFailsWith<CancellationException> {
            prepared.service.handleAcknowledgement(prepared.ack, tripTime = 250)
        }

        assertSame(cancelled, thrown)
        assertTrue(prepared.recorded.none { it.message.contains("Failed to write delivered status") })
        assertEquals(MessageStatus.SENT, prepared.store.fetchMessage(prepared.id)?.status)
        assertEquals(0, prepared.store.contactStampCount)
        assertEquals(1, prepared.service.pendingAckCount())
        assertEquals(true, prepared.service.peekPendingAck(prepared.id)?.isDelivered)
        assertTrue(prepared.events.drain().isEmpty())
    }

    private suspend fun assertFinishedAfterFault(prepared: Prepared) {
        assertEquals(MessageStatus.SENT, prepared.store.fetchMessage(prepared.id)?.status)
        assertEquals(1, prepared.store.contactStampCount)
        assertEquals(0, prepared.service.pendingAckCount())
        assertEquals(listOf(prepared.id to MessageStatus.DELIVERED), prepared.events.drain().resolvedTo())
    }

    private suspend fun TestScope.prepare(failure: Throwable): Prepared {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = AckWriteStore().apply { ackWriteFailure = failure }
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
        val ack = ackBytes(0xEF, 0xBE, 0xAD, 0xDE)
        store.saveMessage(
            ChatFixtures.message(id = id, status = MessageStatus.SENT, contactID = ChatFixtures.CONTACT)
                .copy(ackCode = ack.ackCodeLeUInt32()),
        )
        service.setPendingAck(pendingAck(id, codes = setOf(ack.ackCodeLeUInt32()), ageSeconds = 10.0))
        return Prepared(store, service, recorded, id, ack, service.statusEvents())
    }
}

private class Prepared(
    val store: AckWriteStore,
    val service: MessageService,
    val recorded: MutableList<DebugLogEntryDto>,
    val id: Uuid,
    val ack: ByteArray,
    val events: ReceiveChannel<MessageStatusEvent>,
)

private class AckWriteStore(private val inner: InMemoryMessageStore = InMemoryMessageStore()) : MessageStore by inner {
    var ackWriteFailure: Throwable? = null
    var contactStampCount: Int = 0

    override suspend fun updateMessageAck(id: Uuid, ackCode: Long, status: MessageStatus, roundTripTime: Long?) {
        ackWriteFailure?.let { throw it }
        inner.updateMessageAck(id, ackCode, status, roundTripTime)
    }

    override suspend fun updateContactLastMessage(contactID: Uuid, dateEpochSeconds: Double) {
        contactStampCount += 1
        inner.updateContactLastMessage(contactID, dateEpochSeconds)
    }
}

private class DeliveredStatusProbeError : Error("delivered status probe")
