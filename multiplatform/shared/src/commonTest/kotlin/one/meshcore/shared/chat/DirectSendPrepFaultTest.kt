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
 * Setup before a one-shot direct radio send. Cancellation and every other failure, including a thrown
 * error, mark the row failed once and keep the thrown instance. The radio send is not attempted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DirectSendPrepFaultTest {
    @Test
    fun aCancelledDirectSendPrepKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("superseded")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { selfPublicKeyError = { stopped } }
        val service = loggedMessageService(this, session, store, recorded)
        val events = service.statusEvents()

        val thrown = assertFailsWith<CancellationException> {
            service.sendDirectMessage("Hello", ChatFixtures.directContact())
        }

        assertSame(stopped, thrown)
        assertFailedBeforeRadioSend(store, service, session, events.drain(), recorded)
    }

    @Test
    fun aDirectSendPrepExceptionIsRethrown() = runTest(UnconfinedTestDispatcher()) {
        val failure = DirectSendPrepProbeException()
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { selfPublicKeyError = { failure } }
        val service = loggedMessageService(this, session, store, recorded)
        val events = service.statusEvents()

        val thrown = assertFailsWith<DirectSendPrepProbeException> {
            service.sendDirectMessage("Hello", ChatFixtures.directContact())
        }

        assertSame(failure, thrown)
        assertFailedBeforeRadioSend(store, service, session, events.drain(), recorded)
    }

    @Test
    fun aDirectSendPrepErrorIsRethrown() = runTest(UnconfinedTestDispatcher()) {
        val probe = DirectSendPrepProbeError()
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { selfPublicKeyError = { probe } }
        val service = loggedMessageService(this, session, store, recorded)
        val events = service.statusEvents()

        val thrown = assertFailsWith<DirectSendPrepProbeError> {
            service.sendDirectMessage("Hello", ChatFixtures.directContact())
        }

        assertSame(probe, thrown)
        assertFailedBeforeRadioSend(store, service, session, events.drain(), recorded)
    }

    @Test
    fun aMissingSelfKeyMarksTheRowFailed() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { selfPublicKey = null }
        val service = loggedMessageService(this, session, store, recorded)
        val events = service.statusEvents()

        assertFailsWith<MessageServiceException.NotConnected> {
            service.sendDirectMessage("Hello", ChatFixtures.directContact())
        }

        assertFailedBeforeRadioSend(store, service, session, events.drain(), recorded)
    }

    private suspend fun assertFailedBeforeRadioSend(
        store: InMemoryMessageStore,
        service: MessageService,
        session: FakeChatSession,
        drained: List<MessageStatusEvent>,
        recorded: List<DebugLogEntryDto>,
    ) {
        assertEquals(listOf(MESSAGE_ID), drained.failedIds())
        assertTrue(drained.resolvedTo().isEmpty())
        assertEquals(MessageStatus.FAILED, store.fetchMessage(MESSAGE_ID)?.status)
        assertNull(service.peekPendingAck(MESSAGE_ID))
        assertTrue(session.directSendAttempts.isEmpty())
        assertTrue(recorded.none { it.message.contains("DM post-send bookkeeping") })
    }

    private companion object {
        val MESSAGE_ID = SequentialIds().next()
    }
}

private class DirectSendPrepProbeException : Exception("db locked")

private class DirectSendPrepProbeError : Error("probe")
