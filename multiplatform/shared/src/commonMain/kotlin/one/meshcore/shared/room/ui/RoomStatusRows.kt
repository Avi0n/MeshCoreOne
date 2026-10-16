package one.meshcore.shared.room.ui

import androidx.compose.runtime.Composable
import one.meshcore.shared.meshcore.StatusResponse
import one.meshcore.shared.remotenode.ui.NodeStatusFormat
import one.meshcore.shared.remotenode.ui.StatusMetricDeltas
import one.meshcore.shared.remotenode.ui.StatusRows
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_status_posts_pushed
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_status_posts_received
import one.meshcore.shared.ui.PreferenceHubValueRow
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun RoomStatusRows(status: StatusResponse, deltas: StatusMetricDeltas, ocvValues: List<Int>) {
    StatusRows(
        status = status,
        deltas = deltas,
        ocvValues = ocvValues,
        batteryLeadsWithVoltage = true,
    )
    PreferenceHubValueRow(
        stringResource(Res.string.remotenodes_remote_nodes_room_status_posts_received),
        status.roomServerPostedCount?.let { NodeStatusFormat.count(it.toLong()) } ?: EM_DASH,
    )
    PreferenceHubValueRow(
        stringResource(Res.string.remotenodes_remote_nodes_room_status_posts_pushed),
        status.roomServerPostPushCount?.let { NodeStatusFormat.count(it.toLong()) } ?: EM_DASH,
    )
}

private const val EM_DASH = "—"
