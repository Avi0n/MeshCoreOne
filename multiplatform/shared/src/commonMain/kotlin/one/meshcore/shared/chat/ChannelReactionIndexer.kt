package one.meshcore.shared.chat

import one.meshcore.shared.model.Uuid

/** Indexes a sent channel message for reaction correlation. */
fun interface ChannelReactionIndexer {
    suspend fun indexMessage(id: Uuid, channelIndex: Int, senderName: String, text: String, timestamp: Long)
}
