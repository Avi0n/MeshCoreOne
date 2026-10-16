package one.meshcore.shared.room.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.meshcore.StatusResponse
import one.meshcore.shared.meshcore.TelemetryResponse
import one.meshcore.shared.remotenode.MeshError
import one.meshcore.shared.remotenode.NodeStatusMetrics
import one.meshcore.shared.remotenode.RemoteNodeError
import one.meshcore.shared.remotenode.RemoteSectionRetryPolicy
import one.meshcore.shared.remotenode.displayablePoints
import one.meshcore.shared.remotenode.previousStatusSnapshotOrNull
import one.meshcore.shared.remotenode.publicKeyPrefix
import one.meshcore.shared.remotenode.recordTelemetryPoints
import one.meshcore.shared.remotenode.runNodeSnapshot
import one.meshcore.shared.remotenode.ui.NodeSectionError
import one.meshcore.shared.remotenode.ui.RemoteSectionRequest
import one.meshcore.shared.remotenode.ui.currentRemoteRequest
import one.meshcore.shared.room.RoomServerError
import kotlin.time.Duration

/** Live status and telemetry refresh for one room, including the snapshot each reply records. */
internal class RoomStatusRefresh(
    private val scope: CoroutineScope,
    private val sessionProvider: () -> RemoteNodeSessionDto,
    private val dependencies: RoomStatusViewModel.Dependencies,
    private val state: MutableStateFlow<RoomStatusState>,
    private val reloadHistory: () -> Unit,
) {
    private val session get() = sessionProvider()
    private val statusRequest = RemoteSectionRequest(scope)
    private val telemetryRequest = RemoteSectionRequest(scope)

    fun requestStatus() {
        val service = dependencies.roomAdminService() ?: return
        statusRequest.launch(
            begin = { state.update { it.copy(isLoadingStatus = true, statusSectionError = null) } },
            onCancel = { state.update { it.copy(isLoadingStatus = false) } },
            onFailure = { error -> failStatus(error.toRoomSectionError()) },
        ) {
            val isCurrent = { dependencies.roomAdminService() === service }
            val response =
                currentRemoteRequest(isCurrent) {
                    retrying { timeout ->
                        currentRemoteRequest(isCurrent) { service.requestStatus(session.id, timeout) }
                    }
                }
            applyStatus(response, isCurrent)
        }
    }

    fun requestTelemetry() {
        val service = dependencies.roomAdminService() ?: return
        telemetryRequest.launch(
            begin = { state.update { it.copy(isLoadingTelemetry = true, telemetrySectionError = null) } },
            onCancel = { state.update { it.copy(isLoadingTelemetry = false) } },
            onFailure = { error ->
                state.update {
                    it.copy(isLoadingTelemetry = false, telemetrySectionError = error.toRoomSectionError())
                }
            },
        ) {
            val isCurrent = { dependencies.roomAdminService() === service }
            val response =
                currentRemoteRequest(isCurrent) {
                    retrying { timeout ->
                        currentRemoteRequest(isCurrent) { service.requestTelemetry(session.id, timeout) }
                    }
                }
            applyTelemetry(response, isCurrent)
        }
    }

    private suspend fun applyTelemetry(response: TelemetryResponse, isCurrent: () -> Boolean) {
        currentRemoteRequest(isCurrent) { Unit }
        val prefix = response.publicKeyPrefix
        val mismatch = prefix.isNotEmpty() && !prefix.contentEquals(session.publicKeyPrefix)
        if (mismatch) {
            state.update { it.copy(isLoadingTelemetry = false) }
            return
        }
        val points = response.points.displayablePoints()
        state.update {
            it.copy(
                isLoadingTelemetry = false,
                telemetryLoaded = true,
                telemetrySectionError = null,
                telemetryDataPoints = points,
            )
        }
        currentRemoteRequest(isCurrent) {
            recordTelemetryPoints(points, session.publicKey, dependencies.nodeSnapshotService())
        }
        reloadHistory()
    }

    private suspend fun applyStatus(response: StatusResponse, isCurrent: () -> Boolean) {
        currentRemoteRequest(isCurrent) { Unit }
        if (!response.publicKeyPrefix.contentEquals(session.publicKeyPrefix)) {
            state.update { it.copy(isLoadingStatus = false) }
            return
        }
        val previous = currentRemoteRequest(isCurrent) {
            previousStatusSnapshotOrNull(
                snapshots = dependencies.nodeSnapshotService(),
                nodePublicKey = session.publicKey,
                before = dependencies.nowEpochSeconds(),
            )
        }
        state.update {
            it.copy(
                status = response,
                statusLoaded = true,
                isLoadingStatus = false,
                statusSectionError = null,
                previousStatusSnapshot = previous,
            )
        }
        captureStatus(response, isCurrent)
    }

    private fun captureStatus(response: StatusResponse, isCurrent: () -> Boolean) {
        val snapshots = dependencies.nodeSnapshotService() ?: return
        scope.launch {
            val recorded = currentRemoteRequest(isCurrent) {
                runNodeSnapshot(onFailure = { false }) {
                    snapshots.recordSnapshot(
                        nodePublicKey = session.publicKey,
                        status = NodeStatusMetrics.fromStatus(
                            status = response,
                            postedCount = response.roomServerPostedCount,
                            postPushCount = response.roomServerPostPushCount,
                        ),
                    )
                    true
                }
            }
            if (recorded) reloadHistory()
        }
    }

    private fun failStatus(error: NodeSectionError) {
        state.update { it.copy(isLoadingStatus = false, statusSectionError = error) }
    }

    private suspend fun <T> retrying(operation: suspend (timeout: Duration) -> T): T =
        RemoteSectionRetryPolicy.performWithTransientRetries(operation = operation)
}

private fun Exception.toRoomSectionError(): NodeSectionError = when (this) {
    is RoomServerError.SessionNotFound -> NodeSectionError.Failed
    else -> toSectionError()
}

private fun Exception.toSectionError(): NodeSectionError = when {
    isTimeout -> NodeSectionError.Timeout
    else -> message?.takeIf { it.isNotBlank() }?.let { NodeSectionError.Message(it) } ?: NodeSectionError.Failed
}

private val Exception.isTimeout: Boolean
    get() = this is MeshError.Timeout ||
        this is RemoteNodeError.Timeout ||
        (this is RoomServerError.SessionError && error is MeshError.Timeout)
