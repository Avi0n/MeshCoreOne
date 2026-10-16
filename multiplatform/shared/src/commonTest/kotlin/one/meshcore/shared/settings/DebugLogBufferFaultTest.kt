package one.meshcore.shared.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.sequentialTestUuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Save and prune faults on [DebugLogBuffer]. A failed save requeues the batch. A failed prune leaves
 * that batch saved and retries on the next flush. Cancellation stops the flush.
 */
class DebugLogBufferFaultTest {
    @Test
    fun aSaveErrorRequeuesTheBatch() = runTest {
        val store = FaultInjectingDebugLogStore()
        store.saveFault = DebugLogBufferProbeError()
        val buffer = DebugLogBuffer(store, backgroundScope, now = { 0 })
        buffer.append(entry("one"))
        buffer.flush()
        assertEquals(0, store.inner.snapshot().size)

        store.saveFault = null
        buffer.flush()
        assertEquals(listOf("one"), store.inner.snapshot().map { it.message })
    }

    @Test
    fun aCancelledSaveRethrowsTheSameCancellation() = runTest {
        val store = FaultInjectingDebugLogStore()
        val cancelled = CancellationException("stop")
        store.saveFault = cancelled
        val buffer = DebugLogBuffer(store, backgroundScope, now = { 0 })
        buffer.append(entry("one"))
        val thrown = assertFailsWith<CancellationException> {
            buffer.flush()
        }
        assertSame(cancelled, thrown)
        assertEquals(0, store.inner.snapshot().size)
    }

    @Test
    fun aPruneErrorKeepsTheBatchAndRetriesLater() = runTest {
        val store = FaultInjectingDebugLogStore()
        store.pruneFault = DebugLogBufferProbeError()
        val nowMs = DebugLogRetention.PRUNE_INTERVAL_MS
        val buffer = DebugLogBuffer(store, backgroundScope, now = { nowMs })
        buffer.setLastPruneForTesting(0)
        buffer.append(entry("one"))
        buffer.flush()
        assertEquals(listOf("one"), store.inner.snapshot().map { it.message })
        assertEquals(0, store.pruneCount)

        store.pruneFault = null
        buffer.append(entry("two"))
        buffer.flush()
        assertEquals(1, store.pruneCount)
    }

    @Test
    fun aCancelledPruneRethrowsAndRetriesLater() = runTest {
        val store = FaultInjectingDebugLogStore()
        val cancelled = CancellationException("stop")
        store.pruneFault = cancelled
        val nowMs = DebugLogRetention.PRUNE_INTERVAL_MS
        val buffer = DebugLogBuffer(store, backgroundScope, now = { nowMs })
        buffer.setLastPruneForTesting(0)
        buffer.append(entry("one"))
        val thrown = assertFailsWith<CancellationException> {
            buffer.flush()
        }
        assertSame(cancelled, thrown)
        assertEquals(listOf("one"), store.inner.snapshot().map { it.message })
        assertEquals(0, store.pruneCount)

        store.pruneFault = null
        buffer.append(entry("two"))
        buffer.flush()
        assertEquals(1, store.pruneCount)
    }

    private var idCounter = 0

    private fun entry(message: String) = DebugLogEntryDto(
        id = sequentialTestUuid(idCounter++),
        timestamp = 0,
        level = DebugLogLevel.INFO,
        subsystem = "test",
        category = "c",
        message = message,
    )
}

private class FaultInjectingDebugLogStore : DebugLogStore {
    val inner = InMemoryDebugLogStore()
    var saveFault: Throwable? = null
    var pruneFault: Throwable? = null
    var pruneCount = 0
        private set

    override suspend fun saveDebugLogEntries(entries: List<DebugLogEntryDto>) {
        saveFault?.let { throw it }
        inner.saveDebugLogEntries(entries)
    }

    override suspend fun fetchDebugLogEntries(since: Long, limit: Int) = inner.fetchDebugLogEntries(since, limit)

    override suspend fun countDebugLogEntries() = inner.countDebugLogEntries()

    override suspend fun pruneDebugLogEntries(olderThanMillis: Long, keepCount: Int) {
        pruneFault?.let { throw it }
        pruneCount += 1
        inner.pruneDebugLogEntries(olderThanMillis, keepCount)
    }

    override suspend fun clearDebugLogEntries() = inner.clearDebugLogEntries()
}

private class DebugLogBufferProbeError : Error("probe")
