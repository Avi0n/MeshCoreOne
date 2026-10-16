package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CancellationException

/**
 * Danger-zone commands share this catch. Cancellation is rethrown, every other exception is
 * reported, and a thrown error still propagates. The busy flag clears either way.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun runDangerZoneCommand(
    onFailure: (Throwable) -> Unit,
    setBusy: (Boolean) -> Unit = {},
    command: suspend () -> Unit,
) {
    setBusy(true)
    try {
        command()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        onFailure(error)
    } finally {
        setBusy(false)
    }
}
