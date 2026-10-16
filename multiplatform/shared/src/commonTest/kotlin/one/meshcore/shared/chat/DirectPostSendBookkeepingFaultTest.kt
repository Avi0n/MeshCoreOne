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
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Bookkeeping after a one-shot direct send is accepted. Cancellation keeps the thrown instance and is
 * not logged. Any other failure, including a thrown error, is logged and the send still returns. The
 * row stays pending because the ACK write itself is what failed, and the pending ACK entry stays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DirectPostSendBookkeepingFaultTest {
    @Test
    fun aCancelledDirectBookkeepingKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("superseded")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val inner = InMemoryMessageStore()
        val service = service(this, AckFaultStore(inner) { stopped }, recorded)
        val events = service.statusEvents()

        val thrown = assertFailsWith<CancellationException> {
            service.sendDirectMessage("Hello", ChatFixtures.directContact())
        }

        assertSame(stopped, thrown)
        assertEquals(MessageStatus.PENDING, inner.fetchMessage(MESSAGE_ID)?.status)
        assertTrue(events.drain().failedIds().isEmpty())
        assertNotNull(service.peekPendingAck(MESSAGE_ID))
        assertTrue(recorded.none { it.message.contains("DM post-send bookkeeping") })
    }

    @Test
    fun aDirectBookkeepingExceptionIsLogged() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val inner = InMemoryMessageStore()
        val service = service(this, AckFaultStore(inner) { DirectBookkeepingProbeException() }, recorded)
        val events = service.statusEvents()

        val sent = service.sendDirectMessage("Hello", ChatFixtures.directContact())
        val drained = events.drain()

        assertEquals(MESSAGE_ID, sent.id)
        assertEquals(MessageStatus.PENDING, sent.status)
        assertEquals(MessageStatus.PENDING, inner.fetchMessage(MESSAGE_ID)?.status)
        assertTrue(drained.failedIds().isEmpty())
        assertTrue(drained.resolvedTo().isEmpty())
        assertNotNull(service.peekPendingAck(MESSAGE_ID))
        assertTrue(recorded.any { it.message.contains("send already accepted: db locked") })
    }

    @Test
    fun aDirectBookkeepingErrorIsLogged() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val inner = InMemoryMessageStore()
        val service = service(this, AckFaultStore(inner) { DirectBookkeepingProbeError() }, recorded)
        val events = service.statusEvents()

        val sent = service.sendDirectMessage("Hello", ChatFixtures.directContact())

        assertEquals(MESSAGE_ID, sent.id)
        assertEquals(MessageStatus.PENDING, inner.fetchMessage(MESSAGE_ID)?.status)
        assertTrue(events.drain().resolvedTo().isEmpty())
        assertNotNull(service.peekPendingAck(MESSAGE_ID))
        assertTrue(recorded.any { it.message.contains("send already accepted: probe") })
    }

    private fun service(scope: CoroutineScope, store: MessageStore, recorded: MutableList<DebugLogEntryDto>) =
        testMessageService(
            FakeChatSession(),
            store,
            scope,
            ids = SequentialIds(),
            logger = PersistentLogger(
                scope = scope,
                category = "MessageService",
                record = { recorded += it },
            ),
        )

    private companion object {
        val MESSAGE_ID = SequentialIds().next()
    }
}

private class AckFaultStore(inner: InMemoryMessageStore, private val fault: () -> Throwable) : MessageStore by inner {
    override suspend fun updateMessageAck(id: Uuid, ackCode: Long, status: MessageStatus, roundTripTime: Long?): Unit =
        throw fault()
}

private class DirectBookkeepingProbeException : Exception("db locked")

private class DirectBookkeepingProbeError : Error("probe")
