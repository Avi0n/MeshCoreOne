package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException

private const val ADVERT_STORE_RETRYING = "Error handling advert event (retrying once)"
private const val ADVERT_STORE_RETRY_FAILED = "Advert touch retry failed"

/**
 * Runs one advert store read or stamp, then tries it once more after a failure.
 * Cancellation stops the caller. A second failure is reported and [fallback] is returned.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend inline fun <T> retryContactAdvertOnce(
    logError: (String) -> Unit,
    fallback: () -> T,
    block: suspend () -> T,
): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    logError("$ADVERT_STORE_RETRYING: ${error.message ?: error}")
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (retryError: Throwable) {
        logError("$ADVERT_STORE_RETRY_FAILED: ${retryError.message ?: retryError}")
        fallback()
    }
}
