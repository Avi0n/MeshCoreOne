package one.meshcore.shared.ui

import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.runExceptionFallback

/**
 * Live telemetry history for one contact. A fetch fault falls through to demo samples when demo
 * mode is on, and otherwise shows nothing.
 */
internal suspend fun telemetryHistorySnapshots(
    fetch: suspend () -> List<NodeStatusSnapshotDto>?,
    demoEnabled: () -> Boolean,
    demoSnapshots: () -> List<NodeStatusSnapshotDto>,
): List<NodeStatusSnapshotDto> {
    val live = runExceptionFallback({ emptyList() }) { fetch().orEmpty() }
    return when {
        live.isNotEmpty() -> live
        demoEnabled() -> demoSnapshots()
        else -> emptyList()
    }
}
