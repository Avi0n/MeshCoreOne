package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.MapDisplayPreferencesStore
import one.meshcore.shared.map.ui.LocalMapColorSchemePreference
import one.meshcore.shared.map.ui.LocalMapOffline
import one.meshcore.shared.map.ui.rememberMapDisplayChrome
import one.meshcore.shared.prefs.PreferencesStore
import one.meshcore.shared.remotenode.NodeLocationFix
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_title
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_no_sensor_data
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_no_telemetry_data
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_telemetry
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_telemetry_footer
import one.meshcore.shared.theme.AppColorSchemePreference
import one.meshcore.shared.ui.preferenceHubEdgeFillScroll
import org.jetbrains.compose.resources.stringResource

private val SECTION_GAP = 12.dp

/**
 * Live telemetry for a chat contact. Material 3 card with refresh, sensor rows, View on Map
 * for a live GPS fix, and History.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeTelemetryScreen(
    viewModel: NodeTelemetryViewModel,
    contactName: String,
    modifier: Modifier = Modifier,
    onOpenHistory: (() -> Unit)? = null,
    isRadioReady: Boolean = true,
    mapColorSchemePreference: AppColorSchemePreference = LocalMapColorSchemePreference.current,
    isOffline: Boolean = LocalMapOffline.current,
    locationService: LocationService? = null,
    userLocation: UserLocation? = null,
    mapDisplayPreferences: MapDisplayPreferencesStore? = null,
    preferences: PreferencesStore? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showLocationFix by remember { mutableStateOf(false) }
    val mapChrome = rememberMapDisplayChrome(mapDisplayPreferences, preferences)
    Column(
        modifier = modifier.preferenceHubEdgeFillScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
    ) {
        StatusSection(
            title = stringResource(Res.string.remotenodes_remote_nodes_status_telemetry),
            expanded = state.expanded,
            onExpandedChange = viewModel::setExpanded,
            isLoading = state.isLoading,
            onReload = viewModel::requestTelemetry,
            isRadioReady = isRadioReady,
            footer = stringResource(Res.string.remotenodes_remote_nodes_status_telemetry_footer),
        ) {
            TelemetrySectionBody(
                state = state,
                onOpenHistory = onOpenHistory,
                onOpenLocationMap = { showLocationFix = true },
            )
        }
    }
    val fix = state.currentLocationFix
    if (showLocationFix && fix != null) {
        NodeLocationFixSheet(
            fix = fix,
            label = contactName,
            colorSchemePreference = mapColorSchemePreference,
            isOffline = isOffline,
            locationService = locationService,
            userLocation = userLocation,
            onDismiss = { showLocationFix = false },
            mapChrome = mapChrome,
        )
    }
}

@Composable
private fun TelemetrySectionBody(
    state: NodeTelemetryState,
    onOpenHistory: (() -> Unit)?,
    onOpenLocationMap: () -> Unit,
) {
    val listColors = ListItemDefaults.colors(containerColor = Color.Transparent)
    when {
        state.isLoading && !state.loaded -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }
        }
        state.sectionError != null && !state.loaded -> {
            Text(
                text = telemetrySectionErrorMessage(state.sectionError),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        state.loaded && state.dataPoints.isEmpty() -> {
            Text(
                text = stringResource(Res.string.remotenodes_remote_nodes_status_no_sensor_data),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            LiveTelemetryDestinations(
                fix = state.currentLocationFix,
                onOpenLocationMap = onOpenLocationMap,
                onOpenHistory = onOpenHistory,
                listColors = listColors,
            )
        }
        state.loaded -> {
            TelemetrySensorRows(points = state.dataPoints, ocvValues = state.ocvValues)
            LiveTelemetryDestinations(
                fix = state.currentLocationFix,
                onOpenLocationMap = onOpenLocationMap,
                onOpenHistory = onOpenHistory,
                listColors = listColors,
            )
        }
        else -> {
            Text(
                text = stringResource(Res.string.remotenodes_remote_nodes_status_no_telemetry_data),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun LiveTelemetryDestinations(
    fix: NodeLocationFix?,
    onOpenLocationMap: () -> Unit,
    onOpenHistory: (() -> Unit)?,
    listColors: androidx.compose.material3.ListItemColors,
) {
    if (fix != null) {
        ViewLocationOnMapButton(onClick = onOpenLocationMap)
    }
    HistoryLink(onOpenHistory, listColors)
}

@Composable
private fun HistoryLink(onOpenHistory: (() -> Unit)?, listColors: androidx.compose.material3.ListItemColors) {
    if (onOpenHistory == null) return
    ListItem(
        headlineContent = {
            Text(stringResource(Res.string.remotenodes_remote_nodes_history_title))
        },
        colors = listColors,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenHistory),
    )
}
