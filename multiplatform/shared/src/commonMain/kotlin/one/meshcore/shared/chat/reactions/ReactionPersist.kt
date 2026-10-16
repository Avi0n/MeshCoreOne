package one.meshcore.shared.chat.reactions

import one.meshcore.shared.backup.ReactionDto
import one.meshcore.shared.chat.isOutgoing
import one.meshcore.shared.model.NotificationLevel

internal suspend fun ReactionHandler.persistDm(row: DirectReactionRow) = persistIfNew(directReaction(row))

internal suspend fun ReactionHandler.persistChannel(row: ChannelReactionRow) = persistIfNew(channelReaction(row))

internal suspend fun ReactionHandler.persistIfNew(reaction: ReactionDto) {
    val isNew = reactionService.persistReactionIfNew(reaction, store, sink)
    if (isNew) notifyIfWarranted(reaction)
}

/**
 * Notifies for a freshly persisted reaction only when its target message was outgoing (a reaction to a
 * message someone else sent never notifies), the reactor is not the local node (no self-reaction
 * alert), and the conversation is not muted. Mirrors the gating in iOS
 * `NotificationActionHandler.handleReactionNotification`.
 */
private suspend fun ReactionHandler.notifyIfWarranted(reaction: ReactionDto) {
    val target = store.fetchMessage(reaction.messageID) ?: return
    if (!target.isOutgoing) return
    val local = localNodeName()
    if (local != null && reaction.senderName == local) return
    if (isMuted(reaction)) return
    notifier.notifyReaction(
        ReactionNotice(
            reactorName = reaction.senderName,
            emoji = reaction.emoji,
            messagePreview = reactionPreview(target.text),
            messageID = reaction.messageID,
            contactID = reaction.contactID,
            channelIndex = reaction.channelIndex,
            radioID = reaction.radioID,
        ),
    )
}

private suspend fun ReactionHandler.isMuted(reaction: ReactionDto): Boolean {
    val contactID = reaction.contactID
    val channelIndex = reaction.channelIndex
    return when {
        contactID != null -> store.fetchContact(contactID)?.isMuted ?: false
        channelIndex != null ->
            store.fetchChannel(reaction.radioID, channelIndex)?.notificationLevel == NotificationLevel.MUTED
        else -> false
    }
}

/** Truncates a reacted-to message for display in the notification body. Mirrors iOS `reactionPreview`. */
private fun ReactionHandler.reactionPreview(text: String): String =
    if (text.length > ReactionConstants.REACTION_PREVIEW_MAX_LENGTH) {
        text.take(ReactionConstants.REACTION_PREVIEW_KEEP_LENGTH) + ReactionConstants.REACTION_PREVIEW_ELLIPSIS
    } else {
        text
    }

private fun ReactionHandler.channelReaction(row: ChannelReactionRow): ReactionDto = ReactionDto(
    id = newReactionId(),
    messageID = row.messageID,
    emoji = row.emoji,
    senderName = row.senderName,
    messageHash = row.messageHash,
    rawText = row.rawText,
    receivedAt = now(),
    radioID = row.radioID,
    channelIndex = row.channelIndex,
    contactID = null,
)

internal fun ReactionHandler.directReaction(row: DirectReactionRow): ReactionDto = ReactionDto(
    id = newReactionId(),
    messageID = row.messageID,
    emoji = row.emoji,
    senderName = row.senderName,
    messageHash = row.messageHash,
    rawText = row.rawText,
    receivedAt = now(),
    radioID = row.radioID,
    channelIndex = null,
    contactID = row.contactID,
)
