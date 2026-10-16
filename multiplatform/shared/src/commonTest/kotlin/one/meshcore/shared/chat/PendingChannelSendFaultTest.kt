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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The send half of an already-created pending channel message. Cancellation and every other failure,
 * including a thrown error, mark the row failed and keep the thrown instance. Post-status bookkeeping
 * runs only after that send is accepted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingChannelSendFaultTest {
    @Test
    fun aCancelledPendingChannelSendKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("superseded")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { directSendError = { stopped } }
        val service = loggedMessageService(this, session, store, recorded)
        val message = service.createPendingChannelMessage("Hello channel", CHANNEL_INDEX, ChatFixtures.RADIO)
        val events = service.statusEvents()

        val thrown = assertFailsWith<CancellationException> {
            service.sendPendingChannelMessage(message.id)
        }
        val drained = events.drain()

        assertSame(stopped, thrown)
        assertEquals(MessageStatus.FAILED, store.fetchMessage(message.id)?.status)
        assertTrue(drained.failedIds().isEmpty())
        assertTrue(drained.resolvedTo().isEmpty())
        assertTrue(recorded.none { it.message.contains("post-status bookkeeping") })
    }

    @Test
    fun aPendingChannelSendExceptionIsRethrown() = runTest(UnconfinedTestDispatcher()) {
        val failure = PendingChannelSendProbeException()
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { directSendError = { failure } }
        val service = loggedMessageService(this, session, store, recorded)
        val message = service.createPendingChannelMessage("Hello channel", CHANNEL_INDEX, ChatFixtures.RADIO)

        val thrown = assertFailsWith<PendingChannelSendProbeException> {
            service.sendPendingChannelMessage(message.id)
        }

        assertSame(failure, thrown)
        assertEquals(MessageStatus.FAILED, store.fetchMessage(message.id)?.status)
        assertTrue(recorded.none { it.message.contains("post-status bookkeeping") })
    }

    @Test
    fun aPendingChannelSendErrorIsRethrown() = runTest(UnconfinedTestDispatcher()) {
        val probe = PendingChannelSendProbeError()
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { directSendError = { probe } }
        val service = loggedMessageService(this, session, store, recorded)
        val message = service.createPendingChannelMessage("Hello channel", CHANNEL_INDEX, ChatFixtures.RADIO)

        val thrown = assertFailsWith<PendingChannelSendProbeError> {
            service.sendPendingChannelMessage(message.id)
        }

        assertSame(probe, thrown)
        assertEquals(MessageStatus.FAILED, store.fetchMessage(message.id)?.status)
        assertTrue(recorded.none { it.message.contains("post-status bookkeeping") })
    }

    private companion object {
        const val CHANNEL_INDEX = 0
    }
}

private class PendingChannelSendProbeException : Exception("db locked")

private class PendingChannelSendProbeError : Error("probe")
