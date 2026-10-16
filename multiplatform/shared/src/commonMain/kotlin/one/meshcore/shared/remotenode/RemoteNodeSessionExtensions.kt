package one.meshcore.shared.remotenode

import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.toUppercaseHex

/** The 6-byte prefix length the login/CLI addressing and reconciliation paths key on. */
internal const val PUBLIC_KEY_PREFIX_LENGTH: Int = 6

private const val LATITUDE_LIMIT = 90.0
private const val LONGITUDE_LIMIT = 180.0

/** A validated node location, mirroring the Swift `CLLocationCoordinate2D` the session exposes. */
data class NodeCoordinate(val latitude: Double, val longitude: Double)

/** Leading bytes used to address a node, capped at [PUBLIC_KEY_PREFIX_LENGTH]. */
internal fun ByteArray.addressingPrefix(): ByteArray = copyOf(minOf(PUBLIC_KEY_PREFIX_LENGTH, size))

/** The 6-byte public-key prefix used for addressing, mirroring `RemoteNodeSessionDTO.publicKeyPrefix`. */
val RemoteNodeSessionDto.publicKeyPrefix: ByteArray
    get() = publicKey.addressingPrefix()

/** Uppercase hex of the full public key. */
val RemoteNodeSessionDto.publicKeyHex: String
    get() = publicKey.toUppercaseHex()

val RemoteNodeSessionDto.isRoom: Boolean
    get() = role == RemoteNodeRole.ROOM_SERVER

val RemoteNodeSessionDto.isRepeater: Boolean
    get() = role == RemoteNodeRole.REPEATER

/** Whether the user can post (room-specific): a room session at read-write or higher. */
val RemoteNodeSessionDto.canPost: Boolean
    get() = isRoom && permissionLevel.canPost

val RemoteNodeSessionDto.isAdmin: Boolean
    get() = permissionLevel.isAdmin

val RemoteNodeSessionDto.isMuted: Boolean
    get() = notificationLevel == NotificationLevel.MUTED

/**
 * Whether the node reported a usable location. Latitude/longitude default to (0,0) when GPS was never
 * shared, so the sentinel and validity check are guarded here, mirroring `RemoteNodeSessionDTO.hasLocation`.
 */
val RemoteNodeSessionDto.hasLocation: Boolean
    get() {
        val hasNonZero = latitude != 0.0 || longitude != 0.0
        if (!hasNonZero) return false
        return isValidCoordinate(latitude, longitude)
    }

/** The node's coordinate, or null when no usable location was reported. */
val RemoteNodeSessionDto.coordinate: NodeCoordinate?
    get() = if (hasLocation) NodeCoordinate(latitude, longitude) else null

// The Swift `with(notificationLevel:)`/`with(isPinned:)` copy helpers are unneeded here: a data-class
// `copy(notificationLevel = ...)` / `copy(isPinned = ...)` is the idiomatic equivalent.

private fun isValidCoordinate(latitude: Double, longitude: Double): Boolean {
    if (latitude.isNaN() || longitude.isNaN()) return false
    return latitude in -LATITUDE_LIMIT..LATITUDE_LIMIT && longitude in -LONGITUDE_LIMIT..LONGITUDE_LIMIT
}
