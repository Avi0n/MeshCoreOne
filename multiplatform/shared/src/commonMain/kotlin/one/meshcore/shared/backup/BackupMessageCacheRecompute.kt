package one.meshcore.shared.backup

import one.meshcore.shared.chat.reactions.ReactionParser
import one.meshcore.shared.chat.reactions.ReactionRow
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase

/**
 * Rewrites the denormalized `heardRepeats` count and `reactionSummary` string on every message that
 * gained a child row during an import. The chat bubble reads both caches rather than counting rows,
 * so without this a restored reaction never appears and the repeat count stays stale until a live
 * event happens to rewrite it. Mirrors iOS `recomputeMessageCaches`.
 */
internal suspend fun recomputeMessageCaches(database: MeshDatabase, messageIDs: Set<Uuid>) {
    if (messageIDs.isEmpty()) return
    val messageDao = database.messageDao()
    for (messageID in messageIDs) {
        val message = messageDao.getById(messageID) ?: continue
        val heardRepeats = database.messageRepeatDao().listByMessage(messageID).size
        val reactions = database.reactionDao().listByMessage(messageID)
        // No reactions means no summary at all, not an empty one: the bubble treats "" as a badge.
        val summary =
            if (reactions.isEmpty()) {
                null
            } else {
                ReactionParser.buildSummaryFromReactions(reactions.map { ReactionRow(it.emoji, it.receivedAt) })
            }
        if (message.heardRepeats != heardRepeats || message.reactionSummary != summary) {
            messageDao.upsert(message.copy(heardRepeats = heardRepeats, reactionSummary = summary))
        }
    }
}
