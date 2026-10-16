package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Send-count bookkeeping after a direct resend is accepted. Cancellation keeps the thrown instance and
 * is not logged. Any other failure, including a thrown error, is logged and the resend still finishes.
 * The delivered row and its send count stay as they were.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DirectResendCountFaultTest {
    @Test
    fun aCancelledResendCountKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("superseded")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = DirectResendCountStore { stopped }
        val service = service(this, store, recorded)
        val id = seedDelivered(store, service)
        val events = service.statusEvents()

        val thrown = assertFailsWith<CancellationException> {
            service.resendDirectMessage(id, ChatFixtures.directContact())
        }

        assertSame(stopped, thrown)
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(id)?.status)
        assertEquals(1, store.fetchMessage(id)?.sendCount)
        assertTrue(events.drain().resentIds().isEmpty())
        assertTrue(recorded.none { it.message.contains("DM resend sendCount") })
    }

    @Test
    fun aResendCountExceptionIsLogged() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = DirectResendCountStore { DirectResendCountProbeException() }
        val service = service(this, store, recorded)
        val id = seedDelivered(store, service)
        val events = service.statusEvents()

        val resent = service.resendDirectMessage(id, ChatFixtures.directContact())

        assertEquals(id, resent.id)
        assertEquals(MessageStatus.DELIVERED, resent.status)
        assertEquals(1, store.fetchMessage(id)?.sendCount)
        assertEquals(listOf(id), events.drain().resentIds())
        assertTrue(recorded.any { it.message == dmResendSendCountBookkeepingFailLine(id, "db locked") })
    }

    @Test
    fun aResendCountErrorIsLogged() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = DirectResendCountStore { DirectResendCountProbeError() }
        val service = service(this, store, recorded)
        val id = seedDelivered(store, service)
        val events = service.statusEvents()

        val resent = service.resendDirectMessage(id, ChatFixtures.directContact())

        assertEquals(MessageStatus.DELIVERED, resent.status)
        assertEquals(1, store.fetchMessage(id)?.sendCount)
        assertEquals(listOf(id), events.drain().resentIds())
        assertTrue(recorded.any { it.message == dmResendSendCountBookkeepingFailLine(id, "probe") })
    }

    private suspend fun seedDelivered(store: DirectResendCountStore, service: MessageService): Uuid {
        val id = ChatFixtures.freshUuid()
        store.saveMessage(
            ChatFixtures.message(id = id, status = MessageStatus.DELIVERED, contactID = ChatFixtures.CONTACT)
                .copy(sendCount = 1),
        )
        service.setPendingAck(pendingAck(id, isDelivered = true))
        return id
    }

    private fun service(scope: CoroutineScope, store: MessageStore, recorded: MutableList<DebugLogEntryDto>) =
        loggedMessageService(scope, FakeChatSession(), store, recorded)
}

private class DirectResendCountStore(
    private val inner: InMemoryMessageStore = InMemoryMessageStore(),
    private val fault: () -> Throwable,
) : MessageStore by inner {
    override suspend fun incrementMessageSendCount(id: Uuid): Int = throw fault()
}

private class DirectResendCountProbeException : Exception("db locked")

private class DirectResendCountProbeError : Error("probe")
