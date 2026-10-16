package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Serial drain of queued envelopes. Owns a pending list and a single drain [Job]; multiple [enqueue]
 * calls during an active drain append to the same drain pass. The Kotlin port of the Swift `SendQueue`
 * actor — its single-owner state ([pending], [drainJob]) is confined behind [mutex], mirroring the actor
 * isolation.
 *
 * Errors thrown by [send]:
 * - A [CancellationException] re-inserts the envelope at the head and parks: the drain returns and
 *   respawns (via the completion path) so the requeued envelope retries. This is the park-and-retry
 *   primitive — a send closure that awaits a transport-open trigger throws it once the wait resolves.
 * - Any other error fires [onError] and the drain continues with the next envelope. Per-envelope
 *   failures never stop the queue.
 *
 * After the pending list empties, [onDrain] fires once per drain pass with the most recent
 * non-cancellation error (or null). An enqueue landing during the [onDrain] await is picked up by the
 * outer re-check so a follow-up envelope cannot sit unscheduled.
 *
 * [cancelDrain] cancels the in-flight drain and suppresses the auto-respawn so the queue goes dormant
 * without permanent retirement. A later [enqueue] still schedules a fresh drain.
 */
class SendQueue<T>(
    private val scope: CoroutineScope,
    private val send: suspend (T) -> Unit,
    private val onError: suspend (Throwable, T) -> Unit,
    private val onDrain: suspend (Throwable?) -> Unit,
) {
    private val mutex = Mutex()
    private val pending = ArrayDeque<T>()
    private var drainJob: Job? = null
    private val retired = CompletableDeferred<Unit>()

    /** Appends an envelope and ensures a drain is running. */
    suspend fun enqueue(envelope: T) = mutex.withLock {
        if (retired.isCompleted) return@withLock
        pending.addLast(envelope)
        if (drainJob == null) spawnLocked()
    }

    /** Number of envelopes waiting. Test-only; no view consumer. */
    suspend fun count(): Int = mutex.withLock { pending.size }

    /**
     * Awaits the current drain pass (and any park-driven respawns) to completion. Returns immediately if
     * no drain is running. Tests use it to synchronize on the drain without polling; production consumers
     * observe completion via [onDrain].
     */
    suspend fun awaitDrainCompletion() {
        while (true) {
            val job = mutex.withLock { drainJob } ?: return
            job.join()
            mutex.withLock {
                if (drainJob === job && job.isCompleted) drainJob = null
            }
        }
    }

    /**
     * Cancels the in-flight drain and joins it so the queue goes dormant. The current send closure's next
     * suspension propagates [CancellationException], which re-inserts the parked envelope at the head
     * (rows also survive in the store) and skips the respawn. A subsequent [enqueue] schedules a fresh
     * drain because [drainJob] is back to null.
     */
    suspend fun cancelDrain() {
        val job = mutex.withLock { drainJob }
        if (job != null) {
            job.cancelAndJoin()
            mutex.withLock {
                if (drainJob === job && job.isCompleted) drainJob = null
            }
        }
    }

    internal fun retire() {
        retired.complete(Unit)
    }

    /** Permanently stops this queue; durable envelopes belong to the next connection. */
    suspend fun close() {
        retire()
        withContext(NonCancellable) { awaitDrainCompletion() }
    }

    private fun spawnLocked() {
        if (retired.isCompleted) return
        val job = scope.launch(start = CoroutineStart.LAZY) { drainAndRespawn() }
        drainJob = job
        val registration = retired.invokeOnCompletion { job.cancel(SendQueueRetiredCancellation()) }
        job.invokeOnCompletion { registration.dispose() }
        job.start()
    }

    private suspend fun drainAndRespawn() {
        try {
            drain()
        } catch (cancellation: CancellationException) {
            // Teardown: clear the slot without respawning so the queue stays dormant.
            withContext(NonCancellable) { mutex.withLock { drainJob = null } }
            throw cancellation
        }
        // Park re-inserts at the head (pending non-empty) and normal completion leaves it empty, so the
        // respawn condition is uniformly "pending not empty" — matching the Swift taskCompleted check.
        withContext(NonCancellable) {
            mutex.withLock {
                drainJob = null
                if (!retired.isCompleted && pending.isNotEmpty()) spawnLocked()
            }
        }
    }

    private suspend fun drain() {
        var lastError: Throwable? = null
        do {
            while (true) {
                val envelope = mutex.withLock {
                    if (retired.isCompleted) null else pending.removeFirstOrNull()
                } ?: break
                val error = sendOne(envelope) { return }
                if (error != null) {
                    lastError = error
                    onError(error, envelope)
                }
            }
            // Outer re-check after the onDrain await: an enqueue can land while onDrain suspends, and
            // without this pass that envelope would sit with no scheduled drain.
            if (retired.isCompleted) return
            onDrain(lastError)
        } while (mutex.withLock { !retired.isCompleted && pending.isNotEmpty() })
    }

    // Park (the job is still active) returns from drain so the completion path respawns. Teardown
    // re-queues and rethrows so the queue stays dormant. Any other failure is returned for onError.
    @Suppress("TooGenericExceptionCaught")
    private suspend inline fun sendOne(envelope: T, onPark: () -> Nothing): Throwable? = try {
        send(envelope)
        null
    } catch (cancellation: CancellationException) {
        withContext(NonCancellable) { mutex.withLock { pending.addFirst(envelope) } }
        if (!coroutineContext.isActive) throw cancellation
        onPark()
    } catch (error: Throwable) {
        error
    }
}
