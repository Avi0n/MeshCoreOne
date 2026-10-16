package one.meshcore.shared.room

import one.meshcore.shared.remotenode.MeshError

/**
 * Errors surfaced by [RoomServerService], mirroring the Swift `RoomServerError`. The developer-English
 * [message] is the log/fallback string; UI surfaces map each case to localized copy. [SessionError] wraps
 * a lower-layer [MeshError]. Room *authentication* failures surface as `RemoteNodeError` (from the shared
 * remote-node login path); these cases cover posting, receiving, and session lookups.
 */
sealed class RoomServerError(message: String) : Exception(message) {
    object NotConnected : RoomServerError("Not connected to device.")
    object SessionNotFound : RoomServerError("Room session not found.")
    data class SendFailed(val reason: String) : RoomServerError("Send failed: $reason")
    object PermissionDenied : RoomServerError("Permission denied.")
    object InvalidResponse : RoomServerError("Invalid response from device.")
    data class SessionError(val error: MeshError) : RoomServerError(error.message ?: "session error")
}
