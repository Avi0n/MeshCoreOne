package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A channel-cache update fault is logged and does not fail the caller. Cancellation keeps the thrown
 * instance and does not log.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelRxCacheApplyFaultTest {
    @Test
    fun aCancelledUpdateRethrowsTheSameException() = runTest(UnconfinedTestDispatcher()) {
        val cancelled = CancellationException("superseded")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val refresh = refresh(this, recorded) { throw cancelled }

        val thrown = assertFailsWith<CancellationException> { refresh.apply(emptyList()) }

        assertSame(cancelled, thrown)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun anUpdateErrorIsLoggedAndDoesNotFailTheCaller() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val refresh = refresh(this, recorded) { throw RxCacheApplyProbeError() }

        refresh.apply(emptyList())

        val line = recorded.single()
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("Failed to refresh RxLogService channel cache"))
        assertTrue(line.message.contains("probe"))
    }
}

private class RxCacheApplyProbeError : Error("probe")

private fun refresh(
    scope: CoroutineScope,
    recorded: MutableList<DebugLogEntryDto>,
    update: suspend (List<ChannelDto>) -> Unit,
) = ChannelRxCacheRefresh(
    store = InMemoryChannelStore(),
    rxCache = ChannelListSink { channels -> update(channels) },
    logger = PersistentLogger(scope = scope, category = "ChannelService", record = { recorded += it }),
)
