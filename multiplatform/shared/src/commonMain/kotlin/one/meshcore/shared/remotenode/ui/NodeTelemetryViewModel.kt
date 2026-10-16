package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.lifecycle.OwnedJobs
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.meshcore.TelemetryResponse
import one.meshcore.shared.remotenode.LPPDataPoint
import one.meshcore.shared.remotenode.MeshError
import one.meshcore.shared.remotenode.NodeLocationFix
import one.meshcore.shared.remotenode.NodeSnapshotService
import one.meshcore.shared.remotenode.RemoteNodeError
import one.meshcore.shared.remotenode.RemoteSectionRetryPolicy
import one.meshcore.shared.remotenode.displayablePoints
import one.meshcore.shared.remotenode.recordTelemetryReading
import one.meshcore.shared.remotenode.telemetryReadingOrNull
import one.meshcore.shared.remotenode.toMeshError

/**
 * Live request-telemetry for a contact (iOS `NodeTelemetryViewModel`): expand triggers the first
 * request; reload re-fetches. Takes the bridge-decoded [TelemetryResponse.points], filters channel 0,
 * and records numeric snapshot entries when a [NodeSnapshotService] is available.
 */
class NodeTelemetryViewModel(
    scope: CoroutineScope,
    private val publicKey: ByteArray,
    private val dependencies: Dependencies,
) {
    class Dependencies(
        /** Live `MeshSession.requestTelemetry`; null means the connection retired. */
        val requestTelemetry: () -> (suspend (ByteArray) -> TelemetryResponse)?,
        val nodeSnapshotService: () -> NodeSnapshotService? = { null },
        /** Expected 6-byte public-key prefix; null accepts any prefix (tests / unknown identity). */
        val expectedPrefix: ByteArray? = null,
        /** Contact's OCV curve for live voltage captions (iOS `helper.ocvValues`). */
        val ocvValues: List<Int> = OCVPreset.LI_ION.ocvArray,
    )

    private val _state = MutableStateFlow(NodeTelemetryState(ocvValues = dependencies.ocvValues))
    private val jobs = OwnedJobs(scope)
    val state: StateFlow<NodeTelemetryState> = _state.asStateFlow()

    private var job: Job? = null

    fun setExpanded(expanded: Boolean) {
        if (jobs.isClosed) return
        _state.update { it.copy(expanded = expanded) }
        val current = _state.value
        if (expanded && !current.loaded && !current.isLoading) {
            requestTelemetry()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun requestTelemetry() {
        if (jobs.isClosed) return
        job?.cancel()
        _state.update { it.copy(isLoading = true, sectionError = null) }
        job = jobs.launch {
            try {
                // A firmware "no response yet" means the multi-hop reply is still in flight, so the request
                // is re-issued inside the shared budget instead of failing the section on the first reply.
                val response = RemoteSectionRetryPolicy.performWithTransientRetries {
                    val request = dependencies.requestTelemetry() ?: throw MeshError.NotConnected
                    request(publicKey)
                }
                applyResponse(response)
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(isLoading = false) }
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(isLoading = false, sectionError = error.toTelemetrySectionError())
                }
            }
        }
    }

    /** Stops a request owned by a telemetry sheet that has left composition. */
    fun close() {
        jobs.close()
        job = null
    }

    private fun applyResponse(response: TelemetryResponse) {
        val expected = dependencies.expectedPrefix
        val prefixMismatch = expected != null &&
            response.publicKeyPrefix.isNotEmpty() &&
            !response.publicKeyPrefix.contentEquals(expected)
        if (prefixMismatch) {
            // Prefix mismatch: ignore frame (another node's response).
            _state.update { it.copy(isLoading = false) }
        } else {
            val points = response.points.displayablePoints()
            _state.update {
                it.copy(
                    isLoading = false,
                    loaded = true,
                    sectionError = null,
                    dataPoints = points,
                    currentLocationFix = NodeLocationFix.primaryFix(points),
                )
            }
            recordSnapshotIfNeeded(points)
        }
    }

    /**
     * Records the reading. A node reporting a GPS fix but no numeric sensors still gets a snapshot, so its
     * location history fills from the telemetry sheet the same way it does from the status screen.
     */
    private fun recordSnapshotIfNeeded(points: List<LPPDataPoint>) {
        val reading = points.telemetryReadingOrNull() ?: return
        val snapshots = dependencies.nodeSnapshotService() ?: return
        jobs.launch {
            snapshots.recordTelemetryReading(publicKey, reading)
        }
    }
}

/** Observable state for [NodeTelemetryScreen]. */
data class NodeTelemetryState(
    val expanded: Boolean = false,
    val isLoading: Boolean = false,
    val loaded: Boolean = false,
    val sectionError: NodeSectionError? = null,
    val dataPoints: List<LPPDataPoint> = emptyList(),
    val ocvValues: List<Int> = OCVPreset.LI_ION.ocvArray,
    /** Plottable GPS fix from this reply, driving View on Map (iOS `helper.currentLocationFix`). */
    val currentLocationFix: NodeLocationFix? = null,
)

private fun Exception.toTelemetrySectionError(): NodeSectionError {
    val mapped = (this as? MeshBridgeException)?.toMeshError() ?: this
    return when (mapped) {
        is MeshError.Timeout, is RemoteNodeError.Timeout -> NodeSectionError.Timeout
        is MeshError.NotConnected -> NodeSectionError.Failed
        else -> {
            val text = mapped.message?.takeIf { it.isNotBlank() }
            if (text != null) NodeSectionError.Message(text) else NodeSectionError.Failed
        }
    }
}
