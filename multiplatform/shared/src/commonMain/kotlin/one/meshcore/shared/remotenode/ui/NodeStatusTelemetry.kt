package one.meshcore.shared.remotenode.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_accessibility_reload_telemetry
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_telemetry
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_telemetry_footer
import one.meshcore.shared.theme.AppColorSchemePreference
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LiveTelemetrySection(
    viewModel: NodeStatusViewModel,
    state: NodeStatusState,
    sessionName: String,
    mapColorSchemePreference: AppColorSchemePreference,
    isOffline: Boolean,
    locationService: LocationService?,
    userLocation: UserLocation?,
    isRadioReady: Boolean,
    mapChrome: one.meshcore.shared.map.ui.MapDisplayChrome,
    onOpenHistory: () -> Unit,
) {
    var showLocationFix by remember { mutableStateOf(false) }
    StatusSection(
        expanded = state.telemetryExpanded,
        onExpandedChange = viewModel::setTelemetryExpanded,
        title = stringResource(Res.string.remotenodes_remote_nodes_status_telemetry),
        isLoading = state.isLoadingTelemetry,
        onReload = { viewModel.requestTelemetry() },
        reloadAccessibilityLabel = stringResource(
            Res.string.remotenodes_remote_nodes_status_accessibility_reload_telemetry,
        ),
        isRadioReady = isRadioReady,
        footer = stringResource(Res.string.remotenodes_remote_nodes_status_telemetry_footer),
    ) {
        LiveTelemetryReadout(
            isLoading = state.isLoadingTelemetry,
            loaded = state.telemetryLoaded,
            sectionError = state.telemetrySectionError,
            points = state.telemetryDataPoints,
            ocvValues = state.ocvValues,
        )
        // The fix this reply carried, before any snapshot is written; the history map only shows
        // recorded snapshots, so without this the fresh position has no surface.
        if (state.telemetryLoaded && state.currentLocationFix != null) {
            ViewLocationOnMapButton(onClick = { showLocationFix = true })
        }
        if (state.telemetryLoaded) {
            StatusHistoryLink(onClick = onOpenHistory)
        }
    }

    val fix = state.currentLocationFix
    if (showLocationFix && fix != null) {
        NodeLocationFixSheet(
            fix = fix,
            label = sessionName,
            colorSchemePreference = mapColorSchemePreference,
            isOffline = isOffline,
            locationService = locationService,
            userLocation = userLocation,
            onDismiss = { showLocationFix = false },
            mapChrome = mapChrome,
        )
    }
}
