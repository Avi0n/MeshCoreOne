package one.meshcore.shared.channel

/**
 * Errors raised by [ChannelService]. Kotlin port of the Swift `ChannelServiceError`; [SessionError]
 * wraps a lower-layer session failure the way the Swift `.sessionError(MeshCoreError)` case does, and
 * the messages match the Swift `errorDescription` strings so the single-`errorMessage` view surface
 * reads identically. [SyncAlreadyInProgress] and [CircuitBreakerOpen] guard the sync entry points.
 */
sealed class ChannelServiceError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Not connected to a radio. */
    class NotConnected : ChannelServiceError("Not connected to device.")

    /** The channel does not exist locally or on the device. */
    class ChannelNotFound : ChannelServiceError("Channel not found.")

    /** The device returned a channel at an index other than the one requested. */
    class InvalidChannelIndex : ChannelServiceError("Invalid channel index.")

    /** A supplied secret was not the required 16 bytes. */
    class SecretHashingFailed : ChannelServiceError("Failed to hash channel secret.")

    /** A local persistence write failed. */
    class SaveFailed(val reason: String) : ChannelServiceError("Failed to save channel: $reason")

    /** A device send failed. */
    class SendFailed(val reason: String) : ChannelServiceError("Send failed: $reason")

    /** An underlying session failure. */
    class SessionError(cause: Throwable) : ChannelServiceError(cause.message ?: "Session error", cause)

    /** A second sync was requested while one is already running. */
    class SyncAlreadyInProgress : ChannelServiceError("Channel sync is already in progress.")

    /** Sync was aborted after too many consecutive failures. */
    class CircuitBreakerOpen(val consecutiveFailures: Int) :
        ChannelServiceError("Channel sync suspended after $consecutiveFailures consecutive failures.")
}
