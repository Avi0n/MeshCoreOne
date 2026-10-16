package one.meshcore.shared.meshcore

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Registers [id], runs [dispatch], then waits for the bridge callback. A failure to hand the
 * command off drops the id before it propagates, so a slot is never left pending.
 */
internal suspend fun <T> awaitRegistered(
    pending: MutableMap<Long, CompletableDeferred<T>>,
    id: Long,
    onCancellation: (Long) -> Unit = {},
    dispatch: (Long) -> Unit,
): T {
    coroutineContext.ensureActive()
    val deferred = CompletableDeferred<T>()
    pending[id] = deferred
    return try {
        dispatch(id)
        deferred.await()
    } catch (error: CancellationException) {
        onCancellation(id)
        throw error
    } finally {
        pending.remove(id)
    }
}
