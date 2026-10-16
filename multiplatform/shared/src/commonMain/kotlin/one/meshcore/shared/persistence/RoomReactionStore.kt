package one.meshcore.shared.persistence

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.backup.ReactionDto
import one.meshcore.shared.chat.isOutgoing
import one.meshcore.shared.chat.reactionTimestamp
import one.meshcore.shared.chat.reactions.ChannelReactionLookup
import one.meshcore.shared.chat.reactions.ReactionParser
import one.meshcore.shared.chat.reactions.ReactionStore
import one.meshcore.shared.chat.reactions.ReactionTargetLookup
import one.meshcore.shared.model.Uuid

/**
 * Room-backed [ReactionStore]: reaction rows plus the message/contact/channel lookups the send, receive,
 * and notification paths need. Candidate scans pull a page of newest messages then filter by timestamp
 * window and PocketMesh hash in memory (same shape as the in-memory test double). Row reads delegate to
 * [RoomReactionTargets].
 */
class RoomReactionStore(
    private val reactionDao: ReactionDao,
    private val messageDao: MessageDao,
    contactDao: ContactDao,
    channelDao: ChannelDao,
) : ReactionStore,
    ReactionTargetLookup by RoomReactionTargets(contactDao, channelDao, messageDao) {
    override suspend fun saveReaction(reaction: ReactionDto) {
        reactionDao.upsert(reaction.toEntity())
    }

    override suspend fun fetchReactions(messageID: Uuid, limit: Int): List<ReactionDto> =
        reactionDao.listByMessage(messageID)
            .sortedByDescending { it.receivedAt }
            .take(limit)
            .map { it.toDto() }

    override suspend fun reactionExists(messageID: Uuid, senderName: String, emoji: String): Boolean =
        reactionDao.listByMessage(messageID).any {
            it.senderName == senderName && it.emoji == emoji
        }

    override suspend fun updateMessageReactionSummary(messageID: Uuid, summary: String?) {
        val current = messageDao.getById(messageID) ?: return
        messageDao.upsert(current.copy(reactionSummary = summary))
    }

    override suspend fun findDirectMessageForReaction(
        radioID: Uuid,
        contactID: Uuid,
        messageHash: String,
        window: LongRange,
        limit: Int,
    ): MessageDto? = fetchDirectMessageCandidates(radioID, contactID, window, limit).firstOrNull { candidate ->
        !ReactionParser.isReactionText(candidate.text, isDM = true) &&
            ReactionParser.generateMessageHash(candidate.text, candidate.reactionTimestamp) == messageHash
    }

    override suspend fun findChannelMessageForReaction(
        lookup: ChannelReactionLookup,
        window: LongRange,
        limit: Int,
    ): MessageDto? = fetchChannelMessageCandidates(
        lookup.radioID,
        lookup.channelIndex,
        window,
        limit,
    ).firstOrNull { candidate ->
        val senderMatches = if (candidate.isOutgoing) {
            lookup.localNodeName != null && lookup.parsed.targetSender == lookup.localNodeName
        } else {
            candidate.senderNodeName == lookup.parsed.targetSender
        }
        senderMatches &&
            !ReactionParser.isReactionText(candidate.text, isDM = false) &&
            ReactionParser.generateMessageHash(candidate.text, candidate.reactionTimestamp) == lookup.parsed.messageHash
    }

    override suspend fun fetchDirectMessageCandidates(
        radioID: Uuid,
        contactID: Uuid,
        window: LongRange,
        limit: Int,
    ): List<MessageDto> =
        messageDao.listDirectNewestFirst(radioID, contactID, limit = maxOf(limit, CANDIDATE_PAGE), offset = 0)
            .map { it.toDto() }
            .filter { it.timestamp in window }
            .take(limit)

    override suspend fun fetchChannelMessageCandidates(
        radioID: Uuid,
        channelIndex: Int,
        window: LongRange,
        limit: Int,
    ): List<MessageDto> =
        messageDao.listChannelNewestFirst(radioID, channelIndex, limit = maxOf(limit, CANDIDATE_PAGE), offset = 0)
            .map { it.toDto() }
            .filter { it.timestamp in window }
            .take(limit)

    companion object {
        private const val CANDIDATE_PAGE = 200
    }
}
