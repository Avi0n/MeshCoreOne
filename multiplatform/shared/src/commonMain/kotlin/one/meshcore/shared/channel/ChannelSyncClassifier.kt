package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException

/** Turns a slot failure into the [ChannelSyncError] the sync result reports. */
internal object ChannelSyncClassifier {
    fun circuitBreakerError(index: Int): ChannelSyncError =
        ChannelSyncError(index, ChannelSyncErrorType.CircuitBreaker, "Skipped due to circuit breaker")

    fun nextConsecutiveFailureCount(error: ChannelSyncError, current: Int): Int =
        if (error.errorType.countsTowardCircuitBreaker) current + 1 else 0

    /**
     * Classifies a thrown failure into a [ChannelSyncError]. A [ChannelServiceError.SessionError] is
     * unwrapped to its cause; a raw transport/timeout exception (which `fetchChannel` lets propagate) is
     * classified directly. Mirrors the Swift `classifyError`, including which sources count toward the breaker.
     */
    fun classifyError(error: Throwable, index: Int): ChannelSyncError = when (error) {
        is ChannelServiceError.CircuitBreakerOpen ->
            syncError(index, ChannelSyncErrorType.CircuitBreaker, error.message)
        is ChannelServiceError.SaveFailed ->
            syncError(index, ChannelSyncErrorType.DatabaseError, error.message)
        is ChannelServiceError.SessionError -> classifyCause(error.cause, index, fallback = error.message)
        is ChannelSendTimeoutException -> syncError(index, ChannelSyncErrorType.SendTimeout, "Send timed out")
        is ChannelTransportException -> syncError(index, ChannelSyncErrorType.TransportError, error.message)
        is ChannelTimeoutException -> syncError(index, ChannelSyncErrorType.Timeout, "Request timed out")
        is ChannelDeviceException ->
            syncError(index, ChannelSyncErrorType.DeviceError(error.firmwareCode), error.message)
        else -> syncError(index, ChannelSyncErrorType.Unknown, error.message)
    }

    fun classifyCause(cause: Throwable?, index: Int, fallback: String?): ChannelSyncError = when (cause) {
        is ChannelTimeoutException -> syncError(index, ChannelSyncErrorType.Timeout, "Request timed out")
        is ChannelSendTimeoutException -> syncError(index, ChannelSyncErrorType.SendTimeout, "Send timed out")
        is ChannelTransportException -> syncError(index, ChannelSyncErrorType.TransportError, cause.message)
        is ChannelDeviceException ->
            syncError(index, ChannelSyncErrorType.DeviceError(cause.firmwareCode), cause.message)
        else -> syncError(index, ChannelSyncErrorType.Unknown, fallback)
    }

    fun syncError(index: Int, type: ChannelSyncErrorType, description: String?): ChannelSyncError =
        ChannelSyncError(index, type, description.orEmpty())

    fun remap(error: Throwable): Nothing =
        throw if (error is ChannelServiceError) error else ChannelServiceError.SessionError(error)
}

/**
 * Wraps a session failure as [ChannelServiceError.SessionError] so create/join/clear surfaces
 * can catch one type. Transport and send-timeout used to leak past those catches and leave
 * the spinner stuck.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend inline fun <T> mapChannelSessionError(block: () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    ChannelSyncClassifier.remap(error)
}
