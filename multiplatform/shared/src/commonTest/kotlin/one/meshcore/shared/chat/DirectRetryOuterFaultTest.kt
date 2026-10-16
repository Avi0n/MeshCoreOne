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
 * The retry-loop send after the pending row is saved. Cancellation and every other failure, including
 * a thrown error, mark the row failed once and keep the thrown instance. The radio send is not attempted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DirectRetryOuterFaultTest {
    @Test
    fun aCancelledDirectRetryKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("superseded")
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { selfPublicKeyError = { stopped } }
        val service = service(this, store, session)
        val events = service.statusEvents()

        val thrown = assertFailsWith<CancellationException> {
            service.sendMessageWithRetry("Hello", ChatFixtures.directContact())
        }

        assertSame(stopped, thrown)
        assertFailedOnceBeforeRadioSend(store, service, session, events.drain())
    }

    @Test
    fun aDirectRetryExceptionIsMarkedFailed() = runTest(UnconfinedTestDispatcher()) {
        val failure = DirectRetryOuterProbeException()
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { selfPublicKeyError = { failure } }
        val service = service(this, store, session)
        val events = service.statusEvents()

        val thrown = assertFailsWith<DirectRetryOuterProbeException> {
            service.sendMessageWithRetry("Hello", ChatFixtures.directContact())
        }

        assertSame(failure, thrown)
        assertFailedOnceBeforeRadioSend(store, service, session, events.drain())
    }

    @Test
    fun aDirectRetryErrorIsMarkedFailed() = runTest(UnconfinedTestDispatcher()) {
        val probe = DirectRetryOuterProbeError()
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { selfPublicKeyError = { probe } }
        val service = service(this, store, session)
        val events = service.statusEvents()

        val thrown = assertFailsWith<DirectRetryOuterProbeError> {
            service.sendMessageWithRetry("Hello", ChatFixtures.directContact())
        }

        assertSame(probe, thrown)
        assertFailedOnceBeforeRadioSend(store, service, session, events.drain())
    }

    private suspend fun assertFailedOnceBeforeRadioSend(
        store: InMemoryMessageStore,
        service: MessageService,
        session: FakeChatSession,
        drained: List<MessageStatusEvent>,
    ) {
        assertEquals(listOf(MESSAGE_ID), drained.failedIds())
        assertTrue(drained.resolvedTo().isEmpty())
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

private class DirectRetryOuterProbeException : Exception("db locked")

private class DirectRetryOuterProbeError : Error("probe")
