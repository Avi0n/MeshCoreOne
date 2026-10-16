package one.meshcore.shared.remotenode

import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.meshcore.firmwareCode

/**
 * The subset of the Swift `MeshCoreError` the remote-node layer distinguishes when a device command
 * fails. The [NodeSession] seam throws one of these (or its production adapter maps a bridge
 * `MeshBridgeException` to one); [RemoteNodeService] wraps it as [RemoteNodeError.SessionError]. Kept
 * narrow: the keep-alive classifier and failure-reason strings only need timeout / device-error /
 * not-connected apart.
 */
sealed class MeshError(message: String) : Exception(message) {
    /** A per-command response timeout. */
    object Timeout : MeshError("timeout")

    /** A firmware error carrying its `ProtocolError` sub-[code]. */
    data class DeviceError(val code: Int) : MeshError("device error $code")

    /** The transport is not connected. */
    object NotConnected : MeshError("not connected")

    /** The link dropped mid-command; [detail] carries the diagnostic. */
    data class ConnectionLost(val detail: String) : MeshError("connection lost: $detail")
}

/** Maps a JNI/bridge failure onto the typed [MeshError] remote-node callers classify on. */
fun MeshBridgeException.toMeshError(): MeshError = when (code) {
    BridgeErrorCode.DEVICE_ERROR -> MeshError.DeviceError(firmwareCode())
    BridgeErrorCode.TIMEOUT -> MeshError.Timeout
    BridgeErrorCode.NOT_CONNECTED,
    BridgeErrorCode.SESSION_NOT_STARTED,
    -> MeshError.NotConnected
    else -> MeshError.ConnectionLost(detail)
}

/**
 * Errors surfaced by [RemoteNodeService] and its callers, mirroring the Swift `RemoteNodeError`. The
 * developer-English [message] is the log/fallback string; UI surfaces map each case to localized copy.
 * [SessionError] wraps a lower-layer [MeshError].
 */
sealed class RemoteNodeError(message: String) : Exception(message) {
    object NotConnected : RemoteNodeError("Not connected to mesh device")
    data class LoginFailed(val reason: String) : RemoteNodeError("Login failed: $reason")
    data class SendFailed(val reason: String) : RemoteNodeError("Failed to send: $reason")
    object InvalidResponse : RemoteNodeError("Invalid response from remote node")
    object PermissionDenied : RemoteNodeError("Permission denied")
    object Timeout : RemoteNodeError("Request timed out")
    object SessionNotFound : RemoteNodeError("Remote node session not found")
    object PasswordNotFound : RemoteNodeError("Password not found in keychain")

    /** Keep-alive requires a direct routing path. */
    object FloodRouted : RemoteNodeError("Keep-alive requires direct routing path")
    object PathDiscoveryFailed : RemoteNodeError("Failed to establish direct path")
    object ContactNotFound : RemoteNodeError("Contact not found in database")

    /** The radio's contact table is full; a missing node cannot be auto-added. */
    object RadioContactsFull : RemoteNodeError("Radio contact list is full")

    /** Login cancelled by a duplicate attempt or shutdown. */
    object Cancelled : RemoteNodeError("Login cancelled")
    data class SessionError(val error: MeshError) : RemoteNodeError(error.message ?: "session error")

    val isRetryable: Boolean
        get() = when (this) {
            Timeout, NotConnected, FloodRouted -> true
            else -> false
        }
}

/** A mesh timeout stays retryable. Every other mesh failure stays a session error. */
internal fun remoteNodeFailure(error: MeshError): Nothing = when (error) {
    is MeshError.Timeout -> throw RemoteNodeError.Timeout
    else -> throw RemoteNodeError.SessionError(error)
}

/** A remote-node error passes through. A mesh error stays a session error. Anything else is a lost link. */
internal fun mapSendError(error: Throwable): Throwable = when (error) {
    is RemoteNodeError -> error
    is MeshError -> RemoteNodeError.SessionError(error)
    else -> RemoteNodeError.SessionError(MeshError.ConnectionLost(error.message ?: error.toString()))
}
