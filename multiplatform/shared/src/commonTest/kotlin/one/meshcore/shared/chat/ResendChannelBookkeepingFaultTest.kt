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
 * Repeat bookkeeping after a channel resend is accepted. Cancellation keeps the thrown instance and is
 * not logged. Any other failure, including a thrown error, is logged, the row stays sent, and the resend
 * is still reported.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResendChannelBookkeepingFaultTest {
    @Test
    fun aCancelledResendBookkeepingKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("superseded")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val inner = InMemoryMessageStore()
        val service = service(this, SendCountFaultStore(inner) { stopped }, recorded)
        val id = savedFailedChannel(inner, PRIOR_HEARD_REPEATS, PRIOR_SEND_COUNT)
        val events = service.statusEvents()

        val thrown = assertFailsWith<CancellationException> { service.resendChannelMessage(id) }

        assertSame(stopped, thrown)
        assertSentWithPriorCounts(inner, id)
        assertTrue(events.drain().resentIds().isEmpty())
        assertTrue(recorded.none { it.message.contains("Resend post-status bookkeeping") })
    }

    @Test
    fun aResendBookkeepingExceptionIsLogged() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val inner = InMemoryMessageStore()
        val service = service(this, SendCountFaultStore(inner) { ResendChannelBookkeepingProbeException() }, recorded)
        val id = savedFailedChannel(inner, PRIOR_HEARD_REPEATS, PRIOR_SEND_COUNT)
        val events = service.statusEvents()

        service.resendChannelMessage(id)

        assertSentWithPriorCounts(inner, id)
        assertEquals(listOf(id), events.drain().resentIds())
        assertTrue(
            recorded.any {
                it.message.contains("Resend post-status bookkeeping") &&
                    it.message.contains("db locked")
            },
        )
    }

    @Test
    fun aResendBookkeepingErrorIsLogged() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val inner = InMemoryMessageStore()
        val service = service(this, SendCountFaultStore(inner) { ResendChannelBookkeepingProbeError() }, recorded)
        val id = savedFailedChannel(inner, PRIOR_HEARD_REPEATS, PRIOR_SEND_COUNT)
        val events = service.statusEvents()

        service.resendChannelMessage(id)

        assertSentWithPriorCounts(inner, id)
        assertEquals(listOf(id), events.drain().resentIds())
        assertTrue(
            recorded.any {
                it.message.contains("Resend post-status bookkeeping") && it.message.contains("probe")
            },
        )
    }

    private suspend fun assertSentWithPriorCounts(store: InMemoryMessageStore, id: Uuid) {
        val stored = store.fetchMessage(id)
        assertEquals(MessageStatus.SENT, stored?.status)
        assertEquals(PRIOR_HEARD_REPEATS, stored?.heardRepeats)
        assertEquals(PRIOR_SEND_COUNT, stored?.sendCount)
    }

    private fun service(scope: CoroutineScope, store: MessageStore, recorded: MutableList<DebugLogEntryDto>) =
        loggedMessageService(scope, FakeChatSession(), store, recorded)

    private companion object {
        const val PRIOR_HEARD_REPEATS = 3
        const val PRIOR_SEND_COUNT = 1
    }
}

private class SendCountFaultStore(inner: InMemoryMessageStore, private val fault: () -> Throwable) :
    MessageStore by inner {
    override suspend fun incrementMessageSendCount(id: Uuid): Int = throw fault()
}

private class ResendChannelBookkeepingProbeException : Exception("db locked")

private class ResendChannelBookkeepingProbeError : Error("probe")
