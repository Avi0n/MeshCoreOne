package one.meshcore.shared.contact

import one.meshcore.shared.model.Uuid

/**
 * Deletes a channel sender's channel messages (never DMs) for a radio. On block, a blocked contact's
 * channel posts vanish too, preserving the block guarantee. A seam because the channel message store is
 * owned by the chat graph; container wiring binds this to it. Mirrors the Swift
 * `deleteChannelMessages(fromSender:radioID:)`.
 */
fun interface ChannelMessageDeleting {
    /** Deletes every channel message from [senderName] for [radioID], leaving direct messages untouched. */
    suspend fun deleteChannelMessages(senderName: String, radioID: Uuid)
}
