package one.meshcore.shared.chat

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.runReportingThrowable

private const val MILLISECONDS_PER_SECOND = 1000.0

/** Identity of a pending outgoing direct message before the row's clocks and flags are filled. */
private data class OutgoingDirectDraft(
    val id: Uuid,
    val radioID: Uuid,
    val contactID: Uuid,
    val text: String,
    val timestamp: Long,
    val textType: TextType,
    val replyToID: Uuid?,
)

/** The per-attempt inputs of the DM retry loop, grouped so the loop stays under the parameter limit. */
internal data class DirectSendRequest(
    val messageID: Uuid,
    val contactID: Uuid,
    val radioID: Uuid,
    val publicKey: ByteArray,
    val text: String,
    val timestamp: Long,
    val timeoutSeconds: Double?,
)

/** Optional knobs for a retried direct send. Defaults stay a plain text send with no reply. */
data class DirectRetryOptions(
    val textType: TextType = TextType.PLAIN,
    val replyToID: Uuid? = null,
    val timeoutSeconds: Double? = null,
    val onMessageCreated: (suspend (MessageDto) -> Unit)? = null,
)

/** Identity and loop outcome that finalizeSend writes into the terminal row. */
internal data class DirectSendSettlement(
    val messageID: Uuid,
    val contactID: Uuid,
    val radioID: Uuid,
    val publicKey: ByteArray,
    val sentInfo: MessageSentInfo?,
    val initialPathLength: Int,
)

/**
 * Adds the firmware's ACK code when it differs from the predicted one (so a late ACK from either still
 * matches), else re-stamps the existing entry's timeout and send time from send-return.
 */
internal data class AckRestamp(
    val messageID: Uuid,
    val contactID: Uuid,
    val predictedAck: ByteArray,
    val sentInfo: MessageSentInfo,
    val ackTimeoutSeconds: Double,
    val attempt: Int,
)

/** Rejects a repeater recipient (`invalidRecipient`) and over-long text (`messageTooLong`). */
private fun validateDirectMessage(contact: ContactDto, text: String) {
    if (ContactType.fromRaw(contact.typeRawValue) == ContactType.REPEATER) {
        throw MessageServiceException.InvalidRecipient()
    }
    if (text.encodeToByteArray().size > ChatConstants.MAX_DIRECT_MESSAGE_LENGTH_BYTES) {
        throw MessageServiceException.MessageTooLong()
    }
}

private fun MessageService.createOutgoingMessage(draft: OutgoingDirectDraft): MessageDto = pendingOutgoingMessage(
    id = draft.id,
    radioID = draft.radioID,
    text = draft.text,
    timestamp = draft.timestamp,
    textType = draft.textType,
).copy(contactID = draft.contactID, replyToID = draft.replyToID)

/**
 * Sends a DM in a single attempt (no retry loop). Saves `.pending`, precomputes the expected ACK and
 * tracks it before the send so the listener cannot race the tracker, then on the radio's queue-accept
 * writes `.sent` (a no-op if the listener already delivered) and stamps the contact date. Mirrors
 * `sendDirectMessage`.
 */
suspend fun MessageService.sendDirectMessage(
    text: String,
    to: ContactDto,
    textType: TextType = TextType.PLAIN,
    replyToID: Uuid? = null,
): MessageDto {
    validateDirectMessage(to, text)
    val messageID = newMessageId()
    val timestamp = now().toLong()
    store.saveMessage(
        createOutgoingMessage(
            OutgoingDirectDraft(
                id = messageID,
                radioID = to.radioID,
                contactID = to.id,
                text = text,
                timestamp = timestamp,
                textType = textType,
                replyToID = replyToID,
            ),
        ),
    )

    val (predictedAck, sentInfo) = runOutgoingSend(
        onFailure = { error -> failDirectSend(messageID, error) },
    ) {
        val senderPublicKey = session.currentSelfPublicKey() ?: throw MessageServiceException.NotConnected()
        val predictedAck = AckCodeBuilder.expectedAck(timestamp, 0, text, senderPublicKey)
        // Pre-send floor must outlive one expiry tick so a slow round-trip does not expire the
        // speculative entry before the authoritative timeout lands.
        trackPendingAck(messageID, to.id, predictedAck, ChatConstants.ACK_CHECK_INTERVAL_SECONDS)
        val sentInfo = sendDirectOrDropSpeculative(
            messageID,
            to.publicKey.dmDestination(),
            text,
            timestamp,
        )
        predictedAck to sentInfo
    }

    val ackTimeout = sentInfo.suggestedTimeoutMs / MILLISECONDS_PER_SECOND * ChatConstants.ACK_TIMEOUT_MULTIPLIER
    mergeOrRestampAck(
        AckRestamp(
            messageID = messageID,
            contactID = to.id,
            predictedAck = predictedAck,
            sentInfo = sentInfo,
            ackTimeoutSeconds = ackTimeout,
            attempt = 0,
        ),
    )

    // Post-send bookkeeping: the radio accepted the send, so a throw here must not mark `.failed` or
    // drop the entry; the ACK or expiry checker owns the terminal state now.
    applyDirectPostSendBookkeeping(messageID, to.id, sentInfo.expectedAck.ackCodeLeUInt32())

    return store.fetchMessage(messageID) ?: throw MessageServiceException.SendFailed("Failed to fetch saved message")
}

internal suspend fun MessageService.failDirectSend(messageID: Uuid, error: Throwable): Nothing {
    statusBroadcaster.yield(MessageStatusEvent.Failed(messageID))
    failMessageAndRethrow(error, messageID)
}

/**
 * Sends a DM through the app-layer retry loop and reconciles the terminal state. Saves `.pending`,
 * notifies [DirectRetryOptions.onMessageCreated], runs the loop, then [finalizeSend].
 * Mirrors `sendMessageWithRetry`.
 */
suspend fun MessageService.sendMessageWithRetry(
    text: String,
    to: ContactDto,
    options: DirectRetryOptions = DirectRetryOptions(),
): MessageDto {
    validateDirectMessage(to, text)
    val messageID = newMessageId()
    val timestamp = now().toLong()
    val dto = createOutgoingMessage(
        OutgoingDirectDraft(
            id = messageID,
            radioID = to.radioID,
            contactID = to.id,
            text = text,
            timestamp = timestamp,
            textType = options.textType,
            replyToID = options.replyToID,
        ),
    )
    store.saveMessage(dto)
    options.onMessageCreated?.invoke(dto)

    val initialPathLength = to.outPathLength
    return runOutgoingSend(
        onFailure = { error -> failDirectSend(messageID, error) },
    ) {
        val sentInfo = sendDirectMessageWithRetryLoop(
            DirectSendRequest(messageID, to.id, to.radioID, to.publicKey, text, timestamp, options.timeoutSeconds),
        )
        finalizeSend(
            DirectSendSettlement(
                messageID = messageID,
                contactID = to.id,
                radioID = to.radioID,
                publicKey = to.publicKey,
                sentInfo = sentInfo,
                initialPathLength = initialPathLength,
            ),
        )
    }
}

/**
 * Saves a `.pending` DM without sending it (optimistic UI), stamping the contact's last-message date so
 * a first DM appears in the conversation list before any send succeeds. Mirrors `createPendingMessage`.
 */
suspend fun MessageService.createPendingMessage(
    text: String,
    to: ContactDto,
    textType: TextType = TextType.PLAIN,
    replyToID: Uuid? = null,
): MessageDto {
    validateDirectMessage(to, text)
    val messageID = newMessageId()
    val timestamp = now().toLong()
    val dto = createOutgoingMessage(
        OutgoingDirectDraft(
            id = messageID,
            radioID = to.radioID,
            contactID = to.id,
            text = text,
            timestamp = timestamp,
            textType = textType,
            replyToID = replyToID,
        ),
    )
    store.saveMessage(dto)
    store.updateContactLastMessage(to.id, dto.createdAt)
    return dto
}

/** Drains a `.pending` DM through the retry loop. First drain stamps a fresh wire timestamp. */
suspend fun MessageService.sendPendingDirectMessage(
    messageID: Uuid,
    to: ContactDto,
    preserveTimestamp: Boolean = false,
): MessageDto = performQueuedDirectMessageSend(messageID, to, preserveTimestamp, isResend = false)

/** Resends an already-sent DM: re-runs the loop, bumps `sendCount`, and broadcasts `.resent`. */
suspend fun MessageService.resendDirectMessage(
    messageID: Uuid,
    to: ContactDto,
    preserveTimestamp: Boolean = false,
): MessageDto = performQueuedDirectMessageSend(messageID, to, preserveTimestamp, isResend = true)

private suspend fun MessageService.performQueuedDirectMessageSend(
    messageID: Uuid,
    to: ContactDto,
    preserveTimestamp: Boolean,
    isResend: Boolean,
): MessageDto {
    val alreadyInFlight = stateMutex.withLock { !inFlightRetries.add(messageID) }
    if (alreadyInFlight) {
        logger.warning("Send already in progress for message: $messageID")
        throw MessageServiceException.SendFailed("Retry already in progress")
    }
    try {
        val initialPathLength = to.outPathLength
        val existing = store.fetchMessage(messageID) ?: throw MessageServiceException.SendFailed("Message not found")

        // Fresh timestamp on the first drain so mesh repeaters do not deduplicate the packet; queue
        // auto-retry passes preserveTimestamp so the recipient's dedup ring catches the duplicate.
        val wireTimestamp: Long
        if (preserveTimestamp) {
            wireTimestamp = existing.timestamp
        } else {
            wireTimestamp = now().toLong()
            store.updateMessageTimestamp(messageID, wireTimestamp)
        }

        return runOutgoingSend(
            onFailure = { error -> failMessageAndRethrow(error, messageID) },
        ) {
            val sentInfo = sendDirectMessageWithRetryLoop(
                DirectSendRequest(messageID, to.id, to.radioID, to.publicKey, existing.text, wireTimestamp, null),
            )
            if (isResend && sentInfo != null) bumpSendCount(messageID)
            val message = finalizeSend(
                DirectSendSettlement(
                    messageID = messageID,
                    contactID = to.id,
                    radioID = to.radioID,
                    publicKey = to.publicKey,
                    sentInfo = sentInfo,
                    initialPathLength = initialPathLength,
                ),
            )
            if (isResend && sentInfo != null) statusBroadcaster.yield(MessageStatusEvent.Resent(messageID))
            message
        }
    } finally {
        withContext(NonCancellable) { stateMutex.withLock { inFlightRetries.remove(messageID) } }
    }
}

/** Bumps the persisted send count, tolerating a bookkeeping failure since the retransmission happened. */
private suspend fun MessageService.bumpSendCount(messageID: Uuid) {
    runReportingThrowable<Unit>(
        onFailure = { error ->
            logger.warning(dmResendSendCountBookkeepingFailLine(messageID, error.message ?: error.toString()))
        },
    ) {
        store.incrementMessageSendCount(messageID)
    }
}
