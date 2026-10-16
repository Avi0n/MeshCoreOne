package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job

/** Runs contact sync in a child canceled by terminal connection retirement. */
internal suspend fun <T> ContactService.withContactSyncOperation(block: suspend () -> T): T {
    currentCoroutineContext().ensureActive()
    var operationCancellation: CancellationException? = null
    return try {
        coroutineScope {
            val operation = coroutineContext.job
            val registration = contactSyncRetired.invokeOnCompletion { operation.cancel() }
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

/** Terminal retirement returns no contact so an already fetched message can still persist. */
internal suspend fun <T> ContactService.withContactMaterializationOperation(block: suspend () -> T?): T? {
    val callerContext = currentCoroutineContext()
    return try {
        withContactSyncOperation(block)
    } catch (cancelled: CancellationException) {
        callerContext.ensureActive()
        if (!contactSyncRetired.isCompleted) throw cancelled
        null
    }
}

internal fun ContactService.retireContactSyncOperations() {
    contactSyncRetired.complete(Unit)
}
