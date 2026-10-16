package one.meshcore.shared.persistence

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.RemoteNodeSessionDto

/**
 * Read-side [ChannelEntity] -> [ChannelDto] and [RemoteNodeSessionEntity] -> [RemoteNodeSessionDto]
 * conversions for the conversation-list store. The flood-scope mode column forwards verbatim on read
 * and through [ChannelDto.resolvedFloodScopeModeRawValue] on write; the typed
 * notification/role/permission accessors decode the raw columns (the `-1` unmigrated sentinel folds to
 * its default), matching the Swift producer.
 */
fun ChannelEntity.toDto(): ChannelDto = ChannelDto(
    id = id,
    radioID = radioID,
    index = index,
    name = name,
    secret = secret,
    isEnabled = isEnabled,
    unreadCount = unreadCount,
    unreadMentionCount = unreadMentionCount,
    notificationLevel = notificationLevel,
    isPinned = isPinned,
    floodScopeModeRawValue = floodScopeModeRawValue,
    regionScope = regionScope,
    lastMessageDate = lastMessageDate,
)

fun ChannelDto.toEntity(): ChannelEntity = ChannelEntity(
    id = id,
    radioID = radioID,
    index = index,
    name = name,
    secret = secret,
    isEnabled = isEnabled,
    unreadCount = unreadCount,
    unreadMentionCount = unreadMentionCount,
    notificationLevelRawValue = notificationLevel.raw,
    isPinned = isPinned,
    floodScopeModeRawValue = resolvedFloodScopeModeRawValue,
    regionScope = regionScope,
    lastMessageDate = lastMessageDate,
)

fun RemoteNodeSessionEntity.toDto(): RemoteNodeSessionDto = RemoteNodeSessionDto(
    id = id,
    radioID = radioID,
    publicKey = publicKey,
    name = name,
    role = role,
    latitude = latitude,
    longitude = longitude,
    isConnected = isConnected,
    permissionLevel = permissionLevel,
    unreadCount = unreadCount,
    notificationLevel = notificationLevel,
    isPinned = isPinned,
    neighborCount = neighborCount,
    lastSyncTimestamp = lastSyncTimestamp,
    lastConnectedDate = lastConnectedDate,
    lastBatteryMillivolts = lastBatteryMillivolts,
    lastUptimeSeconds = lastUptimeSeconds,
    lastNoiseFloor = lastNoiseFloor,
    lastRxAirtimeSeconds = lastRxAirtimeSeconds,
    // Pre-migration rows stored activity only on lastSyncTimestamp. Export and the
    // conversation list both go through this DTO, so omit would sort the room as
    // never-messaged after a restore (iOS RemoteNodeSessionDTO.init(from:)).
    lastMessageDate = lastMessageDate ?: lastSyncTimestamp.takeIf { it > 0 }?.toDouble(),
)
