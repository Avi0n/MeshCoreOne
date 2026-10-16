package one.meshcore.shared.chat

import one.meshcore.shared.meshcore.ChannelMessage
import one.meshcore.shared.meshcore.ContactMessage

/** A received message paired with its resolved conversation, discriminating the two ingest paths. */
sealed interface IncomingMessage {
    /** A direct message; [contact] is the sender resolved from its key prefix, or null if unknown. */
    data class Direct(val message: ContactMessage, val contact: ResolvedContact?) : IncomingMessage

    /** A channel message; [channel] is the local channel for the reported slot, or null (phantom). */
    data class Channel(val message: ChannelMessage, val channel: ResolvedChannel?) : IncomingMessage

    /** iOS `IncomingMessageKind.logLabel`. */
    val logLabel: String
        get() = when (this) {
            is Direct -> INGEST_LOG_LABEL_DIRECT
            is Channel -> INGEST_LOG_LABEL_CHANNEL
        }
}
