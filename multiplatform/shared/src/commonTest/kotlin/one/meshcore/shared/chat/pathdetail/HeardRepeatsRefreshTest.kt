package one.meshcore.shared.chat.pathdetail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.MessageRepeatDto
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A fetch fault while opening Repeat Details still returns an empty list and logs the fault.
 * Cancellation and an error leave that fetch.
 */
class HeardRepeatsRefreshTest {
    private class RefreshProbeError : Error("probe")

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun persistFaultReturnsEmptyAndLogs() = runTest(UnconfinedTestDispatcher()) {
        val messageID = ChatFixtures.freshUuid()
        val recorded = mutableListOf<DebugLogEntryDto>()
        val service = refreshService(recorded) { error("db locked") }

        assertTrue(service.refreshRepeats(messageID).isEmpty())
        val line = recorded.single { it.message.contains("Failed to fetch repeats") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("db locked"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aCancelledRefreshPropagates() = runTest(UnconfinedTestDispatcher()) {
        val messageID = ChatFixtures.freshUuid()
        val recorded = mutableListOf<DebugLogEntryDto>()
        val stopped = CancellationException("stopped")
        val service = refreshService(recorded) { throw stopped }

        val cancelled = assertFailsWith<CancellationException> { service.refreshRepeats(messageID) }

        assertSame(stopped, cancelled)
        assertTrue(recorded.none { it.message.contains("Failed to fetch repeats") })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aRefreshErrorPropagates() = runTest(UnconfinedTestDispatcher()) {
        val messageID = ChatFixtures.freshUuid()
        val recorded = mutableListOf<DebugLogEntryDto>()
        val probe = RefreshProbeError()
        val service = refreshService(recorded) { throw probe }

        val thrown = assertFailsWith<RefreshProbeError> { service.refreshRepeats(messageID) }
        assertSame(probe, thrown)
        assertTrue(recorded.none { it.message.contains("Failed to fetch repeats") })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.refreshService(
        recorded: MutableList<DebugLogEntryDto>,
        fetch: suspend (Uuid) -> List<MessageRepeatDto>,
    ): HeardRepeatsService {
        val backing = InMemoryHeardRepeatStore()
        val throwing = object : HeardRepeatStore by backing {
            override suspend fun fetchMessageRepeats(messageID: Uuid): List<MessageRepeatDto> = fetch(messageID)
        }
        return HeardRepeatsService(
            store = throwing,
            newRepeatId = { ChatFixtures.freshUuid() },
            logger = PersistentLogger(
                scope = this,
                category = "HeardRepeatsService",
                record = { recorded += it },
            ),
        )
    }
}
