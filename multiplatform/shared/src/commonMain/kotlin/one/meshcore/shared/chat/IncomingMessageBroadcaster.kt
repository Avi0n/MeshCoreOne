package one.meshcore.shared.chat

import one.meshcore.shared.EventBroadcaster
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.Uuid

/**
 * A just-received message the ingest pipeline broadcasts for the open thread to fold in real time. The
 * saved [message] is carried so the consumer appends without a re-fetch. Mirrors the two message arms of
 * the Swift `dataEventBroadcaster` (`directMessageReceived` / `channelMessageReceived`).
 */
sealed interface IncomingMessageEvent {
    /** A direct message from a resolved contact. [contactID] is the conversation the thread matches on. */
    data class DirectMessageReceived(val message: MessageDto, val contactID: Uuid) : IncomingMessageEvent

    /** A channel message. [channelIndex] is the firmware slot the thread matches on. */
    data class ChannelMessageReceived(val message: MessageDto, val channelIndex: Int) : IncomingMessageEvent
}

/** Incoming-message multicast. Parallels [MessageStatusBroadcaster]. */
typealias IncomingMessageBroadcaster = EventBroadcaster<IncomingMessageEvent>
