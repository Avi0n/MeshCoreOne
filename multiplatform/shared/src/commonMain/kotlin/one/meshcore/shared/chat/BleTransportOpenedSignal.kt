package one.meshcore.shared.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Single-purpose wake signal: a drain parked on a transport reopen suspends in [wait] until [fire]
 * lands. The Kotlin port of the Swift `BLETransportOpenedSignal` actor.
 *
 * The armed-when-no-waiter semantic is load-bearing: a [fire] during a successful send (when no drain is
 * parked) arms the flag so the next parked drain returns immediately rather than losing the signal.
 * [clear] drops the armed flag and is called only after a successful send — the consume-on-wait in [wait]
 * already handles "fire landed during the previous attempt" cleanly.
 */
class BleTransportOpenedSignal {
    private val mutex = Mutex()
    private var armed = false
    private val waiters = mutableListOf<CompletableDeferred<Unit>>()

    /**
     * Suspends until [fire] lands. Returns immediately (consuming the flag) if the signal is already
     * armed at call time. Throws [kotlinx.coroutines.CancellationException] if the calling coroutine is
     * cancelled before the signal fires.
     */
    suspend fun wait() {
        val waiter: CompletableDeferred<Unit> = mutex.withLock {
            if (armed) {
                armed = false
                return
            }
            CompletableDeferred<Unit>().also { waiters.add(it) }
        }
        try {
            waiter.await()
        } finally {
            // Cancellation (park timeout or teardown) must drop the abandoned waiter; a normal wake
            // already cleared it. NonCancellable so the cleanup still runs under a cancelled coroutine.
            withContext(NonCancellable) { mutex.withLock { waiters.remove(waiter) } }
        }
    }

    /** Wakes every waiter; arms the flag for the next [wait] if none are currently suspended. */
    suspend fun fire() = mutex.withLock {
        if (waiters.isEmpty()) {
            armed = true
        } else {
            waiters.forEach { it.complete(Unit) }
            waiters.clear()
        }
    }

    /** Drops any armed-pending state. Call only after a successful send, never before each attempt. */
    suspend fun clear() = mutex.withLock { armed = false }
}
