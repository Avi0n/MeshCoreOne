package one.meshcore.shared.chat

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.runReportingThrowable

/** Identity of a pending outgoing channel message before the row's clocks and flags are filled. */
private data class OutgoingChannelDraft(
    val id: Uuid,
    val radioID: Uuid,
    val channelIndex: Int,
    val text: String,
    val timestamp: Long,
    val textType: TextType,
)

/**
 * Cap user text so `"name: " + text` fits the composed channel budget. Firmware prepends the Device
 * node name, so the last gate loads that row — not a host prefs snapshot that can be empty or stale.
 * A missing Device uses [ChatConstants.MAX_USABLE_NAME_BYTES], matching iOS `rejectIfChannelTextTooLong`.
 */
private suspend fun MessageService.rejectIfChannelTextTooLong(text: String, radioID: Uuid) {
    val nodeNameByteCount = store.fetchDeviceNodeName(radioID)?.encodeToByteArray()?.size
        ?: ChatConstants.MAX_USABLE_NAME_BYTES
    val maxBytes = ChatConstants.maxChannelMessageLength(nodeNameByteCount)
    if (text.encodeToByteArray().size > maxBytes) {
        throw MessageServiceException.MessageTooLong()
    }
}

private fun MessageService.createOutgoingChannelMessage(draft: OutgoingChannelDraft): MessageDto =
    pendingOutgoingMessage(
        id = draft.id,
        radioID = draft.radioID,
        text = draft.text,
        timestamp = draft.timestamp,
        textType = draft.textType,
    ).copy(channelIndex = draft.channelIndex)

/**
 * Sends a channel broadcast in one shot. No ACK, no retry: `.sent` is terminal success. A failure in the
 * send/status flip marks `.failed` (retry visible); post-broadcast bookkeeping failures only log because
 * the packet already left the radio. Mirrors the inline `sendChannelMessage`.
 */
suspend fun MessageService.sendChannelMessage(
    text: String,
    channelIndex: Int,
    radioID: Uuid,
    textType: TextType = TextType.PLAIN,
): Pair<Uuid, Long> {
    rejectIfChannelTextTooLong(text, radioID)
    val messageID = newMessageId()
    val timestamp = now().toLong()
    store.saveMessage(
        createOutgoingChannelMessage(
            OutgoingChannelDraft(
                id = messageID,
                radioID = radioID,
                channelIndex = channelIndex,
                text = text,
                timestamp = timestamp,
                textType = textType,
            ),
        ),
    )

    runOutgoingSend(
        onFailure = { error -> failDirectSend(messageID, error) },
    ) {
        withPoolBackoff(
            FirmwareDeviceErrorCode.CHANNEL_MESSAGE_NOT_FOUND,
            config.poolBackoff,
            log = { logger.warning(it) },
        ) {
            sendChannelMessageWithScope(text, channelIndex, radioID, timestamp)
        }
    }

    runReportingThrowable<Unit>(
        onFailure = { error ->
            logger.warning(channelInlineBookkeepingFailLine(messageID, error.message ?: error.toString()))
        },
    ) {
        store.updateMessageStatus(messageID, MessageStatus.SENT)
        statusBroadcaster.yield(MessageStatusEvent.StatusResolved(messageID, MessageStatus.SENT))
        store.fetchChannelId(radioID, channelIndex)?.let { store.updateChannelLastMessage(it, now()) }
    }
    return messageID to timestamp
}

/** Saves a `.pending` channel message for optimistic UI, drained later via [sendPendingChannelMessage]. */
suspend fun MessageService.createPendingChannelMessage(
    text: String,
    channelIndex: Int,
    radioID: Uuid,
    textType: TextType = TextType.PLAIN,
): MessageDto {
    rejectIfChannelTextTooLong(text, radioID)
    val messageID = newMessageId()
    val timestamp = now().toLong()
    val dto = createOutgoingChannelMessage(
        OutgoingChannelDraft(
            id = messageID,
            radioID = radioID,
            channelIndex = channelIndex,
            text = text,
            timestamp = timestamp,
            textType = textType,
        ),
    )
    store.saveMessage(dto)
    return dto
}

/**
 * Sends an already-created pending channel message. Catch 1 (validation + send + status flip) writes
 * `.failed` on any failure; catch 2 (post-status bookkeeping) only logs because `.sent` is already
 * committed. Mirrors `sendPendingChannelMessage`.
 */
suspend fun MessageService.sendPendingChannelMessage(messageID: Uuid) {
    val (radioID, channelIndex) = runOutgoingSend(
        onFailure = { error -> failMessageAndRethrow(error, messageID) },
    ) {
        val message = store.fetchMessage(messageID) ?: throw MessageServiceException.SendFailed("Message not found")
        val channelIndex = message.channelIndex ?: throw MessageServiceException.SendFailed("Not a channel message")
        withPoolBackoff(
            FirmwareDeviceErrorCode.CHANNEL_MESSAGE_NOT_FOUND,
            config.poolBackoff,
            log = { logger.warning(it) },
        ) {
            sendChannelMessageWithScope(message.text, channelIndex, message.radioID, message.timestamp)
        }
        store.updateMessageStatus(messageID, MessageStatus.SENT)
        message.radioID to channelIndex
    }

    applyPendingChannelBookkeeping(messageID, radioID, channelIndex)
}

private suspend fun MessageService.applyPendingChannelBookkeeping(messageID: Uuid, radioID: Uuid, channelIndex: Int) {
    runReportingThrowable<Unit>(
        onFailure = { error ->
            logger.warning(channelPostStatusBookkeepingFailLine(messageID, error.message ?: error.toString()))
        },
    ) {
        statusBroadcaster.yield(MessageStatusEvent.StatusResolved(messageID, MessageStatus.SENT))
        store.fetchChannelId(radioID, channelIndex)?.let { store.updateChannelLastMessage(it, now()) }
    }
}

/**
 * Resends a channel message. A fresh wire timestamp on the first drain (`preserveTimestamp == false`) is
 * load-bearing: the mesh dedup ring has 128 cyclic slots with no time eviction, so reusing the failed
 * packet's timestamp is silently dropped at every neighbor. Returns the wire timestamp used, so reaction
 * indexing keys off the post-resend value. Mirrors `resendChannelMessage`.
 */
suspend fun MessageService.resendChannelMessage(messageID: Uuid, preserveTimestamp: Boolean = false): Long {
    val (wireTimestamp, sentCommitted) = runOutgoingSend(
        onFailure = { error -> failMessageAndRethrow(error, messageID) },
    ) {
        val message = store.fetchMessage(messageID) ?: throw MessageServiceException.SendFailed("Message not found")
        val channelIndex = message.channelIndex ?: throw MessageServiceException.SendFailed("Not a channel message")
        val wireTimestamp = if (preserveTimestamp) message.timestamp else now().toLong()
        if (!preserveTimestamp) store.updateMessageTimestamp(messageID, wireTimestamp)
        withPoolBackoff(
            FirmwareDeviceErrorCode.CHANNEL_MESSAGE_NOT_FOUND,
            config.poolBackoff,
            log = { logger.warning(it) },
        ) {
            sendChannelMessageWithScope(message.text, channelIndex, message.radioID, wireTimestamp)
        }
        store.updateMessageStatus(messageID, MessageStatus.SENT)
        wireTimestamp to true
    }

    applyChannelResendBookkeeping(messageID)

    if (sentCommitted) statusBroadcaster.yield(MessageStatusEvent.Resent(messageID))
    return wireTimestamp
}

private suspend fun MessageService.applyChannelResendBookkeeping(messageID: Uuid) {
    runReportingThrowable<Unit>(
        onFailure = { error ->
            logger.warning(resendBookkeepingFailLine(messageID, error.message ?: error.toString()))
        },
    ) {
        store.incrementMessageSendCount(messageID)
        store.updateMessageHeardRepeats(messageID, 0)
        store.deleteMessageRepeats(messageID)
    }
}
