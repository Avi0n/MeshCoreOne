package one.meshcore.shared.tools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_tools_line_of_sight_indirect_route
import one.meshcore.shared.resources.tools_line_of_sight_earth_curvature
import one.meshcore.shared.resources.tools_line_of_sight_legend_clear
import one.meshcore.shared.resources.tools_line_of_sight_legend_los
import one.meshcore.shared.resources.tools_line_of_sight_legend_obstructed
import one.meshcore.shared.resources.tools_line_of_sight_legend_terrain
import one.meshcore.shared.resources.tools_line_of_sight_repeater
import one.meshcore.shared.tools.formatKFactor
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.tint
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun IndirectRouteCaption(offPath: Boolean) {
    if (!offPath) return
    Text(
        text = stringResource(Res.string.android_tools_line_of_sight_indirect_route),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The terrain chart's header: its title, and the k-factor the profile was drawn with. Without the caption
 * the curve silently depends on a setting the reader cannot see, which is why iOS states it here too.
 */
@Composable
internal fun TerrainProfileHeader(title: String, refractionK: Double) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LOS_SECTION_ICON_GAP),
            ) {
                Icon(
                    imageVector = LOS_EARTH_CURVATURE_ICON,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(LOS_SECTION_ICON_DP.dp),
                )
                Text(
                    text = formattedStringResource(
                        Res.string.tools_line_of_sight_earth_curvature,
                        formatKFactor(refractionK),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The key to the four series the terrain chart plots. Swatches use theme roles, not iOS system hues.
 */
@Composable
internal fun TerrainProfileLegend(showRepeater: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(LEGEND_ITEM_GAP),
    ) {
        LegendItem(MaterialTheme.colorScheme.outline, stringResource(Res.string.tools_line_of_sight_legend_terrain))
        LegendItem(MaterialTheme.colorScheme.onSurface, stringResource(Res.string.tools_line_of_sight_legend_los))
        LegendItem(
            LOS_FRESNEL_CLEAR_ROLE.tint().copy(alpha = FRESNEL_OUTER_ALPHA),
            stringResource(Res.string.tools_line_of_sight_legend_clear),
        )
        LegendItem(
            LOS_FRESNEL_OBSTRUCTED_ROLE.tint().copy(alpha = FRESNEL_OBSTRUCTED_ALPHA),
            stringResource(Res.string.tools_line_of_sight_legend_obstructed),
        )
        if (showRepeater) {
            LegendItem(LOS_REPEATER_ROLE.tint(), stringResource(Res.string.tools_line_of_sight_repeater))
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(LEGEND_SWATCH_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(LEGEND_SWATCH_SIZE).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val LEGEND_ITEM_GAP = 16.dp
private val LEGEND_SWATCH_GAP = 4.dp
private val LEGEND_SWATCH_SIZE = 8.dp
private const val FRESNEL_OUTER_ALPHA = 0.25f

internal val LOS_EARTH_CURVATURE_ICON get() = AppIcons.Public
