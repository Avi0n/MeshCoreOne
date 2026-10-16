package one.meshcore.shared.remotenode

import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.runExceptionFallback

/** A snapshot-store fault uses the caller's fallback; cancellation still stops the request. */
internal suspend fun <T> runNodeSnapshot(onFailure: () -> T, block: suspend () -> T): T =
    runExceptionFallback(onFailure, block)

/** The prior status snapshot, or null when the store is absent or the read fails. */
internal suspend fun previousStatusSnapshotOrNull(
    snapshots: NodeSnapshotService?,
    nodePublicKey: ByteArray,
    before: Double,
): NodeStatusSnapshotDto? {
    if (snapshots == null) return null
    return runNodeSnapshot(onFailure = { null }) {
        snapshots.previousStatusSnapshot(nodePublicKey = nodePublicKey, before = before)
    }
}
