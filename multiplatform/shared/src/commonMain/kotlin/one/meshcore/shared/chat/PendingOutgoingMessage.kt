package one.meshcore.shared.chat

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid

/**
 * First-send defaults before the radio accepts an outgoing row. Callers attach the destination
 * and reply identity from their draft.
 */
internal fun MessageService.pendingOutgoingMessage(
    id: Uuid,
    radioID: Uuid,
    text: String,
    timestamp: Long,
    textType: TextType,
): MessageDto = MessageDto(
    id = id,
    radioID = radioID,
    text = text,
    timestamp = timestamp,
    createdAt = now(),
    direction = MessageDirection.OUTGOING,
    status = MessageStatus.PENDING,
    textType = textType,
    pathLength = 0,
    isRead = false,
    heardRepeats = 0,
    sendCount = 1,
    retryAttempt = 0,
    maxRetryAttempts = 0,
    linkPreviewFetched = false,
    containsSelfMention = false,
    mentionSeen = false,
    timestampCorrected = false,
)
