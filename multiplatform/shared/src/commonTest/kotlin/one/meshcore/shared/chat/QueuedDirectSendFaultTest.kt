package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A queued direct send after the pending row exists. Cancellation and every other failure, including
 * a thrown error, mark the row failed and keep the thrown instance. No Failed event is yielded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueuedDirectSendFaultTest {
    @Test
    fun aCancelledQueuedDirectSendKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("superseded")
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { selfPublicKeyError = { stopped } }
        val service = service(this, store, session)
        val contact = ChatFixtures.directContact()
        val pending = service.createPendingMessage("Hello", contact)
        val events = service.statusEvents()

        val thrown = assertFailsWith<CancellationException> {
            service.sendPendingDirectMessage(pending.id, contact)
        }

        assertSame(stopped, thrown)
        assertEquals(MESSAGE_ID, pending.id)
        assertFailedWithoutStatusEvent(store, service, session, events.drain())
    }

    @Test
    fun aQueuedDirectSendExceptionIsMarkedFailed() = runTest(UnconfinedTestDispatcher()) {
        val failure = QueuedDirectSendProbeException()
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { selfPublicKeyError = { failure } }
        val service = service(this, store, session)
        val contact = ChatFixtures.directContact()
        service.createPendingMessage("Hello", contact)
        val events = service.statusEvents()

        val thrown = assertFailsWith<QueuedDirectSendProbeException> {
            service.sendPendingDirectMessage(MESSAGE_ID, contact)
        }

        assertSame(failure, thrown)
        assertFailedWithoutStatusEvent(store, service, session, events.drain())
    }

    @Test
    fun aQueuedDirectSendErrorIsMarkedFailed() = runTest(UnconfinedTestDispatcher()) {
        val probe = QueuedDirectSendProbeError()
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { selfPublicKeyError = { probe } }
        val service = service(this, store, session)
        val contact = ChatFixtures.directContact()
        service.createPendingMessage("Hello", contact)
        val events = service.statusEvents()

        val thrown = assertFailsWith<QueuedDirectSendProbeError> {
            service.sendPendingDirectMessage(MESSAGE_ID, contact)
        }

        assertSame(probe, thrown)
        assertFailedWithoutStatusEvent(store, service, session, events.drain())
    }

    private suspend fun assertFailedWithoutStatusEvent(
        store: InMemoryMessageStore,
        service: MessageService,
        session: FakeChatSession,
        drained: List<MessageStatusEvent>,
    ) {
        assertTrue(drained.failedIds().isEmpty())
        assertTrue(drained.resolvedTo().isEmpty())
        assertTrue(drained.resentIds().isEmpty())
        assertEquals(MessageStatus.FAILED, store.fetchMessage(MESSAGE_ID)?.status)
        assertNull(service.peekPendingAck(MESSAGE_ID))
        assertTrue(session.directSendAttempts.isEmpty())
    }

    private fun service(scope: CoroutineScope, store: InMemoryMessageStore, session: FakeChatSession) =
        testMessageService(session, store, scope, ids = SequentialIds())

    private companion object {
        val MESSAGE_ID = SequentialIds().next()
    }
}

private class QueuedDirectSendProbeException : Exception("db locked")

private class QueuedDirectSendProbeError : Error("probe")
