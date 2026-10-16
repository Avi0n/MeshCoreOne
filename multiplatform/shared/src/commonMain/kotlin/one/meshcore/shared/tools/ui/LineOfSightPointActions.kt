package one.meshcore.shared.tools.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import one.meshcore.shared.contact.mapsGeoUri
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_overflow_menu
import one.meshcore.shared.resources.localizable_common_save
import one.meshcore.shared.resources.tools_line_of_sight_clear
import one.meshcore.shared.resources.tools_line_of_sight_copy_coordinates
import one.meshcore.shared.resources.tools_line_of_sight_edit
import one.meshcore.shared.resources.tools_line_of_sight_open_in_maps
import one.meshcore.shared.resources.tools_line_of_sight_relocate
import one.meshcore.shared.resources.tools_line_of_sight_share
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.menu.McDropdownMenu
import one.meshcore.shared.ui.menu.McMenuItem
import one.meshcore.shared.ui.menu.McMenuRole
import one.meshcore.shared.ui.plainTextClipEntry
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.stringResource

/**
 * Point actions in a overflow menu so the coordinate row can ellipsize on a phone sheet.
 */
@Composable
internal fun PointActionButtons(
    pointLabel: String,
    coordinate: LatLng,
    shareText: (String) -> Unit,
    isEditing: Boolean,
    relocateEnabled: Boolean,
    onRelocate: () -> Unit,
    onToggleEdit: () -> Unit,
    onClear: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val haptics = rememberAppHaptics()
    val coordinateText = losCoordinateText(coordinate)
    Box {
        LosPointIconButton(
            icon = AppIcons.MoreVert,
            contentDescription = stringResource(Res.string.android_overflow_menu),
            onClick = { expanded = true },
        )
        McDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            McMenuItem(
                text = stringResource(Res.string.tools_line_of_sight_open_in_maps),
                leadingIcon = AppIcons.Map,
                onClick = {
                    expanded = false
                    uriHandler.openUri(
                        mapsGeoUri(
                            latitude = coordinate.latitude,
                            longitude = coordinate.longitude,
                            label = pointLabel,
                        ),
                    )
                },
            )
            McMenuItem(
                text = stringResource(Res.string.tools_line_of_sight_copy_coordinates),
                leadingIcon = AppIcons.ContentCopy,
                onClick = {
                    expanded = false
                    scope.launch {
                        clipboard.setClipEntry(plainTextClipEntry(coordinateText))
                        haptics.success()
                    }
                },
            )
            McMenuItem(
                text = stringResource(Res.string.tools_line_of_sight_share),
                leadingIcon = LOS_POINT_SHARE_ICON,
                onClick = {
                    expanded = false
                    shareText(coordinateText)
                },
            )
            McMenuItem(
                text = stringResource(Res.string.tools_line_of_sight_relocate),
                leadingIcon = LOS_POINT_RELOCATE_ICON,
                enabled = relocateEnabled,
                onClick = {
                    expanded = false
                    onRelocate()
                },
            )
            McMenuItem(
                text = stringResource(
                    if (isEditing) {
                        Res.string.localizable_common_save
                    } else {
                        Res.string.tools_line_of_sight_edit
                    },
                ),
                leadingIcon = if (isEditing) LOS_POINT_DONE_ICON else LOS_POINT_EDIT_ICON,
                onClick = {
                    expanded = false
                    onToggleEdit()
                },
            )
            McMenuItem(
                text = stringResource(Res.string.tools_line_of_sight_clear),
                leadingIcon = LOS_POINT_CLEAR_ICON,
                role = McMenuRole.Destructive,
                onClick = {
                    expanded = false
                    onClear()
                },
            )
        }
    }
}

@Composable
private fun LosPointIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    rotateDegrees: Float = 0f,
) {
    AppIconButton(
        imageVector = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        enabled = enabled,
        iconModifier = Modifier
            .size(LOS_POINT_ACTION_ICON_DP.dp)
            .rotate(rotateDegrees),
    )
}

/**
 * The "latitude, longitude" text a point is copied and shared as. It is the same text the row shows, so
 * what a user reads is what they paste (iOS `CLLocationCoordinate2D.formattedString`).
 */
internal fun losCoordinateText(coordinate: LatLng): String =
    "${formatCoord(coordinate.latitude)}, ${formatCoord(coordinate.longitude)}"

private fun formatCoord(value: Double): String {
    val scaled = (kotlin.math.abs(value) * COORD_DISPLAY_SCALE).toLong()
    val whole = scaled / COORD_DISPLAY_SCALE.toLong()
    val frac = (scaled % COORD_DISPLAY_SCALE.toLong()).toString().padStart(COORD_DISPLAY_DIGITS, '0')
    val sign = if (value < 0) "-" else ""
    return "$sign$whole.$frac"
}

private const val COORD_DISPLAY_DIGITS = 5
private const val COORD_DISPLAY_SCALE = 100_000.0

/** iOS `PointRowButtonsView` share / pin / ruler / check / xmark. */
internal val LOS_POINT_SHARE_ICON get() = AppIcons.Share
internal val LOS_POINT_RELOCATE_ICON get() = AppIcons.LocationOn
internal val LOS_POINT_EDIT_ICON get() = AppIcons.Straighten
internal val LOS_POINT_DONE_ICON get() = AppIcons.Check
internal val LOS_POINT_CLEAR_ICON get() = AppIcons.Close
private const val LOS_POINT_ACTION_ICON_DP = 22
