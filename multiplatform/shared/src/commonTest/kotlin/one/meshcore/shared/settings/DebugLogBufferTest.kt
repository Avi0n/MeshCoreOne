package one.meshcore.shared.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.sequentialTestUuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Buffer/flush/backpressure/drop-summary behavior for [DebugLogBuffer], plus the process-wide shared/pending
 * mechanism, ported from the Swift `DebugLogBuffer Tests`. `runCurrent()` drives the buffer's fire-and-forget
 * flush coroutines on the injected scope; a gated store holds two flushes at the save gate so the backlog cap
 * drops exactly one batch, deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebugLogBufferTest {
    private var idCounter = 0

    private fun entry(message: String, category: String = "c", level: DebugLogLevel = DebugLogLevel.INFO) =
        DebugLogEntryDto(
            id = sequentialTestUuid(idCounter++),
            timestamp = 0,
            level = level,
            subsystem = "test",
            category = category,
            message = message,
        )

    // MARK: Instance flush

    @Test
    fun flushSavesBufferedEntries() = runTest {
        val store = InMemoryDebugLogStore()
        val buffer = DebugLogBuffer(store, backgroundScope)
        buffer.append(entry("one"))
        buffer.append(entry("two"))
        buffer.flush()

        assertEquals(listOf("one", "two"), store.snapshot().map { it.message })
    }

    @Test
    fun reachingCapacityFlushesImmediately() = runTest {
        val store = InMemoryDebugLogStore()
        val buffer = DebugLogBuffer(store, backgroundScope)
        repeat(DebugLogBuffer.MAX_BUFFER_SIZE) { buffer.append(entry("m$it")) }
        runCurrent()

        assertEquals(DebugLogBuffer.MAX_BUFFER_SIZE, store.snapshot().size)
    }

    @Test
    fun scheduledFlushFiresAfterInterval() = runTest {
        val store = InMemoryDebugLogStore()
        val buffer = DebugLogBuffer(store, backgroundScope)
        buffer.append(entry("later"))
        assertEquals(0, store.snapshot().size)

        advanceTimeBy(DebugLogBuffer.FLUSH_INTERVAL_MS)
        runCurrent()
        assertEquals(1, store.snapshot().size)
    }

    @Test
    fun flushPrunesWhenTheHourlyIntervalHasElapsed() = runTest {
        val store = CountingPruneStore()
        val nowMs = DebugLogRetention.PRUNE_INTERVAL_MS
        val buffer = DebugLogBuffer(store, backgroundScope, now = { nowMs })
        buffer.setLastPruneForTesting(0)
        buffer.append(entry("one"))
        buffer.flush()
        assertEquals(1, store.pruneCount)
        buffer.append(entry("two"))
        buffer.flush()
        assertEquals(1, store.pruneCount, "a second flush inside the interval must not prune again")
    }

    // MARK: Backpressure + drop summary

    @Test
    fun droppedEntriesReportedOnceStoreRecovers() = runTest {
        val store = GatedDebugLogStore()
        val buffer = DebugLogBuffer(store, backgroundScope, now = { 0 }, newId = { entry("x").id })

        repeat(DebugLogBuffer.MAX_BUFFER_SIZE) { buffer.append(entry("a$it")) }
        runCurrent()
        repeat(DebugLogBuffer.MAX_BUFFER_SIZE) { buffer.append(entry("b$it")) }
        runCurrent()
        assertEquals(2, store.pendingSaveCount)

        store.releaseAndSucceed()
        runCurrent()
        buffer.flush()
        runCurrent()

        val saved = store.snapshot()
        assertEquals(DebugLogBuffer.MAX_BUFFER_SIZE, saved.count { it.level != DebugLogLevel.WARNING })
        val summary = saved.singleOrNull { it.level == DebugLogLevel.WARNING }
        assertTrue(summary != null && summary.message.contains("${DebugLogBuffer.MAX_BUFFER_SIZE}"))
    }

    @Test
    fun failedSummarySaveCarriesDropCountForward() = runTest {
        val store = GatedDebugLogStore()
        val buffer = DebugLogBuffer(store, backgroundScope, now = { 0 }, newId = { entry("x").id })

        repeat(DebugLogBuffer.MAX_BUFFER_SIZE) { buffer.append(entry("a$it")) }
        runCurrent()
        repeat(DebugLogBuffer.MAX_BUFFER_SIZE) { buffer.append(entry("b$it")) }
        runCurrent()
        store.releaseAndSucceed()
        store.failNextSummarySave = true
        runCurrent()

        // Recovery flush saves the surviving batch; its summary save fails once.
        buffer.flush()
        runCurrent()
        assertTrue(store.snapshot().none { it.level == DebugLogLevel.WARNING })

        // The next successful save reports the carried-forward count.
        buffer.append(entry("post-recovery"))
        buffer.flush()
        runCurrent()
        val summary = store.snapshot().singleOrNull { it.level == DebugLogLevel.WARNING }
        assertTrue(summary != null && summary.message.contains("${DebugLogBuffer.MAX_BUFFER_SIZE}"))
    }

    // MARK: Static shared / pending

    @Test
    fun sharedRoundTrips() = runTest {
        DebugLogBuffer.resetPendingForTesting()
        DebugLogBuffer.setShared(null)
        val buffer = DebugLogBuffer(InMemoryDebugLogStore(), backgroundScope)

        DebugLogBuffer.setShared(buffer)
        assertEquals(buffer, DebugLogBuffer.getShared())

        DebugLogBuffer.setShared(null)
        assertEquals(null, DebugLogBuffer.getShared())
    }

    @Test
    fun entriesRecordedBeforeAssignmentDrainInOrder() = runTest {
        DebugLogBuffer.resetPendingForTesting()
        DebugLogBuffer.setShared(null)
        val store = InMemoryDebugLogStore()
        val buffer = DebugLogBuffer(store, backgroundScope)

        DebugLogBuffer.record(entry("first"))
        DebugLogBuffer.record(entry("second"))
        DebugLogBuffer.setShared(buffer)
        runCurrent()
        buffer.flush()

        assertEquals(listOf("first", "second"), store.snapshot().map { it.message })
        DebugLogBuffer.setShared(null)
    }

    @Test
    fun pendingQueueDropsOldestPastBound() = runTest {
        DebugLogBuffer.resetPendingForTesting()
        DebugLogBuffer.setShared(null)
        val store = InMemoryDebugLogStore()
        val buffer = DebugLogBuffer(store, backgroundScope)
        val overflow = 3
        val total = DebugLogBuffer.MAX_PENDING_ENTRIES + overflow

        repeat(total) { DebugLogBuffer.record(entry("$it")) }
        DebugLogBuffer.setShared(buffer)
        runCurrent()
        buffer.flush()
        runCurrent()

        val saved = store.snapshot()
        assertEquals(DebugLogBuffer.MAX_PENDING_ENTRIES, saved.size)
        assertEquals("$overflow", saved.first().message)
        assertEquals("${total - 1}", saved.last().message)
        DebugLogBuffer.setShared(null)
    }
}

/** A [DebugLogStore] whose saves fail (blocked at a gate) until released, for the backpressure tests. */
private class GatedDebugLogStore : DebugLogStore {
    private val saved = mutableListOf<DebugLogEntryDto>()
    private var gate = CompletableDeferred<Unit>()
    private var shouldFail = true
    var failNextSummarySave = false
    var pendingSaveCount = 0
        private set

    override suspend fun saveDebugLogEntries(entries: List<DebugLogEntryDto>) {
        if (shouldFail) {
            pendingSaveCount++
            try {
                gate.await()
            } finally {
                pendingSaveCount--
            }
            error("save failed")
        }
        if (failNextSummarySave && entries.size == 1) {
            failNextSummarySave = false
            error("summary save failed")
        }
        saved += entries
    }

    override suspend fun fetchDebugLogEntries(since: Long, limit: Int): List<DebugLogEntryDto> = emptyList()

    override suspend fun countDebugLogEntries(): Int = saved.size

    override suspend fun pruneDebugLogEntries(olderThanMillis: Long, keepCount: Int) = Unit

    override suspend fun clearDebugLogEntries() = Unit

    fun releaseAndSucceed() {
        shouldFail = false
        gate.complete(Unit)
        gate = CompletableDeferred()
    }

    fun snapshot(): List<DebugLogEntryDto> = saved.toList()
}

private class CountingPruneStore : DebugLogStore {
    private val inner = InMemoryDebugLogStore()
    var pruneCount = 0
        private set

    override suspend fun saveDebugLogEntries(entries: List<DebugLogEntryDto>) = inner.saveDebugLogEntries(entries)

    override suspend fun fetchDebugLogEntries(since: Long, limit: Int) = inner.fetchDebugLogEntries(since, limit)

    override suspend fun countDebugLogEntries() = inner.countDebugLogEntries()

    override suspend fun pruneDebugLogEntries(olderThanMillis: Long, keepCount: Int) {
        pruneCount++
        inner.pruneDebugLogEntries(olderThanMillis, keepCount)
    }

    override suspend fun clearDebugLogEntries() = inner.clearDebugLogEntries()
}
