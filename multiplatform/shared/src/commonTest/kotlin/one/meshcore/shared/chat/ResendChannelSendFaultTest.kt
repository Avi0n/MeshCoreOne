package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
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
 * The send half of a channel resend. Cancellation and every other failure, including a thrown error,
 * mark the row failed and keep the thrown instance. Repeat bookkeeping runs only after that send is
 * accepted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResendChannelSendFaultTest {
    @Test
    fun aCancelledChannelResendKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("superseded")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { directSendError = { stopped } }
        val service = loggedMessageService(this, session, store, recorded)
        val id = savedFailedChannel(store, PRIOR_HEARD_REPEATS, PRIOR_SEND_COUNT)
        val events = service.statusEvents()

        val thrown = assertFailsWith<CancellationException> { service.resendChannelMessage(id) }

        assertSame(stopped, thrown)
        assertUnchangedFailedRow(store, id)
        assertTrue(events.drain().resentIds().isEmpty())
        assertTrue(recorded.none { it.message.contains("Resend post-status bookkeeping") })
    }

    @Test
    fun aChannelResendExceptionIsRethrown() = runTest(UnconfinedTestDispatcher()) {
        val failure = ResendChannelSendProbeException()
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { directSendError = { failure } }
        val service = loggedMessageService(this, session, store, recorded)
        val id = savedFailedChannel(store, PRIOR_HEARD_REPEATS, PRIOR_SEND_COUNT)

        val thrown = assertFailsWith<ResendChannelSendProbeException> { service.resendChannelMessage(id) }

        assertSame(failure, thrown)
        assertUnchangedFailedRow(store, id)
        assertTrue(recorded.none { it.message.contains("Resend post-status bookkeeping") })
    }

    @Test
    fun aChannelResendErrorIsRethrown() = runTest(UnconfinedTestDispatcher()) {
        val probe = ResendChannelSendProbeError()
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { directSendError = { probe } }
        val service = loggedMessageService(this, session, store, recorded)
        val id = savedFailedChannel(store, PRIOR_HEARD_REPEATS, PRIOR_SEND_COUNT)

        val thrown = assertFailsWith<ResendChannelSendProbeError> { service.resendChannelMessage(id) }

        assertSame(probe, thrown)
        assertUnchangedFailedRow(store, id)
        assertTrue(recorded.none { it.message.contains("Resend post-status bookkeeping") })
    }

    private suspend fun assertUnchangedFailedRow(store: InMemoryMessageStore, id: Uuid) {
        val stored = store.fetchMessage(id)
        assertEquals(MessageStatus.FAILED, stored?.status)
        assertEquals(PRIOR_HEARD_REPEATS, stored?.heardRepeats)
        assertEquals(PRIOR_SEND_COUNT, stored?.sendCount)
    }

    private companion object {
        const val PRIOR_HEARD_REPEATS = 3
        const val PRIOR_SEND_COUNT = 1
    }
}

private class ResendChannelSendProbeException : Exception("db locked")

private class ResendChannelSendProbeError : Error("probe")
