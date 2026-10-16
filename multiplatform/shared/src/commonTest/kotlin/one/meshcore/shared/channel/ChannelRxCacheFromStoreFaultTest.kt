package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ChannelDto
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
 * A channel-cache fetch fault is logged and does not update the cache. Cancellation keeps the thrown
 * instance and does not log.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelRxCacheFromStoreFaultTest {
    @Test
    fun aCancelledFetchRethrowsTheSameException() = runTest(UnconfinedTestDispatcher()) {
        val cancelled = CancellationException("superseded")
        val recorded = mutableListOf<DebugLogEntryDto>()
        var updates = 0
        val refresh = refresh(this, recorded, updates = { updates += 1 }) { throw cancelled }

        val thrown = assertFailsWith<CancellationException> { refresh.fromStore(ChannelTestIds.RADIO) }

        assertSame(cancelled, thrown)
        assertEquals(0, updates)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun aFetchErrorIsLoggedAndDoesNotUpdateTheCache() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        var updates = 0
        val refresh = refresh(this, recorded, updates = { updates += 1 }) {
            throw RxCacheFetchProbeError()
        }

        refresh.fromStore(ChannelTestIds.RADIO)

        assertEquals(0, updates)
        val line = recorded.single()
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("Failed to refresh RxLogService channel cache"))
        assertTrue(line.message.contains("probe"))
    }
}

private class RxCacheFetchProbeError : Error("probe")

private fun refresh(
    scope: CoroutineScope,
    recorded: MutableList<DebugLogEntryDto>,
    updates: () -> Unit,
    fetch: () -> List<ChannelDto>,
) = ChannelRxCacheRefresh(
    store = object : ChannelStore by InMemoryChannelStore() {
        override suspend fun fetchChannels(radioID: Uuid): List<ChannelDto> = fetch()
    },
    rxCache = ChannelListSink { updates() },
    logger = PersistentLogger(scope = scope, category = "ChannelService", record = { recorded += it }),
)
