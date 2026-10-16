package one.meshcore.shared.persistence

import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.backup.RoomMessageDto

/**
 * Write-side DTO -> entity conversions for the Room-backed node/room session store. The typed
 * role/permission/notification fields collapse to their pinned raw columns (the inverse of the read-side
 * accessors in [RemoteNodeSessionEntity]); every other field forwards verbatim so a session round-trips
 * through Room unchanged. Room messages are field-identical to their entity, so both directions are plain.
 */
fun RemoteNodeSessionDto.toEntity(): RemoteNodeSessionEntity = RemoteNodeSessionEntity(
    id = id,
    radioID = radioID,
    publicKey = publicKey,
    name = name,
    roleRawValue = role.raw,
    latitude = latitude,
    longitude = longitude,
    isConnected = isConnected,
    permissionLevelRawValue = permissionLevel.raw,
    unreadCount = unreadCount,
    notificationLevelRawValue = notificationLevel.raw,
    isPinned = isPinned,
    neighborCount = neighborCount,
    lastSyncTimestamp = lastSyncTimestamp,
    lastConnectedDate = lastConnectedDate,
    lastBatteryMillivolts = lastBatteryMillivolts,
    lastUptimeSeconds = lastUptimeSeconds,
    lastNoiseFloor = lastNoiseFloor,
    lastRxAirtimeSeconds = lastRxAirtimeSeconds,
    lastMessageDate = lastMessageDate,
)

fun RoomMessageEntity.toDto(): RoomMessageDto = RoomMessageDto(
    id = id,
    sessionID = sessionID,
    authorKeyPrefix = authorKeyPrefix,
    text = text,
    timestamp = timestamp,
    createdAt = createdAt,
    isFromSelf = isFromSelf,
    deduplicationKey = deduplicationKey,
    statusRawValue = statusRawValue,
    retryAttempt = retryAttempt,
    maxRetryAttempts = maxRetryAttempts,
    failureSeen = failureSeen,
    authorName = authorName,
    ackCode = ackCode,
    roundTripTime = roundTripTime,
)

fun RoomMessageDto.toEntity(): RoomMessageEntity = RoomMessageEntity(
    id = id,
    sessionID = sessionID,
    authorKeyPrefix = authorKeyPrefix,
    text = text,
    timestamp = timestamp,
    createdAt = createdAt,
    isFromSelf = isFromSelf,
    deduplicationKey = deduplicationKey,
    statusRawValue = statusRawValue,
    retryAttempt = retryAttempt,
    maxRetryAttempts = maxRetryAttempts,
    failureSeen = failureSeen,
    authorName = authorName,
    ackCode = ackCode,
    roundTripTime = roundTripTime,
)
