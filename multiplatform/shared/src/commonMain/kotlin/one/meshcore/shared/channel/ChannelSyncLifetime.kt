package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job

/** Runs channel reads in a child canceled by terminal connection retirement. */
internal suspend fun <T> ChannelServiceState.withChannelSyncOperation(block: suspend () -> T): T {
    currentCoroutineContext().ensureActive()
    var operationCancellation: CancellationException? = null
    return try {
        coroutineScope {
            val operation = coroutineContext.job
            val registration = syncRetired.invokeOnCompletion { operation.cancel() }
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

internal fun ChannelService.retireChannelSyncOperations() {
    channelSyncRetired.complete(Unit)
}
