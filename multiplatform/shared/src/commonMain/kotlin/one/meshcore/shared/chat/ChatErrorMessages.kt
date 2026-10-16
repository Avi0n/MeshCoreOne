package one.meshcore.shared.chat

/**
 * Localized copy for the chat surfaces' error paths, injected so the view models stay free of Compose
 * resources, the way [one.meshcore.shared.tools.ToolsCliMessages] is.
 *
 * [userFacing] is the Kotlin counterpart of the Swift `error.userFacingMessage`: every chat catch block
 * routes through it, so a failure the app already has words for is never reported as the exception's own
 * developer English.
 */
data class ChatErrorMessages(
    val notConnected: String,
    val sendFailed: String,
    val invalidRecipient: String,
    val messageTooLong: String,
    val loadConversationsFailed: String,
    val pinSaveFailed: String,
    val loadOlderMessagesFailed: String,
    val notConnectedToDelete: String,
    val servicesUnavailable: String,
    val sendQueuePersistFailed: String,
    val unableToSend: String,
    val loadMessagesFailed: String,
    val deleteConversationFailed: String,
    val deleteMessageFailed: String,
    val deleteTimedOut: String,
    /** The bridge-layer text for a wrapped session failure, mirroring the Swift `sessionError` delegation. */
    val sessionError: (Throwable) -> String?,
) {
    /**
     * What to show the user for [error], falling back to [otherwise] for anything the chat layer does not
     * recognise. A recognised failure always wins, because its wording names the actual problem.
     */
    fun userFacing(error: Throwable, otherwise: String): String = when (error) {
        is MessageServiceException.NotConnected -> notConnected
        // iOS reports the shipped sentence and drops the reason, which is developer text.
        is MessageServiceException.SendFailed -> sendFailed
        is MessageServiceException.InvalidRecipient -> invalidRecipient
        is MessageServiceException.MessageTooLong -> messageTooLong
        is MessageServiceException.SessionError -> sessionError(error.cause ?: error) ?: otherwise
        else -> otherwise
    }

    companion object {
        /**
         * The developer-English copy, for tests and for any surface that cannot reach composition. Never a
         * default: a production caller has to pass the resolved copy, or its users read English.
         */
        fun developerEnglish(): ChatErrorMessages = ChatErrorMessages(
            notConnected = "Not connected to device.",
            sendFailed = "Send failed.",
            invalidRecipient = "Cannot send messages to this recipient.",
            messageTooLong = "Message exceeds the maximum allowed length.",
            loadConversationsFailed = "Couldn't load conversations.",
            pinSaveFailed = "Couldn't save pin.",
            loadOlderMessagesFailed = "Failed to load older messages",
            notConnectedToDelete = "Connect to your radio to delete this conversation.",
            servicesUnavailable = "Services not available",
            sendQueuePersistFailed = "Couldn't queue your message. Try again.",
            unableToSend = "Please ensure your device is connected and try again.",
            loadMessagesFailed = "Failed to load messages",
            deleteConversationFailed = "Failed to delete conversation.",
            deleteMessageFailed = "Failed to delete message.",
            deleteTimedOut = "The radio did not respond. Please try again.",
            sessionError = { null },
        )
    }
}
