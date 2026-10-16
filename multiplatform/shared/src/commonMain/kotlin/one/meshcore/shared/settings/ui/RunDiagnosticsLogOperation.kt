package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CancellationException

/** Export and clear use the screen's failure copy; cancellation never produces a failed result. */
@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> runDiagnosticsLogOperation(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
