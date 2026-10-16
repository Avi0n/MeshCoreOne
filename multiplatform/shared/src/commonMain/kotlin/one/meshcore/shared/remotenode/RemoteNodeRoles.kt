package one.meshcore.shared.remotenode

import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.RoomPermissionLevel

/**
 * Behaviour the Swift `RemoteNodeRole`/`RoomPermissionLevel` enums carry as members. The raw enums live
 * in the shared model layer (pinned wire values); these helpers add the port's role/permission semantics
 * without reopening that file.
 */

/** The node role for a contact type, or null for a plain chat contact (which has no node role). */
fun RemoteNodeRole.Companion.fromContactType(type: ContactType): RemoteNodeRole? = when (type) {
    ContactType.REPEATER -> RemoteNodeRole.REPEATER
    ContactType.ROOM -> RemoteNodeRole.ROOM_SERVER
    ContactType.CHAT -> null
}

// The enum's built-in Comparable orders by declaration (GUEST < READ_WRITE < ADMIN), which matches the
// Swift raw-value ordering the reconnect degrade check relies on, so `>=`/`>` are used directly.

/** Whether this level can post messages: read-write or higher. */
val RoomPermissionLevel.canPost: Boolean
    get() = this >= RoomPermissionLevel.READ_WRITE

/** Whether this level grants admin access. */
val RoomPermissionLevel.isAdmin: Boolean
    get() = this == RoomPermissionLevel.ADMIN

/**
 * Sort order for a repeater's neighbour list. Swift `NeighborSortOrder`, `UInt8`-raw. Default is
 * [NEWEST_FIRST].
 */
enum class NeighborSortOrder(val raw: Int) {
    NEWEST_FIRST(0),
    OLDEST_FIRST(1),
    STRONGEST_FIRST(2),
    WEAKEST_FIRST(3),
    ;

    companion object {
        val DEFAULT = NEWEST_FIRST

        fun fromRaw(raw: Int): NeighborSortOrder? = entries.firstOrNull { it.raw == raw }
    }
}
