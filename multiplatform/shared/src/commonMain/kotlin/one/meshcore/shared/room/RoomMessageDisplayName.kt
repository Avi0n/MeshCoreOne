package one.meshcore.shared.room

import one.meshcore.shared.backup.RoomMessageDto
import one.meshcore.shared.model.toUppercaseHex

/**
 * Display name for a room-message author: the resolved name, or the key prefix as uppercase hex when
 * the name is unknown. Mirrors iOS `RoomMessage.authorDisplayName`.
 */
val RoomMessageDto.authorDisplayName: String
    get() = authorName ?: authorKeyPrefix.toUppercaseHex()
