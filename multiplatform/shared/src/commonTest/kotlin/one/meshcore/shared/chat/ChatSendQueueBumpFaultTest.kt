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
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Attempt bump before a drain sends. A missing row is terminal. A bump fault, including a thrown
 * error, is logged and parked. Cancellation keeps the thrown instance and does not park.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendQueueBumpFaultTest {
    @Test
    fun aMissingRowIsTerminal() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()

        val result = queue(this, recorded, FakeSendQueueStore()).bumpedPreflight(MESSAGE, "DM")

        assertNull(result)
        assertTrue(recorded.none { it.message.contains("incrementPendingSendAttemptCount failed") })
    }

    @Test
    fun aBumpExceptionIsLoggedAndParks() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = FakeSendQueueStore().apply { pending.incrementFault = { IllegalStateException("db locked") } }
        val queue = queue(this, recorded, store)
        queue.triggers.fire()

        val parked = assertFailsWith<CancellationException> { queue.bumpedPreflight(MESSAGE, "DM") }

        assertEquals("send-queue park", parked.message)
        val line = recorded.single { it.message.contains("incrementPendingSendAttemptCount failed") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertTrue(line.message.contains("db locked"))
    }

    @Test
    fun aBumpErrorIsLoggedAndParks() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = FakeSendQueueStore().apply { pending.incrementFault = { throw BumpProbeError() } }
        val queue = queue(this, recorded, store)
        queue.triggers.fire()

        val parked = assertFailsWith<CancellationException> { queue.bumpedPreflight(MESSAGE, "DM") }

        assertEquals("send-queue park", parked.message)
        val line = recorded.single { it.message.contains("incrementPendingSendAttemptCount failed") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertTrue(line.message.contains("probe"))
    }

    @Test
    fun aCancelledBumpKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("superseded")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = FakeSendQueueStore().apply { pending.incrementFault = { stopped } }
        val queue = queue(this, recorded, store)

        val cancelled = assertFailsWith<CancellationException> { queue.bumpedPreflight(MESSAGE, "DM") }

        assertSame(stopped, cancelled)
        assertTrue(recorded.none { it.message.contains("incrementPendingSendAttemptCount failed") })
    }

    private fun queue(
        scope: CoroutineScope,
        recorded: MutableList<DebugLogEntryDto>,
        store: FakeSendQueueStore,
    ): ChatSendQueueService = loggedSendQueue(scope, recorded, store, RADIO, NOW)

    private companion object {
        const val NOW: Double = 1_700_000_000.0
        val RADIO: Uuid = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
        val MESSAGE: Uuid = Uuid.normalized("CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC")
    }
}

private class BumpProbeError : Error("probe")
