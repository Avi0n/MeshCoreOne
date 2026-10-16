package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job

/** Runs one session operation in a child canceled by terminal connection retirement. */
internal suspend fun <T> RemoteNodeService.withConnectionOperation(block: suspend () -> T): T {
    currentCoroutineContext().ensureActive()
    var operationCancellation: CancellationException? = null
    return try {
        coroutineScope {
            val operation = coroutineContext.job
            val registration = connectionRetired.invokeOnCompletion { operation.cancel() }
            try {
                operation.ensureActive()
                block()
            } catch (cancelled: CancellationException) {
                operationCancellation = cancelled
                throw cancelled
            } finally {
                registration.dispose()
            }
        }
    } catch (cancelled: CancellationException) {
        throw operationCancellation ?: cancelled
    }
}

/** Retires capture, logout and reauth ownership; background stops remain reusable. */
internal fun RemoteNodeService.retireConnectionOperations() {
    connectionRetired.complete(Unit)
}
