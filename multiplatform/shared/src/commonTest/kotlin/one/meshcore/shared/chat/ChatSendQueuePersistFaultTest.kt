package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Enqueue, retry, and drain persists keep distinct failure policies and the same cancellation. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendQueuePersistFaultTest {
    @Test
    fun aCancelledEnqueueRethrowsWithoutLogging() = runTest(UnconfinedTestDispatcher()) {
        val cancelled = CancellationException("superseded")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val queue = queue(this, recorded, FakeSendQueueStore().apply { insertError = { cancelled } })

        val thrown = assertFailsWith<CancellationException> {
            queue.enqueueDM(DirectMessageEnvelope(MESSAGE, CONTACT))
        }

        assertSame(cancelled, thrown)
        assertTrue(recorded.none { it.message.contains("Persisting envelope failed") })
    }

    @Test
    fun anEnqueueErrorIsLoggedAndWrapped() = runTest(UnconfinedTestDispatcher()) {
        val probe = SendQueuePersistProbeError()
        val recorded = mutableListOf<DebugLogEntryDto>()
        val queue = queue(this, recorded, FakeSendQueueStore().apply { insertError = { probe } })

        val thrown = assertFailsWith<ChatSendQueueServiceException.PersistFailed> {
            queue.enqueueDM(DirectMessageEnvelope(MESSAGE, CONTACT))
        }

        assertSame(probe, thrown.cause)
        val line = recorded.single { it.message.contains("Persisting envelope failed") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("probe"))
    }

    @Test
    fun aCancelledRetryRethrowsWithoutLogging() = runTest(UnconfinedTestDispatcher()) {
        val cancelled = CancellationException("superseded")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val queue = queue(this, recorded, FakeSendQueueStore().apply { replaceError = { cancelled } })

        val thrown = assertFailsWith<CancellationException> {
            queue.retryDM(DirectMessageEnvelope(MESSAGE, CONTACT))
        }

        assertSame(cancelled, thrown)
        assertTrue(recorded.none { it.message.contains("Persisting envelope failed") })
    }

    @Test
    fun aRetryFailureIsWrappedWithoutTheEnqueueLog() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val queue = queue(
            this,
            recorded,
            FakeSendQueueStore().apply { replaceError = { IllegalStateException("disk full") } },
        )

        val thrown = assertFailsWith<ChatSendQueueServiceException.PersistFailed> {
            queue.retryDM(DirectMessageEnvelope(MESSAGE, CONTACT))
        }

        assertTrue(thrown.cause is IllegalStateException)
        assertEquals("disk full", thrown.cause?.message)
        assertTrue(recorded.none { it.message.contains("Persisting envelope failed") })
    }

    @Test
    fun aRetryErrorIsWrappedWithoutTheEnqueueLog() = runTest(UnconfinedTestDispatcher()) {
        val probe = SendQueuePersistProbeError()
        val recorded = mutableListOf<DebugLogEntryDto>()
        val queue = queue(this, recorded, FakeSendQueueStore().apply { replaceError = { probe } })

        val thrown = assertFailsWith<ChatSendQueueServiceException.PersistFailed> {
            queue.retryDM(DirectMessageEnvelope(MESSAGE, CONTACT))
        }

        assertSame(probe, thrown.cause)
        assertTrue(recorded.none { it.message.contains("Persisting envelope failed") })
    }

    @Test
    fun aCancelledDrainPersistRethrowsWithoutLogging() = runTest(UnconfinedTestDispatcher()) {
        val cancelled = CancellationException("superseded")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val queue = queue(this, recorded, FakeSendQueueStore())

        val thrown = assertFailsWith<CancellationException> {
            queue.persistDrainState { throw cancelled }
        }

        assertSame(cancelled, thrown)
        assertTrue(recorded.none { it.message.contains("Failed to persist send-queue drain state") })
    }

    @Test
    fun anErrorDrainPersistIsLoggedAndDoesNotThrow() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val queue = queue(this, recorded, FakeSendQueueStore())

        queue.persistDrainState { throw SendQueuePersistProbeError() }

        val line = recorded.single { it.message.contains("Failed to persist send-queue drain state") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("probe"))
    }

    private fun queue(
        scope: CoroutineScope,
        recorded: MutableList<DebugLogEntryDto>,
        store: FakeSendQueueStore,
    ): ChatSendQueueService = loggedSendQueue(scope, recorded, store, RADIO, NOW)

    private companion object {
        const val NOW: Double = 1_700_000_000.0
        val RADIO: Uuid = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
        val CONTACT: Uuid = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")
        val MESSAGE: Uuid = Uuid.normalized("CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC")
    }
}

private class SendQueuePersistProbeError : Error("probe")
