package one.meshcore.shared.persistence

import one.meshcore.shared.backup.ContactDto

/**
 * [ContactEntity] <-> [ContactDto] conversion for the Room-backed chat/contact stores. The contact type
 * stays a raw `typeRawValue` on both sides (never routed through the normalizing accessor), matching the
 * Swift producer that ships the verbatim wire type byte.
 */
fun ContactEntity.toDto(): ContactDto = ContactDto(
    id = id,
    radioID = radioID,
    publicKey = publicKey,
    name = name,
    typeRawValue = typeRawValue,
    flags = flags,
    outPathLength = outPathLength,
    outPath = outPath,
    lastAdvertTimestamp = lastAdvertTimestamp,
    latitude = latitude,
    longitude = longitude,
    lastModified = lastModified,
    lastHeardTimestamp = lastHeardTimestamp,
    isBlocked = isBlocked,
    isMuted = isMuted,
    isFavorite = isFavorite,
    isPinned = isPinned,
    unreadCount = unreadCount,
    unreadMentionCount = unreadMentionCount,
    nickname = nickname,
    lastMessageDate = lastMessageDate,
    ocvPreset = ocvPreset,
    customOCVArrayString = customOCVArrayString,
    avatarImageData = avatarImageData,
)

fun ContactDto.toEntity(): ContactEntity = ContactEntity(
    id = id,
    radioID = radioID,
    publicKey = publicKey,
    name = name,
    typeRawValue = typeRawValue,
    flags = flags,
    outPathLength = outPathLength,
    outPath = outPath,
    lastAdvertTimestamp = lastAdvertTimestamp,
    latitude = latitude,
    longitude = longitude,
    lastModified = lastModified,
    lastHeardTimestamp = lastHeardTimestamp,
    isBlocked = isBlocked,
    isMuted = isMuted,
    isFavorite = isFavorite,
    isPinned = isPinned,
    unreadCount = unreadCount,
    unreadMentionCount = unreadMentionCount,
    nickname = nickname,
    lastMessageDate = lastMessageDate,
    ocvPreset = ocvPreset,
    customOCVArrayString = customOCVArrayString,
    avatarImageData = avatarImageData,
)
