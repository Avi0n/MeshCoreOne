package one.meshcore.shared.contact.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactDetailLogic
import one.meshcore.shared.contact.PathAccessibilityCopy
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.contact.hasLocation
import one.meshcore.shared.contact.isFloodRouted
import one.meshcore.shared.contact.pathAccessibilityLabel
import one.meshcore.shared.contact.pathHopCount
import one.meshcore.shared.contact.pathHops
import one.meshcore.shared.contact.pathString
import one.meshcore.shared.contact.routeKind
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.formatRounded5
import one.meshcore.shared.map.ui.LocalMapColorSchemePreference
import one.meshcore.shared.map.ui.MapLibreHost
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_detail_coordinates
import one.meshcore.shared.resources.contacts_detail_copy_route
import one.meshcore.shared.resources.contacts_detail_discover_path
import one.meshcore.shared.resources.contacts_detail_edit_path
import one.meshcore.shared.resources.contacts_detail_flood_footer
import one.meshcore.shared.resources.contacts_detail_hops_away
import one.meshcore.shared.resources.contacts_detail_location
import one.meshcore.shared.resources.contacts_detail_open_in_maps
import one.meshcore.shared.resources.contacts_detail_outbound_path
import one.meshcore.shared.resources.contacts_detail_path_footer
import one.meshcore.shared.resources.contacts_detail_reset_path
import one.meshcore.shared.resources.contacts_detail_route
import one.meshcore.shared.resources.contacts_detail_route_direct
import one.meshcore.shared.resources.contacts_detail_route_flood
import one.meshcore.shared.resources.contacts_detail_route_prefix
import one.meshcore.shared.ui.PathHopChipRow
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.plainTextClipEntry
import org.jetbrains.compose.resources.stringResource

private val MINI_MAP_HEIGHT = 180.dp

@Composable
internal fun ContactDetailLocationSection(
    contact: ContactDto,
    isOffline: Boolean,
    onExpandMap: () -> Unit,
    onOpenInMaps: (() -> Unit)?,
) {
    val mapAppearance = LocalMapColorSchemePreference.current
    val miniPoint = remember(contact.id, contact.latitude, contact.longitude, contact.contactType) {
        ContactDetailLogic.miniMapPoint(contact)
    }
    val miniCamera = remember(contact.latitude, contact.longitude) {
        ContactDetailLogic.miniMapCamera(contact.latitude, contact.longitude)
    }
    if (contact.hasLocation && miniPoint != null) {
        DetailSection(title = stringResource(Res.string.contacts_detail_location)) {
            // Non-interactive and unlabelled, so a tap anywhere on it expands rather than panning.
            MapLibreHost(
                points = listOf(miniPoint),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MINI_MAP_HEIGHT)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(MaterialTheme.shapes.medium),
                colorSchemePreference = mapAppearance,
                initialCamera = miniCamera,
                isOffline = isOffline,
                interactive = false,
                showLabels = false,
                onMapClick = { _: LatLng -> onExpandMap() },
                onPointClick = { _: String -> onExpandMap() },
            )
            DetailInfoItem(
                headline = stringResource(Res.string.contacts_detail_coordinates),
                supporting = formatCoordinates(contact.latitude, contact.longitude),
                icon = AppIcons.LocationOn,
            )
            if (onOpenInMaps != null) {
                DetailActionItem(
                    headline = stringResource(Res.string.contacts_detail_open_in_maps),
                    icon = AppIcons.Map,
                    onClick = onOpenInMaps,
                )
            }
        }
    }
}

@Composable
internal fun ContactDetailPathSection(
    contact: ContactDto,
    resolveHopName: (ByteArray) -> String?,
    isDiscoveringPath: Boolean,
    discoverySecondsRemaining: Int?,
    onCancelDiscovery: (() -> Unit)?,
    enablement: ContactDetailLogic.ActionEnablement,
    onDiscoverPath: () -> Unit,
    onResetPath: () -> Unit,
    onEditPath: () -> Unit,
    radioHint: String?,
    pathStatus: String?,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val pathFooter = if (contact.isFloodRouted) {
        stringResource(Res.string.contacts_detail_flood_footer)
    } else {
        stringResource(Res.string.contacts_detail_path_footer)
    }
    val routeLabel = contact.routeKind(inboundHopCount = null).label()
    DetailSection(
        title = stringResource(Res.string.contacts_detail_outbound_path),
        footer = pathFooter,
    ) {
        // Out-path hops are named where a node matches the hash, else the Flood or Direct label.
        val isRoutePopulated = !contact.isFloodRouted && contact.pathHopCount > 0
        val pathDisplay = if (isRoutePopulated) {
            contact.pathString(resolveHopName)
        } else {
            routeLabel
        }
        val routeA11y = pathAccessibilityLabel(
            isFloodRouted = contact.isFloodRouted,
            pathHopCount = contact.pathHopCount,
            pathDisplay = pathDisplay,
            copy = PathAccessibilityCopy(
                floodLabel = stringResource(Res.string.contacts_detail_route_flood),
                directLabel = stringResource(Res.string.contacts_detail_route_direct),
                prefixTemplate = stringResource(Res.string.contacts_detail_route_prefix),
            ),
        )
        val copyRouteLabel = stringResource(Res.string.contacts_detail_copy_route)
        val routeIDPrefixes = contact.pathHops.joinToString(separator = ",") { it.hex }
        val copyRoute: () -> Unit = {
            scope.launch {
                clipboard.setClipEntry(plainTextClipEntry(routeIDPrefixes))
            }
        }
        DetailInfoItem(
            headline = stringResource(Res.string.contacts_detail_route),
            supporting = pathDisplay,
            icon = AppIcons.Timeline,
            monospace = true,
            supportingContent = if (isRoutePopulated) {
                {
                    PathHopChipRow(
                        hops = contact.pathHops.map { hop -> resolveHopName(hop.raw) ?: hop.hex },
                    )
                }
            } else {
                null
            },
            modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = routeA11y
                if (isRoutePopulated) {
                    customActions = listOf(
                        CustomAccessibilityAction(copyRouteLabel) {
                            copyRoute()
                            true
                        },
                    )
                }
            },
        )
        if (isRoutePopulated) {
            DetailActionItem(
                headline = copyRouteLabel,
                icon = AppIcons.ContentCopy,
                onClick = copyRoute,
            )
        }
        if (!contact.isFloodRouted) {
            DetailInfoItem(
                headline = stringResource(Res.string.contacts_detail_hops_away),
                supporting = contact.pathHopCount.toString(),
                icon = AppIcons.Route,
            )
        }
        if (isDiscoveringPath) {
            DiscoveringPathRow(
                secondsRemaining = discoverySecondsRemaining,
                onCancel = onCancelDiscovery,
            )
        } else {
            DetailActionItem(
                headline = stringResource(Res.string.contacts_detail_discover_path),
                icon = AppIcons.CellTower,
                enabled = enablement.discoverPath,
                onClick = onDiscoverPath,
                radioHint = radioHint,
            )
        }
        DetailActionItem(
            headline = stringResource(Res.string.contacts_detail_reset_path),
            icon = AppIcons.Sync,
            enabled = enablement.resetPath,
            onClick = onResetPath,
            radioHint = radioHint,
        )
        DetailActionItem(
            headline = stringResource(Res.string.contacts_detail_edit_path),
            icon = AppIcons.Edit,
            enabled = enablement.editPath,
            onClick = onEditPath,
            radioHint = radioHint,
        )
        pathStatus?.takeIf { it != "ok" }?.let { err ->
            Text(
                text = err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

internal fun formatCoordinates(latitude: Double, longitude: Double): String =
    "${formatRounded5(latitude)}, ${formatRounded5(longitude)}"
