package one.meshcore.shared.ui

import androidx.compose.runtime.Composable
import one.meshcore.shared.remotenode.LocationReportFormat
import one.meshcore.shared.remotenode.Recency
import one.meshcore.shared.remotenode.RecencyUnit
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_days_ago
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_hours_ago
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_minutes_ago
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_seconds_ago
import org.jetbrains.compose.resources.StringResource

/**
 * The localized "%d… ago" template for iOS `.relative(presentation: .named)`. Pure so the unit-to-resource
 * mapping is asserted without a composition. Seconds through days reuse the remote-node recency strings
 * already used by stale-cleanup last-run.
 */
fun namedRelativeResource(recency: Recency): StringResource = when (recency.unit) {
    RecencyUnit.SECONDS -> Res.string.remotenodes_remote_nodes_status_seconds_ago
    RecencyUnit.MINUTES -> Res.string.remotenodes_remote_nodes_status_minutes_ago
    RecencyUnit.HOURS -> Res.string.remotenodes_remote_nodes_status_hours_ago
    RecencyUnit.DAYS -> Res.string.remotenodes_remote_nodes_history_days_ago
}

/**
 * Named relative age for an epoch-seconds stamp. Matches iOS `.relative(presentation: .named)` as the
 * existing "%ds ago" / "%dm ago" / "%dh ago" / "%dd ago" family, not compact `5m` / `2d`.
 */
@Composable
fun namedRelativeLabel(epochSeconds: Long, nowSeconds: Long): String {
    val recency = LocationReportFormat.relativeRecency((nowSeconds - epochSeconds).toDouble())
    return formattedStringResource(namedRelativeResource(recency), recency.value)
}
