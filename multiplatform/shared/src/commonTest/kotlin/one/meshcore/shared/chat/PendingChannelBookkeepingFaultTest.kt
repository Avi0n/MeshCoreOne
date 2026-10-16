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
 * Post-status bookkeeping for a pending channel send. Cancellation keeps the thrown instance and is not
 * logged. Any other failure, including a thrown error, is logged and the send still returns. The row
 * stays sent because that write already committed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingChannelBookkeepingFaultTest {
    @Test
    fun aCancelledPendingBookkeepingKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("superseded")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val harness = harness(this, recorded) { stopped }
        val events = harness.service.statusEvents()

        val thrown = assertFailsWith<CancellationException> {
            harness.service.sendPendingChannelMessage(harness.messageID)
        }

        assertSame(stopped, thrown)
        assertEquals(MessageStatus.SENT, harness.inner.fetchMessage(harness.messageID)?.status)
        assertEquals(listOf(harness.messageID to MessageStatus.SENT), events.drain().resolvedTo())
        assertTrue(recorded.none { it.message.contains("post-status bookkeeping") })
    }

    @Test
    fun aPendingBookkeepingExceptionIsLogged() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val harness = harness(this, recorded) { PendingChannelBookkeepingProbeException() }
        val events = harness.service.statusEvents()

        harness.service.sendPendingChannelMessage(harness.messageID)

        assertEquals(MessageStatus.SENT, harness.inner.fetchMessage(harness.messageID)?.status)
        assertEquals(listOf(harness.messageID to MessageStatus.SENT), events.drain().resolvedTo())
        assertTrue(recorded.any { it.message.contains("status=.sent already committed: db locked") })
    }

    @Test
    fun aPendingBookkeepingErrorIsLogged() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val harness = harness(this, recorded) { PendingChannelBookkeepingProbeError() }
        val events = harness.service.statusEvents()

        harness.service.sendPendingChannelMessage(harness.messageID)

        assertEquals(MessageStatus.SENT, harness.inner.fetchMessage(harness.messageID)?.status)
        assertEquals(listOf(harness.messageID to MessageStatus.SENT), events.drain().resolvedTo())
        assertTrue(recorded.any { it.message.contains("status=.sent already committed: probe") })
    }

    private suspend fun harness(
        scope: CoroutineScope,
        recorded: MutableList<DebugLogEntryDto>,
        fault: () -> Throwable,
    ): Harness {
        val inner = InMemoryMessageStore()
        inner.seedChannel(ChatFixtures.RADIO, CHANNEL_INDEX, ChatFixtures.freshUuid())
        val service = testMessageService(
            FakeChatSession(),
            ChannelStampFaultStore(inner, fault),
            scope,
            logger = PersistentLogger(
                scope = scope,
                category = "MessageService",
                record = { recorded += it },
            ),
        )
        val message = service.createPendingChannelMessage("Hello channel", CHANNEL_INDEX, ChatFixtures.RADIO)
        return Harness(inner, service, message.id)
    }

    private class Harness(val inner: InMemoryMessageStore, val service: MessageService, val messageID: Uuid)

    private companion object {
        const val CHANNEL_INDEX = 0
    }
}

private class ChannelStampFaultStore(inner: InMemoryMessageStore, private val fault: () -> Throwable) :
    MessageStore by inner {
    override suspend fun updateChannelLastMessage(channelID: Uuid, dateEpochSeconds: Double): Unit = throw fault()
}

private class PendingChannelBookkeepingProbeException : Exception("db locked")

private class PendingChannelBookkeepingProbeError : Error("probe")
