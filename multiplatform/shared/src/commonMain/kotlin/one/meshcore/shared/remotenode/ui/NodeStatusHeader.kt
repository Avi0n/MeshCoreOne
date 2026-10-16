package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_relative_age_days
import one.meshcore.shared.resources.android_relative_age_hours
import one.meshcore.shared.resources.android_relative_age_minutes
import one.meshcore.shared.resources.android_relative_age_seconds
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_clock_ahead
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_clock_behind
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_guest_mode
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/** Guest-only caption on status, matching iOS `NodeStatusHeaderSection`. */
internal fun showsStatusGuestMode(permissionLevel: RoomPermissionLevel): Boolean =
    permissionLevel == RoomPermissionLevel.GUEST

@Composable
internal fun NodeStatusHeader(session: RemoteNodeSessionDto, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LABEL_SPACING),
    ) {
        Text(session.name, style = MaterialTheme.typography.titleMedium)
        if (showsStatusGuestMode(session.permissionLevel)) {
            Text(
                text = stringResource(Res.string.remotenodes_remote_nodes_status_guest_mode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Warns when a node's clock is skewed past the threshold; an inaccurate clock breaks replay protection. */
@Composable
internal fun ClockDriftWarning(driftSeconds: Double) {
    val magnitude = formatDriftMagnitude(abs(driftSeconds))
    val text = if (driftSeconds > 0) {
        formattedStringResource(Res.string.remotenodes_remote_nodes_status_clock_ahead, magnitude)
    } else {
        formattedStringResource(Res.string.remotenodes_remote_nodes_status_clock_behind, magnitude)
    }
    ListItem(
        headlineContent = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        },
        leadingContent = {
            Icon(
                imageVector = CLOCK_DRIFT_WARNING_ICON,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** iOS clock-drift `clock.badge.exclamationmark`. */
internal val CLOCK_DRIFT_WARNING_ICON get() = AppIcons.AccessTime

/** Compact localized "1h 5m" / "5m" / "30s" magnitude, capped at two units like iOS. */
@Composable
private fun formatDriftMagnitude(seconds: Double): String {
    val day = stringResource(Res.string.android_relative_age_days)
    val hour = stringResource(Res.string.android_relative_age_hours)
    val minute = stringResource(Res.string.android_relative_age_minutes)
    val second = stringResource(Res.string.android_relative_age_seconds)
    return NodeStatusFormat.driftMagnitudeUnits(seconds).joinToString(" ") { part ->
        val template = when (part.unit) {
            DriftUnit.DAYS -> day
            DriftUnit.HOURS -> hour
            DriftUnit.MINUTES -> minute
            DriftUnit.SECONDS -> second
        }
        formatTemplate(template, listOf(part.count))
    }
}

internal const val CLOCK_DRIFT_WARNING_THRESHOLD_SECONDS = 300.0

private val LABEL_SPACING = 6.dp
