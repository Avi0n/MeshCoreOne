package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Post-send bookkeeping on the one-shot channel send. Cancellation keeps the thrown instance and is not
 * logged. Any other failure, including a thrown error, is logged and the send still returns. The row
 * stays pending because the status write itself is what failed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InlineChannelBookkeepingFaultTest {
    @Test
    fun aCancelledInlineBookkeepingKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("superseded")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val inner = InMemoryMessageStore()
        val service = service(this, recorded, StatusFaultStore(inner) { stopped })

        val thrown = assertFailsWith<CancellationException> {
            service.sendChannelMessage("Hello channel", CHANNEL_INDEX, ChatFixtures.RADIO)
        }

        assertSame(stopped, thrown)
        assertEquals(MessageStatus.PENDING, inner.fetchMessage(MESSAGE_ID)?.status)
        assertTrue(recorded.none { it.message.contains("broadcast already out") })
    }

    @Test
    fun anInlineBookkeepingExceptionIsLogged() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val inner = InMemoryMessageStore()
        val service = service(
            this,
            recorded,
            StatusFaultStore(inner) { InlineChannelBookkeepingProbeException() },
        )
        val events = service.statusEvents()

        val sent = service.sendChannelMessage("Hello channel", CHANNEL_INDEX, ChatFixtures.RADIO)
        val drained = events.drain()

        assertEquals(MESSAGE_ID, sent.first)
        assertEquals(MessageStatus.PENDING, inner.fetchMessage(MESSAGE_ID)?.status)
        assertTrue(drained.failedIds().isEmpty())
        assertTrue(drained.resolvedTo().isEmpty())
        assertTrue(recorded.any { it.message.contains("broadcast already out: db locked") })
    }

    @Test
    fun anInlineBookkeepingErrorIsLogged() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val inner = InMemoryMessageStore()
        val service = service(
            this,
            recorded,
            StatusFaultStore(inner) { InlineChannelBookkeepingProbeError() },
        )
        val events = service.statusEvents()

        val sent = service.sendChannelMessage("Hello channel", CHANNEL_INDEX, ChatFixtures.RADIO)

        assertEquals(MESSAGE_ID, sent.first)
        assertEquals(MessageStatus.PENDING, inner.fetchMessage(MESSAGE_ID)?.status)
        assertTrue(events.drain().resolvedTo().isEmpty())
        assertTrue(recorded.any { it.message.contains("broadcast already out: probe") })
    }

    private fun service(
        scope: CoroutineScope,
        recorded: MutableList<DebugLogEntryDto>,
        store: MessageStore,
    ): MessageService = testMessageService(
        FakeChatSession(),
        store,
        scope,
        ids = SequentialIds(),
        logger = PersistentLogger(
            scope = scope,
            category = "MessageService",
            record = { recorded += it },
        ),
    )

    private companion object {
        const val CHANNEL_INDEX = 0
        val MESSAGE_ID = SequentialIds().next()
    }
}

private class StatusFaultStore(inner: InMemoryMessageStore, private val fault: () -> Throwable) :
    MessageStore by inner {
    override suspend fun updateMessageStatus(id: Uuid, status: MessageStatus): Unit = throw fault()
}

private class InlineChannelBookkeepingProbeException : Exception("db locked")

private class InlineChannelBookkeepingProbeError : Error("probe")
