package one.meshcore.shared.contact

/**
 * Errors raised by [ContactService]. Kotlin port of the Swift `ContactServiceError`; [SessionError]
 * wraps a lower-layer session failure the way the Swift `.sessionError(MeshCoreError)` case does. The
 * messages match the Swift `errorDescription` strings so the view-model surface (a single
 * `errorMessage`) reads identically.
 */
sealed class ContactServiceError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Not connected to a radio. */
    class NotConnected : ContactServiceError("Not connected to radio")

    /** A send operation failed. */
    class SendFailed : ContactServiceError("Failed to send message")

    /** The device returned an unparseable response. */
    class InvalidResponse : ContactServiceError("Invalid response from device")

    /** A sync was interrupted before completing. */
    class SyncInterrupted : ContactServiceError("Sync was interrupted")

    /** The contact does not exist on the device. */
    class ContactNotFound : ContactServiceError("Contact not found on device")

    /** The device's contact table is full. */
    class ContactTableFull : ContactServiceError("Device node list is full")

    /** The contact's advertisement is missing or too old to re-broadcast. */
    class ShareContactUnavailable :
        ContactServiceError("Unable to share node. The node's advertisement may be missing or too old.")

    /** An underlying session failure. */
    class SessionError(cause: Throwable) : ContactServiceError(cause.message ?: "Session error", cause)
}
