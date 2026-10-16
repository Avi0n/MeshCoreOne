package one.meshcore.shared.persistence

import one.meshcore.shared.chat.pathdetail.ChannelMessageDeletionStore
import one.meshcore.shared.model.Uuid

/**
 * Room-backed [ChannelMessageDeletionStore] for the block-channel-sender cascade: channel-only exact
 * sender-name match, then pending/reaction/repeat/message deletes. Empty [ids] lists are no-ops so
 * Room's empty-IN edge case never fires.
 */
class RoomChannelMessageDeletionStore(
    private val messageDao: MessageDao,
    private val pendingSendDao: PendingSendDao,
    private val reactionDao: ReactionDao,
    private val messageRepeatDao: MessageRepeatDao,
) : ChannelMessageDeletionStore {
    override suspend fun channelMessageIdsFromSender(radioID: Uuid, senderName: String): List<Uuid> =
        messageDao.channelMessageIdsFromSender(radioID, senderName)

    override suspend fun deletePendingSends(messageIDs: List<Uuid>) {
        if (messageIDs.isEmpty()) return
        pendingSendDao.deleteByMessageIds(messageIDs)
    }

    override suspend fun deleteReactions(messageIDs: List<Uuid>) {
        if (messageIDs.isEmpty()) return
        reactionDao.deleteByMessageIds(messageIDs)
    }

    override suspend fun deleteMessageRepeats(messageIDs: List<Uuid>) {
        if (messageIDs.isEmpty()) return
        messageRepeatDao.deleteByMessageIds(messageIDs)
    }

    override suspend fun deleteMessages(messageIDs: List<Uuid>) {
        if (messageIDs.isEmpty()) return
        messageDao.deleteByIds(messageIDs)
    }
}
