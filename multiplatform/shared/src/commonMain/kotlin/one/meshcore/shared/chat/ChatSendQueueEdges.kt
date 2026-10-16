package one.meshcore.shared.chat

import one.meshcore.shared.settings.PersistentLogger

/** Optional reaction indexing and retry timing. A null log uses the queue's own category. */
data class ChatSendQueueEdges(
    val reactionIndexer: ChannelReactionIndexer? = null,
    val config: ChatSendQueueConfig = ChatSendQueueConfig(),
    val logger: PersistentLogger? = null,
)
