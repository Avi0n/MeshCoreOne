package one.meshcore.shared.room

import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.backup.RoomMessageDto
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.isRoom

/**
 * Ingests a `signedPlain` room message forwarded by the polling layer. Finds the room by the server's
 * 6-byte prefix (ignoring non-rooms), recovers a disconnected session, deduplicates on the shared key,
 * resolves the author by 4-byte prefix, saves, advances the sync watermark, and bumps unread when the
 * message is not our own. Returns the saved message, or null when it was not from a known room or a
 * duplicate.
 */
suspend fun RoomServerService.handleIncomingMessage(
    senderPublicKeyPrefix: ByteArray,
    timestamp: Long,
    authorPrefix: ByteArray,
    text: String,
): RoomMessageDto? {
    val remoteSession = store.fetchSessionByPrefix(senderPublicKeyPrefix)?.takeIf { it.isRoom }
        ?: return null

    if (!remoteSession.isConnected && store.markRoomSessionConnected(remoteSession.id)) {
        emit(RoomServerEvent.ConnectionRecovered(remoteSession.id))
    }

    suspend fun saveIfNew(): RoomMessageDto? {
        val dedupKey = RoomMessageDedup.generateDeduplicationKey(timestamp.toUInt(), authorPrefix, text)
        if (store.isDuplicateRoomMessage(remoteSession.id, dedupKey)) return null

        auditLogger.logRoomMessageReceived(
            roomPublicKey = senderPublicKeyPrefix,
            authorPrefix = authorPrefix,
            messageLength = text.length,
        )

        val isFromSelf = selfAuthored(authorPrefix)
        val authorName = store.findContactNameByKeyPrefix(authorPrefix)
        val message = RoomMessageDto(
            id = newId(),
            sessionID = remoteSession.id,
            authorKeyPrefix = authorPrefix,
            text = text,
            timestamp = timestamp,
            createdAt = now().toDouble(),
            isFromSelf = isFromSelf,
            deduplicationKey = dedupKey,
            statusRawValue = MessageStatus.DELIVERED.raw,
            retryAttempt = 0,
            maxRetryAttempts = 0,
            authorName = authorName,
        )
        store.saveRoomMessage(message)
        store.updateRoomActivity(remoteSession.id, syncTimestamp = timestamp)
        if (!isFromSelf) store.incrementRoomUnreadCount(remoteSession.id)
        emit(RoomServerEvent.MessageReceived(message, remoteSession.id))
        return message
    }
    return saveIfNew()
}

/** Room messages for [sessionID], optionally windowed. */
suspend fun RoomServerService.fetchMessages(
    sessionID: Uuid,
    limit: Int? = null,
    offset: Int? = null,
): List<RoomMessageDto> = store.fetchRoomMessages(sessionID, limit, offset)

/** Resets a room's unread counter (call when the conversation is viewed). */
suspend fun RoomServerService.markAsRead(sessionID: Uuid) = store.resetRoomUnreadCount(sessionID)

/** Marks unseen self-failed sends in this room seen (iOS `markFailedSendsSeen(roomSessionID:)`). */
suspend fun RoomServerService.markFailedSendsSeen(sessionID: Uuid) = store.markFailedSendsSeen(sessionID)

/** The room session with [sessionID], or null when missing / not a room. */
suspend fun RoomServerService.fetchSession(sessionID: Uuid): RemoteNodeSessionDto? =
    store.fetchSession(sessionID)?.takeIf { it.isRoom }

/** The room session addressed by [publicKeyPrefix] when it exists and is connected, else null. */
suspend fun RoomServerService.getConnectedSession(publicKeyPrefix: ByteArray): RemoteNodeSessionDto? =
    store.fetchSessionByPrefix(publicKeyPrefix)?.takeIf { it.isRoom && it.isConnected }

private suspend fun RoomServerService.selfAuthored(authorPrefix: ByteArray): Boolean {
    val self = mutex.withLock { selfPublicKeyPrefix } ?: return false
    val a = self.copyOf(minOf(AUTHOR_KEY_PREFIX_BYTES, self.size))
    val b = authorPrefix.copyOf(minOf(AUTHOR_KEY_PREFIX_BYTES, authorPrefix.size))
    return a.contentEquals(b)
}
