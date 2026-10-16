package one.meshcore.shared.contact

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.toUppercaseHex

/** Encoded `outPathLength` byte meaning "flood routing"; mirrors the firmware `floodPathSentinel`. */
const val CONTACT_FLOOD_PATH_SENTINEL: Int = 0xFF

/**
 * A contact record moving between the device session and persistence. The Kotlin port of the Swift
 * `ContactFrame`: [typeRawValue] preserves the verbatim wire type byte so a contact carrying a type not
 * modeled by [ContactType] round-trips to the device instead of being coerced, while [type] is the
 * decoded accessor. Bridges to/from the session's [MeshContact].
 */
data class ContactFrame(
    val publicKey: ByteArray,
    val type: ContactType,
    val flags: Int,
    val outPathLength: Int,
    val outPath: ByteArray,
    val name: String,
    val lastAdvertTimestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val lastModified: Long,
    /** The raw 1-byte type value; defaults to [type]'s raw so an unmodeled byte can be preserved. */
    val typeRawValue: Int = type.raw,
) {
    override fun equals(other: Any?): Boolean = this === other ||
        (
            other is ContactFrame &&
                publicKey.contentEquals(other.publicKey) &&
                type == other.type &&
                typeRawValue == other.typeRawValue &&
                flags == other.flags &&
                outPathLength == other.outPathLength &&
                outPath.contentEquals(other.outPath) &&
                name == other.name &&
                lastAdvertTimestamp == other.lastAdvertTimestamp &&
                latitude == other.latitude &&
                longitude == other.longitude &&
                lastModified == other.lastModified
            )

    override fun hashCode(): Int {
        var result = publicKey.contentHashCode()
        result = 31 * result + typeRawValue
        result = 31 * result + flags
        result = 31 * result + outPathLength
        result = 31 * result + outPath.contentHashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + lastAdvertTimestamp.hashCode()
        result = 31 * result + latitude.hashCode()
        result = 31 * result + longitude.hashCode()
        result = 31 * result + lastModified.hashCode()
        return result
    }
}

/** Converts a [ContactFrame] to a [MeshContact] for session commands. */
fun ContactFrame.toMeshContact(): MeshContact = MeshContact(
    id = publicKey.toUppercaseHex(),
    publicKey = publicKey,
    typeRawValue = typeRawValue,
    flags = flags,
    outPathLength = outPathLength,
    outPath = outPath,
    advertisedName = name,
    lastAdvertisement = lastAdvertTimestamp,
    latitude = latitude,
    longitude = longitude,
    lastModified = lastModified,
)

/**
 * Converts a persisted contact to a [MeshContact] for a flags command. [flags] carries the row's
 * current flags; the new flags are passed separately to `changeContactFlags`.
 */
fun ContactDto.toMeshContact(): MeshContact = MeshContact(
    id = publicKey.toUppercaseHex(),
    publicKey = publicKey,
    typeRawValue = typeRawValue,
    flags = flags,
    outPathLength = outPathLength,
    outPath = outPath,
    advertisedName = name,
    lastAdvertisement = lastAdvertTimestamp,
    latitude = latitude,
    longitude = longitude,
    lastModified = lastModified,
)

/** Converts a device [MeshContact] to a [ContactFrame] for persistence, preserving the raw type byte. */
fun MeshContact.toContactFrame(): ContactFrame = ContactFrame(
    publicKey = publicKey,
    type = ContactType.fromRaw(typeRawValue) ?: ContactType.CHAT,
    typeRawValue = typeRawValue,
    flags = flags,
    outPathLength = outPathLength,
    outPath = outPath,
    name = advertisedName,
    lastAdvertTimestamp = lastAdvertisement,
    latitude = latitude,
    longitude = longitude,
    lastModified = lastModified,
)

/**
 * A frame for this contact with the path reset to flood routing. A contact the radio no longer knows
 * has no valid stored path, so flooding is the only route that can reach it; the firmware rediscovers a
 * direct path from the first response. Preserves the raw type byte and identity; only the route and
 * [lastModified] change. Mirrors the Swift `Contact.floodedContactFrame(asOf:)`.
 */
fun ContactDto.floodedContactFrame(asOf: Long): ContactFrame = ContactFrame(
    publicKey = publicKey,
    type = ContactType.fromRaw(typeRawValue) ?: ContactType.CHAT,
    typeRawValue = typeRawValue,
    flags = flags,
    outPathLength = CONTACT_FLOOD_PATH_SENTINEL,
    outPath = ByteArray(0),
    name = name,
    lastAdvertTimestamp = lastAdvertTimestamp,
    latitude = latitude,
    longitude = longitude,
    lastModified = asOf,
)
