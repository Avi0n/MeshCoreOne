package one.meshcore.shared.tools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import one.meshcore.shared.remotenode.LPPDisplay
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.tools_line_of_sight_additional_height
import one.meshcore.shared.resources.tools_line_of_sight_dropped_pin
import one.meshcore.shared.resources.tools_line_of_sight_ground_elevation
import one.meshcore.shared.resources.tools_line_of_sight_loading_elevation
import one.meshcore.shared.resources.tools_line_of_sight_not_selected
import one.meshcore.shared.resources.tools_line_of_sight_total_height
import one.meshcore.shared.tools.ADDITIONAL_HEIGHT_MAX_M
import one.meshcore.shared.tools.ADDITIONAL_HEIGHT_MIN_M
import one.meshcore.shared.tools.LosSelectedPoint
import one.meshcore.shared.tools.additionalHeightStepMeters
import one.meshcore.shared.tools.formatLosHeightMeters
import one.meshcore.shared.ui.SignalChromeRole
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.onTint
import one.meshcore.shared.ui.tint
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PointSummaryRow(
    title: String,
    markerLabel: String,
    markerRole: SignalChromeRole,
    point: LosSelectedPoint?,
    shareText: (String) -> Unit,
    isEditing: Boolean,
    onToggleEdit: () -> Unit,
    onClear: () -> Unit,
    onRelocate: () -> Unit,
    relocateEnabled: Boolean,
    onHeight: (Double) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = {
                Text(
                    text = if (point == null) {
                        stringResource(Res.string.tools_line_of_sight_not_selected)
                    } else {
                        point.displayName(stringResource(Res.string.tools_line_of_sight_dropped_pin))
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = if (point == null) {
                null
            } else {
                {
                    Column {
                        ElevationReadout(
                            groundElevation = point.groundElevation,
                            totalHeight = point.totalHeight,
                        )
                        Text(
                            text = losCoordinateText(point.coordinate),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            leadingContent = {
                LosPointMarker(
                    label = markerLabel,
                    role = markerRole,
                    filled = point != null,
                    contentDescription = title,
                )
            },
            trailingContent = if (point == null) {
                null
            } else {
                {
                    PointActionButtons(
                        pointLabel = title,
                        coordinate = point.coordinate,
                        shareText = shareText,
                        isEditing = isEditing,
                        relocateEnabled = relocateEnabled,
                        onRelocate = onRelocate,
                        onToggleEdit = onToggleEdit,
                        onClear = onClear,
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth(),
        )
        if (point != null && isEditing) {
            AdditionalHeightStepper(
                meters = point.additionalHeight,
                onChange = onHeight,
            )
        }
    }
}

/** iOS `PointRowView` 24pt letter circle (surface-variant while the point is empty). */
@Composable
internal fun LosPointMarker(label: String, role: SignalChromeRole, filled: Boolean, contentDescription: String) {
    val scheme = MaterialTheme.colorScheme
    val fill = if (filled) role.tint() else losPointMarkerEmptyFill(scheme.onSurface)
    Box(
        modifier = Modifier
            .size(LOS_POINT_MARKER_DP.dp)
            .clip(CircleShape)
            .background(fill)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = losPointMarkerLabelColor(filled, role.onTint(), scheme.onSurface),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Ground elevation and the resulting total mast height for one point. The whole clearance verdict rests
 * on the ground figure, so a sea-level fallback has to be distinguishable from a real 1800 m ridge, and
 * a fetch still in flight has to say so rather than silently disabling Analyze.
 */
@Composable
internal fun ElevationReadout(groundElevation: Double?, totalHeight: Double?) {
    val system = LPPDisplay.currentMeasurementSystem()
    val symbols = LPPDisplay.currentLengthSymbols()
    if (groundElevation == null) {
        Text(
            text = stringResource(Res.string.tools_line_of_sight_loading_elevation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Text(
        text = stringResource(Res.string.tools_line_of_sight_ground_elevation) + ": " +
            formatLosHeightMeters(groundElevation, system, symbols),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    totalHeight?.let {
        Text(
            text = stringResource(Res.string.tools_line_of_sight_total_height) + ": " +
                formatLosHeightMeters(it, system, symbols),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** iOS `HeightEditorGrid` additional-height stepper: 1 m metric, 1 ft otherwise, 0…200 m. */
@Composable
internal fun AdditionalHeightStepper(meters: Double, onChange: (Double) -> Unit) {
    val label = stringResource(Res.string.tools_line_of_sight_additional_height)
    val system = LPPDisplay.currentMeasurementSystem()
    val symbols = LPPDisplay.currentLengthSymbols()
    val step = additionalHeightStepMeters(system)
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIconButton(
                    imageVector = AppIcons.Remove,
                    contentDescription = label,
                    onClick = { onChange(meters - step) },
                    enabled = meters > ADDITIONAL_HEIGHT_MIN_M,
                )
                Text(
                    text = formatLosHeightMeters(meters, system, symbols),
                    style = MaterialTheme.typography.bodyMedium,
                )
                AppIconButton(
                    imageVector = AppIcons.Add,
                    contentDescription = label,
                    onClick = { onChange(meters + step) },
                    enabled = meters < ADDITIONAL_HEIGHT_MAX_M,
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val LOS_POINT_MARKER_DP = 24
internal const val LOS_POINT_MARKER_EMPTY_FILL_ALPHA = 0.12f

internal fun losPointMarkerEmptyFill(onSurface: Color) = onSurface.copy(alpha = LOS_POINT_MARKER_EMPTY_FILL_ALPHA)

internal fun losPointMarkerLabelColor(filled: Boolean, onFill: Color, onSurface: Color): Color =
    if (filled) onFill else onSurface
