package one.meshcore.shared.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.runThrowableResult
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Buffers debug log entries and flushes them to a [DebugLogStore] in batches, a port of the Swift
 * `DebugLogBuffer` actor. Mutable state is confined behind [mutex] (the actor analog); flushes run on the
 * injected [scope]. A size-triggered flush fires at [MAX_BUFFER_SIZE]; otherwise a flush is scheduled after
 * [FLUSH_INTERVAL_MS]. On a save failure entries are re-queued up to the backlog cap ([MAX_BUFFER_SIZE] `* 2`),
 * beyond which they are counted as dropped and reported as one synthesized warning on the next successful
 * save — those loss windows are otherwise invisible in the persisted log.
 *
 * The companion `shared`/`record` mechanism is the process-wide entry point: entries recorded before a buffer
 * exists queue in a bounded pending list (oldest dropped past [MAX_PENDING_ENTRIES]), and assigning a buffer
 * drains that queue into it in record order.
 */
@OptIn(ExperimentalTime::class)
class DebugLogBuffer(
    private val store: DebugLogStore,
    internal val scope: CoroutineScope,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val newId: () -> Uuid = Uuid::random,
) {
    private val mutex = Mutex()
    private val buffer = mutableListOf<DebugLogEntryDto>()
    private var droppedEntryCount = 0
    private var isPersistingDropSummary = false

    // Seeded to now so the first successful flush after connect does not
    // repeat the connect-time prune on this store.
    private var lastPrune = now()

    /** The scheduled (debounced) flush; a size-triggered flush is a separate fire-and-forget coroutine. */
    private var flushJob: Job? = null

    /** Appends an entry, flushing immediately at capacity or scheduling a debounced flush otherwise. */
    suspend fun append(entry: DebugLogEntryDto) {
        val flushImmediately = mutex.withLock {
            buffer.add(entry)
            buffer.size >= MAX_BUFFER_SIZE
        }
        if (flushImmediately) flushNow() else scheduleFlush()
    }

    /** Cancels any scheduled flush and drains synchronously. */
    suspend fun flush() {
        flushJob?.cancel()
        flushJob = null
        flushBuffer()
    }

    /** Alias for [flush]; drains before teardown. */
    suspend fun shutdown() = flush()

    internal suspend fun setLastPruneForTesting(epochMs: Long) {
        mutex.withLock { lastPrune = epochMs }
    }

    private suspend fun pruneIfDue() {
        val due = mutex.withLock { now() - lastPrune >= DebugLogRetention.PRUNE_INTERVAL_MS }
        if (!due) return
        try {
            store.pruneDebugLogEntries(
                olderThanMillis = now() - DebugLogRetention.WINDOW_MS,
                keepCount = DebugLogRetention.MAX_ENTRIES,
            )
            mutex.withLock { lastPrune = now() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // A prune fault must not drop the just-saved batch; the next successful flush retries.
            return
        }
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_INTERVAL_MS)
            flushBuffer()
        }
    }

    private fun flushNow() {
        flushJob?.cancel()
        flushJob = null
        scope.launch { flushBuffer() }
    }

    private suspend fun flushBuffer() {
        val entries = mutex.withLock {
            if (buffer.isEmpty()) emptyList() else buffer.toList().also { buffer.clear() }
        }
        if (entries.isEmpty()) return

        if (trySave(entries)) {
            persistDropSummaryIfNeeded()
            pruneIfDue()
            return
        }
        mutex.withLock {
            val requeue = entries.take(MAX_BUFFER_SIZE)
            if (buffer.size + requeue.size < MAX_BUFFER_SIZE * 2) {
                buffer.addAll(0, requeue)
                droppedEntryCount += entries.size - requeue.size
            } else {
                droppedEntryCount += entries.size
            }
        }
    }

    /**
     * Persists a summary of entries dropped since the last successful save, then subtracts only the reported
     * amount (drops recorded while the save was suspended survive). A failed summary save leaves the count so
     * the loss carries forward. Guarded against reentrancy so two concurrent flushes cannot double-report.
     */
    private suspend fun persistDropSummaryIfNeeded() {
        val reported = mutex.withLock {
            if (droppedEntryCount <= 0 || isPersistingDropSummary) {
                null
            } else {
                isPersistingDropSummary = true
                droppedEntryCount
            }
        } ?: return

        val message = "Lost $reported log entries due to prior save failures"
        val summary = DebugLogEntryDto.of(
            newId(),
            now(),
            DebugLogLevel.WARNING,
            DebugLogSource(LOG_SUBSYSTEM, LOG_CATEGORY),
            message,
        )
        val saved = trySave(listOf(summary))
        mutex.withLock {
            if (saved) droppedEntryCount -= reported
            isPersistingDropSummary = false
        }
    }

    private suspend fun trySave(entries: List<DebugLogEntryDto>): Boolean = runThrowableResult({ false }) {
        store.saveDebugLogEntries(entries)
        true
    }

    companion object {
        /** Buffer size that triggers an immediate flush. */
        const val MAX_BUFFER_SIZE: Int = 50

        /** Pre-buffer queue bound; the oldest are dropped past this. */
        const val MAX_PENDING_ENTRIES: Int = 500

        /** Debounce before a scheduled flush fires. */
        const val FLUSH_INTERVAL_MS: Long = 5000

        private const val LOG_SUBSYSTEM = "com.mc1"
        private const val LOG_CATEGORY = "DebugLogBuffer"

        private val sharedMutex = Mutex()
        private var sharedBuffer: DebugLogBuffer? = null
        private val pending = mutableListOf<DebugLogEntryDto>()

        /** The current process-wide buffer, or null before one is assigned. */
        suspend fun getShared(): DebugLogBuffer? = sharedMutex.withLock { sharedBuffer }

        /**
         * Assigns the process-wide buffer. Assigning a non-null buffer atomically takes every entry queued
         * while none existed and delivers them in record order.
         */
        suspend fun setShared(buffer: DebugLogBuffer?) {
            val drained = sharedMutex.withLock {
                sharedBuffer = buffer
                if (buffer == null) emptyList() else pending.toList().also { pending.clear() }
            }
            if (buffer != null && drained.isNotEmpty()) {
                buffer.scope.launch { drained.forEach { buffer.append(it) } }
            }
        }

        /** Hands an entry to the current buffer, or queues it (bounded, oldest dropped) until one is assigned. */
        suspend fun record(entry: DebugLogEntryDto) {
            val buffer = sharedMutex.withLock {
                val current = sharedBuffer
                if (current != null) {
                    current
                } else {
                    pending.add(entry)
                    if (pending.size > MAX_PENDING_ENTRIES) {
                        repeat(pending.size - MAX_PENDING_ENTRIES) { pending.removeAt(0) }
                    }
                    null
                }
            }
            buffer?.let { it.scope.launch { it.append(entry) } }
        }

        /** Empties the pending queue so tests exercise the no-buffer window from a known state. */
        suspend fun resetPendingForTesting() = sharedMutex.withLock { pending.clear() }
    }
}
