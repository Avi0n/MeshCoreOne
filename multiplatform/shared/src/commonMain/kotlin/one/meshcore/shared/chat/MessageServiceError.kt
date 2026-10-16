package one.meshcore.shared.chat

/**
 * A transient/terminal device error surfaced by a [ChatSession] send. [firmwareCode] is the radio's
 * `deviceError(code)` sub-code; [withPoolBackoff] absorbs bursts whose code matches its transient code
 * (see [FirmwareDeviceErrorCode]). The production session adapter maps a bridge `DEVICE_ERROR` to this;
 * fakes throw it directly. Mirrors the transient arm of the Swift `MeshCoreError.deviceError`.
 */
class ChatDeviceException(val firmwareCode: Int) : Exception("device error $firmwareCode")

/**
 * Errors raised by [MessageService]. Mirrors the Swift `MessageServiceError`; [sessionError] wraps a
 * lower-layer session failure the way the Swift `.sessionError(MeshCoreError)` case does.
 */
sealed class MessageServiceException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Not connected to a device. */
    class NotConnected : MessageServiceException("Not connected to device.")

    /** The message send operation failed for the given reason. */
    class SendFailed(val reason: String) : MessageServiceException("Send failed: $reason")

    /** Attempted to send to an invalid recipient (e.g. a repeater). */
    class InvalidRecipient : MessageServiceException("Cannot send messages to this recipient.")

    /** Message text exceeds the maximum allowed length. */
    class MessageTooLong : MessageServiceException("Message exceeds the maximum allowed length.")

    /** An underlying session failure. */
    class SessionError(cause: Throwable) : MessageServiceException(cause.message ?: "Session error.", cause)
}
