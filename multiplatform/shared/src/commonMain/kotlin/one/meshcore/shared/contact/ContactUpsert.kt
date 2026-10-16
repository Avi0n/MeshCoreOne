package one.meshcore.shared.contact

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid

/** Bit 0 of the contact flags byte: favorite/pinned. Device-synced and preserved across frame updates. */
private const val FAVORITE_FLAG: Int = 0x01

/**
 * The frame-to-row merge every [ContactStore] must apply so the in-memory and Room backings agree, and
 * the load-bearing three-axis identity invariant is enforced in one place: a row is keyed by
 * `(radioID, publicKey)`, both frozen after creation. Kotlin port of the Swift `Contact(radioID:from:)`
 * insert and `Contact.update(from:)` merge.
 */
object ContactUpsert {
    /**
     * Builds a fresh contact row from a device [frame]. [radioID] is the data-partition key supplied by
     * the caller from the live `device.radioID` — never minted here; [id] is the local row handle the
     * store assigns. The app-side favorite bool is seeded from flags bit 0.
     */
    fun insert(radioID: Uuid, id: Uuid, frame: ContactFrame): ContactDto = ContactDto(
        id = id,
        radioID = radioID,
        publicKey = frame.publicKey,
        name = frame.name,
        typeRawValue = frame.typeRawValue,
        flags = frame.flags,
        outPathLength = frame.outPathLength,
        outPath = frame.outPath,
        lastAdvertTimestamp = frame.lastAdvertTimestamp,
        latitude = frame.latitude,
        longitude = frame.longitude,
        lastModified = frame.lastModified,
        isBlocked = false,
        isMuted = false,
        isFavorite = (frame.flags and FAVORITE_FLAG) != 0,
        unreadCount = 0,
    )

    /**
     * Merges a device [frame] into an [existing] row. `radioID` and `publicKey` stay frozen (identity),
     * and the local favorite bit (flags bit 0) plus the app-side [ContactDto.isFavorite] bool survive the
     * radio re-reporting the contact: bit 0 comes from the existing flags, bits 1-7 from the frame. All
     * other app-side metadata (nickname, blocked, muted, unread, OCV, last-message) is untouched.
     */
    fun merge(existing: ContactDto, frame: ContactFrame): ContactDto = existing.copy(
        name = frame.name,
        typeRawValue = frame.typeRawValue,
        flags = (existing.flags and FAVORITE_FLAG) or (frame.flags and FAVORITE_FLAG.inv()),
        outPathLength = frame.outPathLength,
        outPath = frame.outPath,
        lastAdvertTimestamp = frame.lastAdvertTimestamp,
        latitude = frame.latitude,
        longitude = frame.longitude,
        lastModified = frame.lastModified,
    )

    /**
     * Applies a full [dto] onto an [existing] row while keeping `radioID` and `publicKey` frozen to the
     * existing identity. Mirrors the Swift `Contact.apply(_:)` upsert, which never rewrites the identity
     * axes.
     */
    fun apply(existing: ContactDto, dto: ContactDto): ContactDto = dto.copy(
        id = existing.id,
        radioID = existing.radioID,
        publicKey = existing.publicKey,
        isPinned = existing.isPinned,
    )
}
