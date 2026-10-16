package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.meshcore.ChannelInfo
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
 * Post-sync diagnostics run on the list just saved. A failure is logged and the sync still finishes,
 * including the RX cache update. Cancellation aborts before that update. A thrown error stays logged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelDiagnosticsFaultTest {
    private val radioID = ChannelTestIds.RADIO

    @Test
    fun aCancelledDiagnosticsPassAbortsBeforeTheCacheUpdate() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val logs = mutableListOf<DebugLogEntryDto>()
        val cache = RecordingChannelListSink()
        val store = DiagnosticsFaultStore(InMemoryChannelStore())
        store.fault = stopped
        val service = service(store, logs, cache)

        val thrown = assertFailsWith<CancellationException> {
            service.syncChannels(radioID, maxChannels = 1, usePipelinedRead = false)
        }

        assertSame(stopped, thrown)
        assertEquals(listOf(PUBLIC_INDEX), service.getChannels(radioID).map { it.index })
        assertTrue(cache.updates.isEmpty())
        assertTrue(logs.none { it.message.contains(DIAGNOSTICS_FAILED) })
    }

    @Test
    fun aFailedDiagnosticsPassStillFinishesTheSync() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val cache = RecordingChannelListSink()
        val store = DiagnosticsFaultStore(InMemoryChannelStore())
        store.fault = DiagnosticsComputeFailure()
        val service = service(store, logs, cache)

        val result = service.syncChannels(radioID, maxChannels = 1, usePipelinedRead = false)

        assertEquals(1, result.channelsSynced)
        assertTrue(result.errors.isEmpty())
        assertEquals(listOf(PUBLIC_INDEX), service.getChannels(radioID).map { it.index })
        assertEquals(1, cache.updates.size)
        val line = logs.single { it.message.contains(DIAGNOSTICS_FAILED) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals(diagnosticsFailedLine(FAILURE_TEXT), line.message)
    }

    @Test
    fun aDiagnosticsErrorStaysLoggedAndTheSyncFinishes() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val cache = RecordingChannelListSink()
        val store = DiagnosticsFaultStore(InMemoryChannelStore())
        store.fault = DiagnosticsProbeError()
        val service = service(store, logs, cache)

        val result = service.syncChannels(radioID, maxChannels = 1, usePipelinedRead = false)

        assertEquals(1, result.channelsSynced)
        assertTrue(result.errors.isEmpty())
        assertEquals(listOf(PUBLIC_INDEX), service.getChannels(radioID).map { it.index })
        assertEquals(1, cache.updates.size)
        assertEquals(
            diagnosticsFailedLine(PROBE_TEXT),
            logs.single { it.message.contains(DIAGNOSTICS_FAILED) }.message,
        )
    }

    private fun TestScope.service(
        store: DiagnosticsFaultStore,
        logs: MutableList<DebugLogEntryDto>,
        cache: RecordingChannelListSink,
    ): ChannelService {
        val session = FakeChannelSession()
        session.slots[PUBLIC_INDEX] = channelInfo(PUBLIC_INDEX, name = "Public")
        return testChannelService(
            session = session,
            store = store,
            rxCache = cache,
            logger = PersistentLogger(
                scope = this,
                category = "ChannelSync",
                record = { logs += it },
            ),
        )
    }
}

private const val PUBLIC_INDEX = 0
private const val DIAGNOSTICS_FAILED = "Failed to compute post-sync channel diagnostics"
private const val FAILURE_TEXT = "disk"
private const val PROBE_TEXT = "probe"

private fun diagnosticsFailedLine(detail: String): String = "$DIAGNOSTICS_FAILED: $detail"

/**
 * The vacated-slot scan reads the saved list once. The diagnostics pass reads it again, and that
 * second read is the one that fails.
 */
private class DiagnosticsFaultList(private val inner: List<ChannelDto>, private val fault: Throwable) :
    List<ChannelDto> by inner {
    private var reads = 0

    override fun iterator(): Iterator<ChannelDto> {
        reads += 1
        if (reads > 1) throw fault
        return inner.iterator()
    }
}

private class DiagnosticsFaultStore(private val inner: InMemoryChannelStore) : ChannelStore by inner {
    var fault: Throwable? = null

    override suspend fun batchSaveChannels(
        radioID: Uuid,
        configured: List<ChannelInfo>,
        unconfiguredIndices: List<Int>,
        pruneBeyond: Int?,
    ): List<ChannelDto> {
        val saved = inner.batchSaveChannels(radioID, configured, unconfiguredIndices, pruneBeyond)
        val thrown = fault
        return if (thrown == null) saved else DiagnosticsFaultList(saved, thrown)
    }
}

private class DiagnosticsComputeFailure : Exception("disk")

private class DiagnosticsProbeError : Error("probe")
