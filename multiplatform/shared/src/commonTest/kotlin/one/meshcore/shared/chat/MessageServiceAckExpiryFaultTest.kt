package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * An expiry-check fault is logged and the checker keeps running. Cancellation stops it and is not logged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageServiceAckExpiryFaultTest {
    @Test
    fun anOrdinaryExpiryFaultKeepsChecking() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = ExpiryWriteStore().apply { statusWriteFailure = IllegalStateException("db locked") }
        val service = expiryService(backgroundScope, store, recorded)
        val id = seedExpired(store, service)

        service.startAckExpiryChecking(CHECK_INTERVAL_SECONDS)
        val checker = checkerJob()
        advanceOneCheck()

        val line = recorded.single { it.message.contains("ACK expiry check failed") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals("MessageService", line.category)
        assertTrue(line.message.contains("db locked"))
        assertEquals(MessageStatus.SENT, store.fetchMessage(id)?.status)
        assertTrue(checker.isActive)

        store.statusWriteFailure = null
        advanceOneCheck()
        assertEquals(MessageStatus.FAILED, store.fetchMessage(id)?.status)
        assertTrue(checker.isActive)
    }

    @Test
    fun anExpiryErrorKeepsChecking() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = ExpiryWriteStore().apply { statusWriteFailure = AckExpiryProbeError() }
        val service = expiryService(backgroundScope, store, recorded)
        val id = seedExpired(store, service)

        service.startAckExpiryChecking(CHECK_INTERVAL_SECONDS)
        val checker = checkerJob()
        advanceOneCheck()

        val line = recorded.single { it.message.contains("ACK expiry check failed") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("ack expiry probe"))
        assertEquals(MessageStatus.SENT, store.fetchMessage(id)?.status)
        assertTrue(checker.isActive)

        store.statusWriteFailure = null
        advanceOneCheck()
        assertEquals(MessageStatus.FAILED, store.fetchMessage(id)?.status)
    }

    @Test
    fun aCancelledExpiryCheckStopsTheChecker() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val cancelled = CancellationException("superseded")
        val store = ExpiryWriteStore().apply { statusWriteFailure = cancelled }
        val service = expiryService(backgroundScope, store, recorded)
        val id = seedExpired(store, service)

        service.startAckExpiryChecking(CHECK_INTERVAL_SECONDS)
        val job = checkerJob()
        var completion: Throwable? = null
        job.invokeOnCompletion { completion = it }
        advanceOneCheck()

        assertSame(cancelled, completion)
        assertFalse(job.isActive)
        assertTrue(recorded.none { it.message.contains("ACK expiry check failed") })
        assertEquals(MessageStatus.SENT, store.fetchMessage(id)?.status)

        store.statusWriteFailure = null
        advanceOneCheck()
        assertEquals(MessageStatus.SENT, store.fetchMessage(id)?.status)
    }

    private fun TestScope.advanceOneCheck() {
        advanceTimeBy(CHECK_INTERVAL_MILLIS)
        runCurrent()
    }

    private fun TestScope.checkerJob(): Job = backgroundScope.coroutineContext.job.children.single()

    private suspend fun seedExpired(store: ExpiryWriteStore, service: MessageService): Uuid {
        val id = ChatFixtures.freshUuid()
        store.saveMessage(ChatFixtures.message(id = id, status = MessageStatus.SENT))
        service.setPendingAck(
            pendingAck(id, ageSeconds = EXPIRED_AGE_SECONDS, timeoutSeconds = EXPIRED_TIMEOUT_SECONDS),
        )
        return id
    }

    private fun expiryService(
        scope: CoroutineScope,
        store: MessageStore,
        recorded: MutableList<DebugLogEntryDto>,
    ): MessageService = testMessageService(
        FakeChatSession(),
        store,
        scope,
        logger = PersistentLogger(
            scope = scope,
            category = "MessageService",
            record = { recorded += it },
        ),
    )
}

private const val CHECK_INTERVAL_SECONDS = 1.0
private const val CHECK_INTERVAL_MILLIS = 1_000L
private const val EXPIRED_AGE_SECONDS = 60.0
private const val EXPIRED_TIMEOUT_SECONDS = 30.0

private class ExpiryWriteStore(private val inner: InMemoryMessageStore = InMemoryMessageStore()) :
    MessageStore by inner {
    var statusWriteFailure: Throwable? = null

    override suspend fun updateMessageStatusUnlessDelivered(id: Uuid, status: MessageStatus): Boolean {
        statusWriteFailure?.let { throw it }
        return inner.updateMessageStatusUnlessDelivered(id, status)
    }
}

private class AckExpiryProbeError : Error("ack expiry probe")
