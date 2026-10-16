package one.meshcore.shared.room

import one.meshcore.shared.meshcore.StatusResponse
import one.meshcore.shared.meshcore.TelemetryResponse
import kotlin.time.Duration

/**
 * The device-session seam [RoomAdminService] uses for a room's status, telemetry, and CLI. A narrow
 * room-in-package port over the androidMain-only `MeshCoreSession`, mirroring
 * [one.meshcore.shared.remotenode.NodeSession]: it carries the admin/CLI ops the shared login seam does
 * not, so the service stays in commonMain and is independently testable.
 * [one.meshcore.shared.connection.RoomAdminSessionAdapter] bridges the live session and applies the
 * request-response timeout ceiling.
 *
 * A command failure raises a [one.meshcore.shared.remotenode.MeshError].
 */
interface RoomAdminSession {
    /**
     * Requests a room status frame from [publicKey], failing after [timeout]. The target is a room, so the
     * frame comes back already decoded with the room-server layout and its posted/post-push counters set.
     */
    suspend fun requestStatus(publicKey: ByteArray, timeout: Duration): StatusResponse

    /** Requests a telemetry frame from [publicKey], failing after [timeout]. */
    suspend fun requestTelemetry(publicKey: ByteArray, timeout: Duration): TelemetryResponse

    /** Sends [command] to [publicKey] and returns the content-matched CLI response, failing after [timeout]. */
    suspend fun sendCommand(publicKey: ByteArray, command: String, timeout: Duration): String

    /** Sends [command] to [publicKey] and returns the FIFO-matched CLI response, failing after [timeout]. */
    suspend fun sendRawCommand(publicKey: ByteArray, command: String, timeout: Duration): String
}
