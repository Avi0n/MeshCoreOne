package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.flow.update
import one.meshcore.shared.backup.NeighborSnapshotEntry
import one.meshcore.shared.meshcore.Neighbour
import one.meshcore.shared.meshcore.StatusResponse
import one.meshcore.shared.meshcore.TelemetryResponse
import one.meshcore.shared.remotenode.NeighborBaseline
import one.meshcore.shared.remotenode.NodeStatusMetrics
import one.meshcore.shared.remotenode.displayablePoints
import one.meshcore.shared.remotenode.previousStatusSnapshotOrNull
import one.meshcore.shared.remotenode.publicKeyPrefix
import one.meshcore.shared.remotenode.recordTelemetryPoints
import one.meshcore.shared.remotenode.runNodeSnapshot

internal suspend fun NodeStatusViewModel.applyStatus(response: StatusResponse, isCurrent: () -> Boolean = { true }) {
    val currentOwner = { !isClosed && isCurrent() }
    currentRemoteRequest(currentOwner) { Unit }
    if (!response.publicKeyPrefix.contentEquals(session.publicKeyPrefix)) {
        statusState.update { it.copy(isLoadingStatus = false) }
        return
    }
    val previous = currentRemoteRequest(currentOwner) {
        previousStatusSnapshotOrNull(
            snapshots = dependencies.nodeSnapshotService(),
            nodePublicKey = session.publicKey,
            before = dependencies.nowEpochSeconds(),
        )
    }
    statusState.update {
        it.copy(
            status = response,
            statusLoaded = true,
            isLoadingStatus = false,
            statusSectionError = null,
            previousStatusSnapshot = previous,
        )
    }
    currentRemoteRequest(currentOwner) { recordStatus(response) }
    snapshotsRecorded()
}

internal suspend fun NodeStatusViewModel.applyTelemetry(
    response: TelemetryResponse,
    isCurrent: () -> Boolean = {
        true
    },
) {
    val currentOwner = { !isClosed && isCurrent() }
    currentRemoteRequest(currentOwner) { Unit }
    val prefix = response.publicKeyPrefix
    val mismatch = prefix.isNotEmpty() && !prefix.contentEquals(session.publicKeyPrefix)
    if (mismatch) {
        statusState.update { it.copy(isLoadingTelemetry = false) }
        return
    }
    val points = response.points.displayablePoints()
    statusState.update {
        it.copy(
            isLoadingTelemetry = false,
            telemetryLoaded = true,
            telemetrySectionError = null,
            telemetryDataPoints = points,
        )
    }
    currentRemoteRequest(currentOwner) {
        recordTelemetryPoints(points, session.publicKey, dependencies.nodeSnapshotService())
    }
    snapshotsRecorded()
}

internal suspend fun NodeStatusViewModel.loadNeighborBaseline(): NeighborBaseline? {
    val snapshots = dependencies.nodeSnapshotService() ?: return null
    return runNodeSnapshot(onFailure = { null }) { snapshots.neighborBaseline(session.publicKey) }
}

/**
 * Persists the status capture. `rxAirtime` and `receiveErrors` ride every status frame but are
 * repeater-only metrics, so this repeater surface records them where the room surface passes its
 * posted/post-push counters instead (iOS `RepeaterStatusViewModel.handleStatusResponse`).
 */
private suspend fun NodeStatusViewModel.recordStatus(response: StatusResponse) {
    val snapshots = dependencies.nodeSnapshotService() ?: return
    runNodeSnapshot(onFailure = {}) {
        snapshots.recordSnapshot(
            nodePublicKey = session.publicKey,
            status = NodeStatusMetrics.fromStatus(
                response,
                rxAirtimeSeconds = response.rxAirtime,
                receiveErrors = response.receiveErrors.toLong(),
            ),
        )
        Unit
    }
}

internal suspend fun NodeStatusViewModel.recordNeighbors(neighbors: List<Neighbour>) {
    val snapshots = dependencies.nodeSnapshotService() ?: return
    runNodeSnapshot(onFailure = {}) {
        snapshots.recordSnapshot(
            nodePublicKey = session.publicKey,
            neighbors = neighbors.map { neighbour ->
                NeighborSnapshotEntry(
                    publicKeyPrefix = neighbour.publicKeyPrefix,
                    snr = neighbour.snr,
                    secondsAgo = neighbour.secondsAgo.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                )
            },
        )
        Unit
    }
}

internal fun NodeStatusViewModel.failStatus(error: NodeSectionError) {
    statusState.update { it.copy(isLoadingStatus = false, statusSectionError = error) }
}

internal fun Exception.toSectionError(): NodeSectionError {
    val text = message?.takeIf { it.isNotBlank() } ?: return NodeSectionError.Failed
    return NodeSectionError.Message(text)
}

internal fun NodeStatusViewModel.snapshotsRecorded() {
    statusState.update { it.copy(snapshotVersion = it.snapshotVersion + 1) }
}
