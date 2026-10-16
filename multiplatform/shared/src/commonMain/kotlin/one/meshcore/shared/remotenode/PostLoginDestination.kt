package one.meshcore.shared.remotenode

import one.meshcore.shared.model.RemoteNodeRole

/** Why the node-auth sheet was opened: join a room/repeater, manage it, or open telemetry/status. */
enum class NodeAuthPurpose {
    JOIN,
    MANAGE,
    TELEMETRY,
}

/** Where a successful login should land, matching iOS ContactDetailView post-auth routing. */
enum class PostLoginDestination {
    ROOM,
    SETTINGS,
    STATUS,
}

/**
 * Join always opens the room thread or repeater status. Telemetry always opens status.
 * Manage opens settings only for an admin session; a guest lands on status.
 */
fun postLoginDestination(role: RemoteNodeRole, isAdmin: Boolean, purpose: NodeAuthPurpose): PostLoginDestination {
    if (purpose == NodeAuthPurpose.TELEMETRY) {
        return PostLoginDestination.STATUS
    }
    if (purpose == NodeAuthPurpose.JOIN) {
        return if (role == RemoteNodeRole.ROOM_SERVER) {
            PostLoginDestination.ROOM
        } else {
            PostLoginDestination.STATUS
        }
    }
    return if (isAdmin) PostLoginDestination.SETTINGS else PostLoginDestination.STATUS
}
