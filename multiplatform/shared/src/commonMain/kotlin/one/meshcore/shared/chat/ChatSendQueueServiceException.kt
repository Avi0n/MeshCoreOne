package one.meshcore.shared.chat

/** Errors surfaced by [ChatSendQueueService.enqueueDM]/[ChatSendQueueService.enqueueChannel]. */
sealed class ChatSendQueueServiceException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The durable persist of the queue row failed, so the caller must surface it rather than drop the send. */
    class PersistFailed(cause: Throwable) : ChatSendQueueServiceException("Failed to queue message for sending.", cause)

    /** Not connected to a device. */
    class NotConnected : ChatSendQueueServiceException("Not connected to device.")
}
