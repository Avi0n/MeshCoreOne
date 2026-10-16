package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException

/**
 * Runs one radio sync phase. [onFailure] reports a thrown failure and supplies the value later
 * phases still see. Cancellation still stops the caller.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend inline fun <T> runSyncPhase(onFailure: (Throwable) -> T, phase: () -> T): T = try {
    phase()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    onFailure(error)
}
