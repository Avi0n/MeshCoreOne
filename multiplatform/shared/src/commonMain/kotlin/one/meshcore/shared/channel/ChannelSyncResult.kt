package one.meshcore.shared.channel

/**
 * Why a single slot failed to sync. Kotlin port of the Swift `ChannelSyncError.ErrorType`; the raw
 * distinctions drive both retry eligibility and the circuit breaker, so the cases must not be collapsed.
 * [DeviceError] carries the firmware sub-code as in Swift's `.deviceError(code:)`.
 */
sealed interface ChannelSyncErrorType {
    data object Timeout : ChannelSyncErrorType

    data object SendTimeout : ChannelSyncErrorType

    data object TransportError : ChannelSyncErrorType

    data object CircuitBreaker : ChannelSyncErrorType

    data class DeviceError(val code: Int) : ChannelSyncErrorType

    data object DatabaseError : ChannelSyncErrorType

    data object Unknown : ChannelSyncErrorType
}

/** Whether a retry could recover this failure: only the two timeout classes, exactly as Swift. */
val ChannelSyncErrorType.isRetryable: Boolean
    get() = this is ChannelSyncErrorType.Timeout || this is ChannelSyncErrorType.SendTimeout

/**
 * Whether this failure advances the consecutive-failure count that trips the circuit breaker. Only the
 * transport-class failures (timeout, send timeout, transport error) count; a device error, database
 * error, breaker skip, or unknown resets the streak — matching the Swift `countsTowardCircuitBreaker`.
 */
val ChannelSyncErrorType.countsTowardCircuitBreaker: Boolean
    get() = when (this) {
        is ChannelSyncErrorType.Timeout,
        is ChannelSyncErrorType.SendTimeout,
        is ChannelSyncErrorType.TransportError,
        -> true

        is ChannelSyncErrorType.CircuitBreaker,
        is ChannelSyncErrorType.DeviceError,
        is ChannelSyncErrorType.DatabaseError,
        is ChannelSyncErrorType.Unknown,
        -> false
    }

/** Detailed error for one failed channel slot during a sync. Mirrors the Swift `ChannelSyncError`. */
data class ChannelSyncError(val index: Int, val errorType: ChannelSyncErrorType, val description: String) {
    /** Whether this error type is potentially recoverable with a retry. */
    val isRetryable: Boolean get() = errorType.isRetryable
}

/**
 * The outcome of a channel sync pass. Mirrors the Swift `ChannelSyncResult`: how many configured slots
 * were persisted plus the per-slot failures, from which the retryable set and breaker verdict derive.
 */
data class ChannelSyncResult(val channelsSynced: Int, val errors: List<ChannelSyncError> = emptyList()) {
    /** Whether the pass completed with no failures. */
    val isComplete: Boolean get() = errors.isEmpty()

    /** Count of response-timeout failures (distinct from send timeouts). */
    val requestTimeoutCount: Int get() = errors.count { it.errorType is ChannelSyncErrorType.Timeout }

    /** Count of transport send-timeout failures. */
    val sendTimeoutCount: Int get() = errors.count { it.errorType is ChannelSyncErrorType.SendTimeout }

    /** Whether the circuit breaker aborted the pass, leaving remaining slots unread. */
    val circuitBreakerAborted: Boolean get() = errors.any { it.errorType is ChannelSyncErrorType.CircuitBreaker }

    /** Indices whose failures a retry pass could recover. */
    val retryableIndices: List<Int> get() = errors.filter { it.isRetryable }.map { it.index }
}
