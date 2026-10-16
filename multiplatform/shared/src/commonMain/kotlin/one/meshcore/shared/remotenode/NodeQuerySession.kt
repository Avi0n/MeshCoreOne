package one.meshcore.shared.remotenode

import one.meshcore.shared.meshcore.NeighboursResponse
import one.meshcore.shared.meshcore.OwnerInfoResponse
import one.meshcore.shared.meshcore.StatusResponse
import one.meshcore.shared.meshcore.TelemetryResponse

/**
 * The read frames a node session can request: status, telemetry, owner info, and one neighbours page.
 * [NodeSession] adds login, path, and CLI commands on top of these reads.
 */
interface NodeQuerySession {
    /** Requests a repeater/room status frame from [publicKey]. */
    suspend fun requestStatus(publicKey: ByteArray): StatusResponse

    /** Requests telemetry from [publicKey]; the LPP payload is decoded bridge-side. */
    suspend fun requestTelemetry(publicKey: ByteArray): TelemetryResponse

    /** Requests owner info (firmware version, node name, owner text) from [publicKey]. */
    suspend fun requestOwnerInfo(publicKey: ByteArray): OwnerInfoResponse

    /** Requests one page of a repeater's neighbours from [publicKey]. */
    suspend fun requestNeighbours(
        publicKey: ByteArray,
        count: Int,
        offset: Int,
        orderBy: Int,
        pubkeyPrefixLength: Int,
    ): NeighboursResponse
}
