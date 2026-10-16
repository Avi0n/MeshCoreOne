package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The one-shot channel send. Cancellation and every other failure, including a thrown error, mark the
 * row failed, report failed, and keep the thrown instance. Bookkeeping runs only after the send is
 * accepted.
 */
class InlineChannelSendFaultTest {
    @Test
    fun aCancelledInlineSendKeepsTheThrownInstance() = runTest {
        val stopped = CancellationException("superseded")
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { directSendError = { stopped } }
        val service = testMessageService(session, store, this, ids = SequentialIds())
        val events = service.statusEvents()

        val thrown = assertFailsWith<CancellationException> {
            service.sendChannelMessage("Hello channel", CHANNEL_INDEX, ChatFixtures.RADIO)
        }

        assertSame(stopped, thrown)
        val drained = events.drain()
        assertEquals(listOf(MESSAGE_ID), drained.failedIds())
        assertTrue(drained.resolvedTo().isEmpty())
        assertEquals(MessageStatus.FAILED, store.fetchMessage(MESSAGE_ID)?.status)
    }

    @Test
    fun aFailedSettlementDoesNotReplaceTheSendCancellation() = runTest {
        val cancelled = CancellationException("send cancelled")
        val backing = InMemoryMessageStore()
        val store = object : MessageStore by backing {
            override suspend fun updateMessageStatusUnlessDelivered(id: Uuid, status: MessageStatus): Boolean =
                error("settlement storage failure")
        }
        val session = FakeChatSession().apply { directSendError = { cancelled } }
        val service = testMessageService(session, store, this, ids = SequentialIds())

        val thrown = assertFailsWith<CancellationException> {
            service.sendChannelMessage("Hello channel", CHANNEL_INDEX, ChatFixtures.RADIO)
        }

        assertSame(cancelled, thrown)
        assertEquals("settlement storage failure", thrown.suppressedExceptions.single().message)
    }

    @Test
    fun anInlineSendExceptionIsRethrown() = runTest {
        val failure = InlineChannelSendProbeException()
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { directSendError = { failure } }
        val service = testMessageService(session, store, this, ids = SequentialIds())
        val events = service.statusEvents()

        val thrown = assertFailsWith<InlineChannelSendProbeException> {
            service.sendChannelMessage("Hello channel", CHANNEL_INDEX, ChatFixtures.RADIO)
        }

        assertSame(failure, thrown)
        assertEquals(listOf(MESSAGE_ID), events.drain().failedIds())
        assertEquals(MessageStatus.FAILED, store.fetchMessage(MESSAGE_ID)?.status)
    }

    @Test
    fun anInlineSendErrorIsRethrown() = runTest {
        val probe = InlineChannelSendProbeError()
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { directSendError = { probe } }
        val service = testMessageService(session, store, this, ids = SequentialIds())
        val events = service.statusEvents()

        val thrown = assertFailsWith<InlineChannelSendProbeError> {
            service.sendChannelMessage("Hello channel", CHANNEL_INDEX, ChatFixtures.RADIO)
        }

        assertSame(probe, thrown)
        assertEquals(listOf(MESSAGE_ID), events.drain().failedIds())
        assertEquals(MessageStatus.FAILED, store.fetchMessage(MESSAGE_ID)?.status)
    }

    private companion object {
        const val CHANNEL_INDEX = 0
        val MESSAGE_ID = SequentialIds().next()
    }
}

private class InlineChannelSendProbeException : Exception("dropped")

private class InlineChannelSendProbeError : Error("probe")
