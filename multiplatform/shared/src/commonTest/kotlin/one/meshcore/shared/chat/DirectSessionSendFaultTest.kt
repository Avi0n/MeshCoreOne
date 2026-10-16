package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.settings.DebugLogEntryDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The radio send inside a one-shot direct message. Cancellation and every other failure, including a
 * thrown error, drop the speculative ACK, report failed once, and keep the thrown instance. Post-send
 * bookkeeping runs only after the radio accepts the send.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DirectSessionSendFaultTest {
    @Test
    fun aCancelledDirectSessionSendKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("superseded")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { directSendError = { stopped } }
        val service = loggedMessageService(this, session, store, recorded)
        val events = service.statusEvents()

        val thrown = assertFailsWith<CancellationException> {
            service.sendDirectMessage("Hello", ChatFixtures.directContact())
        }
        val drained = events.drain()

        assertSame(stopped, thrown)
        assertEquals(listOf(MESSAGE_ID), drained.failedIds())
        assertTrue(drained.resolvedTo().isEmpty())
        assertEquals(MessageStatus.FAILED, store.fetchMessage(MESSAGE_ID)?.status)
        assertNull(service.peekPendingAck(MESSAGE_ID))
        assertTrue(recorded.none { it.message.contains("DM post-send bookkeeping") })
    }

    @Test
    fun aDirectSessionSendExceptionIsRethrown() = runTest(UnconfinedTestDispatcher()) {
        val failure = DirectSessionSendProbeException()
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { directSendError = { failure } }
        val service = loggedMessageService(this, session, store, recorded)
        val events = service.statusEvents()

        val thrown = assertFailsWith<DirectSessionSendProbeException> {
            service.sendDirectMessage("Hello", ChatFixtures.directContact())
        }

        assertSame(failure, thrown)
        assertEquals(listOf(MESSAGE_ID), events.drain().failedIds())
        assertEquals(MessageStatus.FAILED, store.fetchMessage(MESSAGE_ID)?.status)
        assertNull(service.peekPendingAck(MESSAGE_ID))
        assertTrue(recorded.none { it.message.contains("DM post-send bookkeeping") })
    }

    @Test
    fun aDirectSessionSendErrorIsRethrown() = runTest(UnconfinedTestDispatcher()) {
        val probe = DirectSessionSendProbeError()
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { directSendError = { probe } }
        val service = loggedMessageService(this, session, store, recorded)
        val events = service.statusEvents()

        val thrown = assertFailsWith<DirectSessionSendProbeError> {
            service.sendDirectMessage("Hello", ChatFixtures.directContact())
        }

        assertSame(probe, thrown)
        assertEquals(listOf(MESSAGE_ID), events.drain().failedIds())
        assertEquals(MessageStatus.FAILED, store.fetchMessage(MESSAGE_ID)?.status)
        assertNull(service.peekPendingAck(MESSAGE_ID))
        assertTrue(recorded.none { it.message.contains("DM post-send bookkeeping") })
    }

    private companion object {
        val MESSAGE_ID = SequentialIds().next()
    }
}

private class DirectSessionSendProbeException : Exception("db locked")

private class DirectSessionSendProbeError : Error("probe")
