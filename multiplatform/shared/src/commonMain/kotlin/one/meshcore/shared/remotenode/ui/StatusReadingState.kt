package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.meshcore.StatusResponse
import one.meshcore.shared.remotenode.LPPDataPoint
import one.meshcore.shared.remotenode.NodeLocationFix

/**
 * Status and telemetry fields the repeater and room screens both keep. Location and metric deltas
 * are derived here so the two screens follow the same rules.
 */
interface StatusReadingState {
    val telemetryDataPoints: List<LPPDataPoint>
    val status: StatusResponse?
    val previousStatusSnapshot: NodeStatusSnapshotDto?

    val currentLocationFix: NodeLocationFix?
        get() = NodeLocationFix.primaryFix(telemetryDataPoints)

    val statusDeltas: StatusMetricDeltas
        get() {
            val current = status ?: return StatusMetricDeltas()
            return NodeStatusDeltas.fromStatus(current, previousStatusSnapshot)
        }
}
