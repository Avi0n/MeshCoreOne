package one.meshcore.shared.remotenode

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toLowercaseHex

private const val PUBLIC_KEY_SIZE: Int = 32
private const val LOG_KEY_PREFIX_BYTES: Int = 6

/**
 * Creates (or reuses) a saved session for a contact. An existing row for the same public key is reused
 * to preserve its login state and cached fields; duplicates are cleaned up. Rejects a plain chat contact.
 */
suspend fun RemoteNodeService.createSession(radioID: Uuid, contact: ContactDto): RemoteNodeSessionDto {
    val role = sessionRole(contact)
    requirePublicKeyLength(contact)

    val pubKeyHex = contact.publicKey.copyOf(minOf(LOG_KEY_PREFIX_BYTES, contact.publicKey.size)).toLowercaseHex()
    val existing = store.fetchSessionByPublicKey(contact.publicKey)
    if (existing != null) {
        logger.info(createSessionReusingLine(existing.id, pubKeyHex, existing.isConnected))
    } else {
        logger.info(createSessionCreatingLine(pubKeyHex))
    }
    val dto = makeSessionDto(radioID, contact, role, existing)
    store.saveSession(dto)
    store.cleanupDuplicateSessions(contact.publicKey, dto.id)
    val saved = store.fetchSessionByPublicKey(contact.publicKey) ?: run {
        logger.error("createSession: failed to fetch saved session for $pubKeyHex")
        throw RemoteNodeError.SessionNotFound
    }
    logger.info(createSessionSavedLine(saved.id, pubKeyHex))
    return saved
}

private fun RemoteNodeService.makeSessionDto(
    radioID: Uuid,
    contact: ContactDto,
    role: RemoteNodeRole,
    existing: RemoteNodeSessionDto?,
): RemoteNodeSessionDto = RemoteNodeSessionDto(
    id = existing?.id ?: newId(),
    radioID = radioID,
    publicKey = contact.publicKey,
    name = contact.displayName,
    role = role,
    latitude = contact.latitude,
    longitude = contact.longitude,
    isConnected = false,
    permissionLevel = existing?.permissionLevel ?: RoomPermissionLevel.GUEST,
    unreadCount = existing?.unreadCount ?: 0,
    notificationLevel = existing?.notificationLevel ?: NotificationLevel.ALL,
    isPinned = existing?.isPinned ?: false,
    neighborCount = existing?.neighborCount ?: 0,
    lastSyncTimestamp = existing?.lastSyncTimestamp ?: 0,
    lastConnectedDate = existing?.lastConnectedDate,
    lastBatteryMillivolts = existing?.lastBatteryMillivolts,
    lastUptimeSeconds = existing?.lastUptimeSeconds,
    lastNoiseFloor = existing?.lastNoiseFloor,
    lastRxAirtimeSeconds = existing?.lastRxAirtimeSeconds,
    lastMessageDate = existing?.lastMessageDate,
)

/** Removes a session and its stored password. */
suspend fun RemoteNodeService.removeSession(id: Uuid, publicKey: ByteArray) {
    stopKeepAlive(id)
    passwordStore.deletePassword(publicKey)
    store.deleteSession(id)
}

/** Marks a session disconnected without sending a logout packet. */
suspend fun RemoteNodeService.disconnect(sessionID: Uuid) {
    stopKeepAlive(sessionID)
    persistDisconnected(sessionID)
    emit(RemoteNodeEvent.SessionStateChanged(sessionID, isConnected = false))
}

private fun sessionRole(contact: ContactDto): RemoteNodeRole {
    val type = ContactType.fromRaw(contact.typeRawValue) ?: throw RemoteNodeError.InvalidResponse
    return RemoteNodeRole.fromContactType(type) ?: throw RemoteNodeError.InvalidResponse
}

private fun requirePublicKeyLength(contact: ContactDto) {
    if (contact.publicKey.size != PUBLIC_KEY_SIZE) {
        throw RemoteNodeError.LoginFailed(
            "Invalid public key length: expected $PUBLIC_KEY_SIZE bytes, got ${contact.publicKey.size}",
        )
    }
}
