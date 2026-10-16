package one.meshcore.shared.room

import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.RoomMessageDto
import one.meshcore.shared.chat.ChatConstants
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.canPost

private const val SELF_AUTHOR_NAME = "Me"

/** Sets the self public-key prefix from SelfInfo; only the first [AUTHOR_KEY_PREFIX_BYTES] are kept. */
suspend fun RoomServerService.setSelfPublicKeyPrefix(prefix: ByteArray) = mutex.withLock {
    selfPublicKeyPrefix = prefix.copyOf(minOf(AUTHOR_KEY_PREFIX_BYTES, prefix.size))
    Unit
}

/**
 * Posts [text] to a room. Requires `canPost` else [RoomServerError.PermissionDenied]. Saves a `pending`,
 * self-authored message immediately (optimistic UI) and returns it, then sends in the background: an ACK
 * resolves to `delivered`, exhausted retries with no ACK to `sent` (not failed — the packet likely
 * reached the server), and a thrown send to `failed`.
 */
suspend fun RoomServerService.postMessage(sessionID: Uuid, text: String): RoomMessageDto {
    val remoteSession = store.fetchSession(sessionID) ?: throw RoomServerError.SessionNotFound
    if (!remoteSession.canPost) throw RoomServerError.PermissionDenied
    requireRoomTextFits(text)

    val timestamp = now()
    val authorPrefix = mutex.withLock { selfPublicKeyPrefix } ?: ByteArray(AUTHOR_KEY_PREFIX_BYTES)
    val message = buildLocalMessage(sessionID, authorPrefix, text, timestamp)
    store.saveRoomMessage(message)
    auditLogger.logRoomMessagePosted(publicKey = remoteSession.publicKey, messageLength = text.length)

    scope.launch { runSend(message.id, sessionID, remoteSession.publicKey, text, timestamp) }

    return message
}

/**
 * Retries a `failed` message. Rejects a concurrent retry for the same id, bumps the attempt, re-marks it
 * `pending`, re-sends inline, and returns the message with its resolved status.
 */
suspend fun RoomServerService.retryMessage(id: Uuid): RoomMessageDto {
    val claimed = mutex.withLock { inFlightRetries.add(id) }
    requireRetryClaimed(claimed)

    try {
        val message = requireFailedRoomMessage(store.fetchRoomMessage(id))
        val remoteSession = store.fetchSession(message.sessionID) ?: throw RoomServerError.SessionNotFound

        store.updateRoomMessageRetryStatus(
            id = id,
            status = MessageStatus.PENDING,
            retryAttempt = message.retryAttempt + 1,
            maxRetryAttempts = config.maxAttempts,
        )
        emit(RoomServerEvent.StatusUpdated(id, MessageStatus.PENDING))

        runSend(id, message.sessionID, remoteSession.publicKey, message.text, now())

        return messageAfterRetry(store.fetchRoomMessage(id))
    } finally {
        mutex.withLock { inFlightRetries.remove(id) }
    }
}

private fun RoomServerService.buildLocalMessage(
    sessionID: Uuid,
    authorPrefix: ByteArray,
    text: String,
    timestamp: Long,
): RoomMessageDto = RoomMessageDto(
    id = newId(),
    sessionID = sessionID,
    authorKeyPrefix = authorPrefix,
    text = text,
    timestamp = timestamp,
    createdAt = timestamp.toDouble(),
    isFromSelf = true,
    deduplicationKey = RoomMessageDedup.generateDeduplicationKey(timestamp.toUInt(), authorPrefix, text),
    statusRawValue = MessageStatus.PENDING.raw,
    retryAttempt = 0,
    maxRetryAttempts = config.maxAttempts,
    authorName = SELF_AUTHOR_NAME,
)

private fun requireRoomTextFits(text: String) {
    if (text.encodeToByteArray().size > ChatConstants.MAX_DIRECT_MESSAGE_LENGTH_BYTES) {
        throw RoomServerError.SendFailed("Message exceeds the 150-byte limit.")
    }
}

private fun requireRetryClaimed(claimed: Boolean) {
    if (!claimed) throw RoomServerError.SendFailed("Retry already in progress")
}

private fun requireFailedRoomMessage(message: RoomMessageDto?): RoomMessageDto {
    val found = message ?: throw RoomServerError.SendFailed("Message not found")
    if (found.statusRawValue != MessageStatus.FAILED.raw) {
        throw RoomServerError.SendFailed("Message is not in failed state")
    }
    return found
}

private fun messageAfterRetry(message: RoomMessageDto?): RoomMessageDto =
    message ?: throw RoomServerError.SendFailed("Failed to fetch message after retry")
