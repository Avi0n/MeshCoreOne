package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException

private const val CHANNEL_CACHE_REFRESH_FAILED = "Failed to refresh RxLogService channel cache"

/**
 * A channel-cache refresh fault is logged and does not fail the caller. Cancellation propagates.
 * A thrown error is logged the same way and does not propagate.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> runChannelCacheRefresh(
    warning: (String) -> Unit,
    fallback: () -> T,
    block: suspend () -> T,
): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    warning("$CHANNEL_CACHE_REFRESH_FAILED: ${error.message ?: error}")
    fallback()
}
