package one.meshcore.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A cancelled picker reserves its callback until that framework result has been consumed. */
internal class NodeConfigDocumentPickWaiter<T> {
    private val lock = Any()
    private var reservation: CompletableDeferred<T?>? = null

    suspend fun pick(launch: () -> Unit): T? {
        currentCoroutineContext().ensureActive()
        val request = CompletableDeferred<T?>()
        synchronized(lock) {
            check(reservation == null)
            reservation = request
        }
        var launched = false
        try {
            launch()
            launched = true
            return request.await()
        } finally {
            if (!launched) {
                synchronized(lock) {
                    if (reservation === request) reservation = null
                }
            }
            request.cancel()
        }
    }

    fun offer(value: T?): Boolean {
        val request = synchronized(lock) {
            reservation.also { reservation = null }
        } ?: return false
        request.complete(value)
        return true
    }
}
