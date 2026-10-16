package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Flood escalation inside the direct retry loop. Cancellation keeps the thrown instance and is not
 * logged. Any other reset failure, including a thrown error, is logged and the send continues.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetryFloodEscalateFaultTest {
    @Test
    fun aCancelledFloodEscalateKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("superseded")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { resetPathError = { stopped } }
        val service = service(this, store, session, recorded)
        val events = service.statusEvents()

        val thrown = assertFailsWith<CancellationException> {
            service.sendMessageWithRetry("Hello", ChatFixtures.directContact())
        }

        assertSame(stopped, thrown)
        assertEquals(listOf(MESSAGE_ID), events.drain().failedIds())
        assertEquals(MessageStatus.FAILED, store.fetchMessage(MESSAGE_ID)?.status)
        assertTrue(session.directSendAttempts.isEmpty())
        assertTrue(recorded.none { it.message.contains("Failed to reset path") })
    }

    @Test
    fun aFloodEscalateExceptionContinuesTheSend() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply {
            resetPathError = { RetryFloodProbeException() }
            ackArrives = true
        }
        val service = service(this, store, session, recorded)
        val events = service.statusEvents()

        val sent = service.sendMessageWithRetry("Hello", ChatFixtures.directContact())

        assertEquals(MESSAGE_ID, sent.id)
        assertEquals(MessageStatus.DELIVERED, sent.status)
        assertEquals(listOf(0), session.directSendAttempts)
        assertTrue(events.drain().routingChanges().isEmpty())
        assertTrue(recorded.any { it.message.contains("Failed to reset path: db locked, continuing...") })
    }

    @Test
    fun aFloodEscalateErrorContinuesTheSend() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply {
            resetPathError = { RetryFloodProbeError() }
            ackArrives = true
        }
        val service = service(this, store, session, recorded)

        val sent = service.sendMessageWithRetry("Hello", ChatFixtures.directContact())

        assertEquals(MessageStatus.DELIVERED, sent.status)
        assertEquals(listOf(0), session.directSendAttempts)
        assertTrue(recorded.any { it.message.contains("Failed to reset path: probe, continuing...") })
    }

    private fun service(
        scope: CoroutineScope,
        store: InMemoryMessageStore,
        session: FakeChatSession,
        recorded: MutableList<DebugLogEntryDto>,
    ) = testMessageService(
        session,
        store,
        scope,
        ids = SequentialIds(),
        config = floodImmediately(),
        logger = PersistentLogger(
            scope = scope,
            category = "MessageService",
            record = { recorded += it },
        ),
    )

    private fun floodImmediately() = MessageServiceConfig(maxAttempts = 2, floodAfter = 0)

    private companion object {
        val MESSAGE_ID = SequentialIds().next()
    }
}

private class RetryFloodProbeException : Exception("db locked")

private class RetryFloodProbeError : Error("probe")
