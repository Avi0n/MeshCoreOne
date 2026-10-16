package one.meshcore.shared.chat.reactions

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.isOutgoing
import one.meshcore.shared.chat.reactionTimestamp
import one.meshcore.shared.model.Uuid

/**
 * Indexes a freshly loaded page of thread messages so queued reactions find their target, persisting any
 * drained pendings under the conversation's own radioID (never the live connection's). Mirrors the Swift
 * `indexMessagesForReactions`.
 */
suspend fun ReactionHandler.indexMessages(messages: List<MessageDto>, scope: ReactionIndexScope) {
    when (scope) {
        is ReactionIndexScope.Channel -> {
            val radioID = scope.channel.radioID
            for (message in messages) {
                val senderName = if (message.isOutgoing) scope.localNodeName else message.senderNodeName
                if (senderName == null) continue
                val matches = reactionService.indexMessage(
                    message.id,
                    scope.channel.index,
                    senderName,
                    message.text,
                    message.timestamp,
                )
                for (pending in matches) persistChannelPending(message.id, pending, radioID)
            }
        }

        is ReactionIndexScope.Direct -> {
            for (message in messages) {
                val matches = reactionService.indexDmMessage(
                    message.id,
                    scope.contact.id,
                    message.text,
                    message.reactionTimestamp,
                )
                for (pending in matches) {
                    persistDirectPending(message.id, pending, scope.contact.id, scope.contact.radioID)
                }
            }
        }
    }
}

/** After a DB-resolved channel match, index the target so subsequent reactions hit the cache. */
internal suspend fun ReactionHandler.indexResolvedChannelMessage(
    target: MessageDto,
    channelIndex: Int,
    selfNodeName: String,
) {
    val targetSender = channelCandidateSender(target, selfNodeName) ?: return
    reactionService.indexMessage(target.id, channelIndex, targetSender, target.text, target.reactionTimestamp)
}

internal suspend fun ReactionHandler.persistChannelPending(messageID: Uuid, pending: PendingReaction, radioID: Uuid) =
    persistChannel(
        ChannelReactionRow(
            messageID = messageID,
            emoji = pending.parsed.emoji,
            senderName = pending.senderNodeName,
            messageHash = pending.parsed.messageHash,
            rawText = pending.rawText,
            channelIndex = pending.channelIndex,
            radioID = radioID,
        ),
    )

internal suspend fun ReactionHandler.persistDirectPending(
    messageID: Uuid,
    pending: PendingDmReaction,
    contactID: Uuid,
    radioID: Uuid,
) = persistIfNew(
    directReaction(
        DirectReactionRow(
            messageID = messageID,
            emoji = pending.parsed.emoji,
            senderName = pending.senderName,
            messageHash = pending.parsed.messageHash,
            rawText = pending.rawText,
            contactID = contactID,
            radioID = radioID,
        ),
    ),
)
