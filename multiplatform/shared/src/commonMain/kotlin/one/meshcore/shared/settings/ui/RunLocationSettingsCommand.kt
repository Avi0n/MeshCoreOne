package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CancellationException

/**
 * One location-settings command. Cancellation goes to [onCancel], which rethrows the same instance
 * unless the caller rolls state back first. An error still propagates. Every other failure is
 * handled by the caller.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> runLocationSettingsCommand(
    onFailure: (Exception) -> T,
    onCancel: (CancellationException) -> Nothing = { throw it },
    block: suspend () -> T,
): T = try {
    block()
} catch (cancelled: CancellationException) {
    onCancel(cancelled)
} catch (error: Exception) {
    onFailure(error)
}
