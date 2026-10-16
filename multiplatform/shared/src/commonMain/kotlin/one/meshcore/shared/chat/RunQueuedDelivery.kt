package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException

/**
 * A queued radio send. Cancellation is rethrown. Every other failure, including a thrown error,
 * is handled by the caller.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun runQueuedDelivery(onFailure: suspend (Throwable) -> Unit, block: suspend () -> Unit) {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        onFailure(error)
    }
}
