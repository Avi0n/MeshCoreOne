package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The acknowledgement listener keeps collecting after a status-write fault. Cancellation stops it
 * and is not logged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageServiceAckListenerFaultTest {
    @Test
    fun anOrdinaryAcknowledgementFaultKeepsCollecting() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeChatSession()
        val store = OutgoingLookupStore().apply {
            outgoingLookupFailure = IllegalStateException("db locked")
        }
        val service = loggedMessageService(backgroundScope, session, store, recorded)
        service.startEventMonitoring()
        session.eventSubscriptionCount.first { it > 0 }

        session.emit(MeshEvent.Acknowledgement(ackBytes(0x11, 0x22, 0x33, 0x44)))

        val line = recorded.single { it.message.contains("Failed to write delivered status") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals("MessageService", line.category)
        assertTrue(line.message.contains("db locked"))
        assertTrue(service.eventListenerJob?.isActive == true)

        store.outgoingLookupFailure = null
        session.emit(MeshEvent.Acknowledgement(ackBytes(0x01, 0x02, 0x03, 0x04)))
        assertTrue(recorded.any { it.message.contains("unmatched ACK") && it.message.contains("code=01020304") })
    }

    @Test
    fun anAcknowledgementErrorKeepsCollecting() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeChatSession()
        val probe = AckListenerProbeError()
        val store = OutgoingLookupStore().apply { outgoingLookupFailure = probe }
        val service = loggedMessageService(backgroundScope, session, store, recorded)
        service.startEventMonitoring()
        session.eventSubscriptionCount.first { it > 0 }

        session.emit(MeshEvent.Acknowledgement(ackBytes(0xAA, 0xBB, 0xCC, 0xDD)))

        val line = recorded.single { it.message.contains("Failed to write delivered status") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("ack listener probe"))
        assertTrue(service.eventListenerJob?.isActive == true)

        store.outgoingLookupFailure = null
        session.emit(MeshEvent.Acknowledgement(ackBytes(0x0A, 0x0B, 0x0C, 0x0D)))
        assertTrue(recorded.any { it.message.contains("unmatched ACK") && it.message.contains("code=0A0B0C0D") })
    }

    @Test
    fun aCancelledAcknowledgementStopsTheListener() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeChatSession()
        val cancelled = CancellationException("superseded")
        val store = OutgoingLookupStore().apply { outgoingLookupFailure = cancelled }
        val service = loggedMessageService(backgroundScope, session, store, recorded)
        service.startEventMonitoring()
        val job = checkNotNull(service.eventListenerJob)
        var completion: Throwable? = null
        job.invokeOnCompletion { completion = it }
        session.eventSubscriptionCount.first { it > 0 }

        session.emit(MeshEvent.Acknowledgement(ackBytes(0x11, 0x22, 0x33, 0x44)))

        assertSame(cancelled, completion)
        assertFalse(recorded.any { it.message.contains("Failed to write delivered status") })
        assertFalse(job.isActive)

        store.outgoingLookupFailure = null
        session.emit(MeshEvent.Acknowledgement(ackBytes(0x01, 0x02, 0x03, 0x04)))
        assertFalse(recorded.any { it.message.contains("unmatched ACK") })
    }

    private class OutgoingLookupStore(private val inner: InMemoryMessageStore = InMemoryMessageStore()) :
        MessageStore by inner {
        var outgoingLookupFailure: Throwable? = null

        override suspend fun hasOutgoingSentDM(ackCode: Long): Boolean {
            outgoingLookupFailure?.let { throw it }
            return inner.hasOutgoingSentDM(ackCode)
        }
    }
}

private class AckListenerProbeError : Error("ack listener probe")
