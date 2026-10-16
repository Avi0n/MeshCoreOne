package one.meshcore.shared.persistence

import one.meshcore.shared.model.Uuid

/** Same-database channel DAOs used by frame writes and message cascades. */
internal class RoomChannelParts(
    val database: MeshDatabase,
    val newId: () -> Uuid,
    val channelDao: ChannelDao = database.channelDao(),
    val messageDao: MessageDao = database.messageDao(),
)
