package one.meshcore.shared

import kotlinx.coroutines.CancellationException

/**
 * Logs [failure] and returns [fallback] when [block] throws. Cancellation still stops the caller,
 * and an error still propagates.
 */
@Suppress("TooGenericExceptionCaught")
suspend fun <T> runLoggedException(
    warning: (String) -> Unit,
    failure: String,
    fallback: () -> T,
    block: suspend () -> T,
): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    warning("$failure: ${error.message ?: "unknown error"}")
    fallback()
}
