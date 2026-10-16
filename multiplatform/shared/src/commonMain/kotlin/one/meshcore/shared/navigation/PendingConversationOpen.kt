package one.meshcore.shared.navigation

/**
 * A notification-tap conversation open: the conversation key plus, for a reaction tap, the reacted
 * [messageId] to scroll to (null for a plain message tap, which opens at the bottom). Mirrors the iOS
 * `onReactionNotificationTapped` message id passed into `navigateToChat(scrollToMessageID:)`.
 */
data class NotificationConversationOpen(val conversationKey: String, val messageId: String? = null)

/**
 * App-level staging for a payload a notification body tap asks to open, the tap counterpart of
 * [PendingExternalUrl]. A cold-launch tap delivers its payload before the shell and the live
 * connection graph exist; routing it synchronously would lose it the same way a launch-time deep link is
 * lost. The payload is held here and routed only once [markReady] has run at the tail of the launch
 * sequence, so a failed auto-reconnect during launch cannot wipe a freshly staged tap.
 *
 * [submit] routes immediately if launch has already settled (warm tap), else holds the payload for
 * [markReady]. The payload is dropped after routing so a warm [submit] and the end-of-launch [markReady]
 * cannot both route the same tap. Held outside any connection or view-model object so it survives their
 * teardown; the routing itself is a caller-supplied sink, keeping this holder free of coroutines and
 * trivially testable. [T] is [NotificationConversationOpen], a contact id, or an external URL.
 */
class PendingConversationOpen<T> {
    private var pending: T? = null
    private var sink: ((T) -> Unit)? = null

    /** Records a payload from a tap and routes it now if launch has settled, else holds it. */
    fun submit(payload: T) {
        pending = payload
        drainIfReady()
    }

    /** Installs the routing [sink] at the tail of launch and routes any payload held from a cold-launch tap. */
    fun markReady(sink: (T) -> Unit) {
        this.sink = sink
        drainIfReady()
    }

    private fun drainIfReady() {
        val activeSink = sink ?: return
        val payload = pending ?: return
        pending = null
        activeSink(payload)
    }
}
