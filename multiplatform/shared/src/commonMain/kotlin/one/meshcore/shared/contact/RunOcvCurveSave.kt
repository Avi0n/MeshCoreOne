package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException

/**
 * One battery-curve save. Cancellation runs [onCancel] and is rethrown. An error still
 * propagates. Every other failure is reported by the caller.
 */
internal suspend fun runOcvCurveSave(onCancel: () -> Unit, onFailure: () -> Unit, block: suspend () -> Unit) {
    try {
        block()
    } catch (cancelled: CancellationException) {
        onCancel()
        throw cancelled
    } catch (_: Exception) {
        onFailure()
    }
}
