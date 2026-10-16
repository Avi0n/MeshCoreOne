package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * A status-section read shows a failure on the section. Cancellation clears loading and still
 * propagates. A thrown error leaves loading set and propagates.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun runStatusSectionRead(
    onCancel: () -> Unit,
    onFailure: (Exception) -> Unit,
    read: suspend () -> Unit,
) {
    try {
        read()
    } catch (cancelled: CancellationException) {
        onCancel()
        throw cancelled
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        onFailure(error)
    }
}
