package one.meshcore.shared.persistence

import one.meshcore.shared.backup.ReactionDto

fun ReactionEntity.toDto(): ReactionDto = ReactionDto(
    id = id,
    messageID = messageID,
    emoji = emoji,
    senderName = senderName,
    messageHash = messageHash,
    rawText = rawText,
    receivedAt = receivedAt,
    radioID = radioID,
    channelIndex = channelIndex,
    contactID = contactID,
)

fun ReactionDto.toEntity(): ReactionEntity = ReactionEntity(
    id = id,
    messageID = messageID,
    emoji = emoji,
    senderName = senderName,
    messageHash = messageHash,
    rawText = rawText,
    receivedAt = receivedAt,
    radioID = radioID,
    channelIndex = channelIndex,
    contactID = contactID,
)
