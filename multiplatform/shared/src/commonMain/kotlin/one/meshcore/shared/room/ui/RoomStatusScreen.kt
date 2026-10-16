package one.meshcore.shared.room.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.ui.LocalMapColorSchemePreference
import one.meshcore.shared.map.ui.LocalMapOffline
import one.meshcore.shared.map.ui.rememberMapDisplayChrome
import one.meshcore.shared.remotenode.TelemetryHistoryDemo
import one.meshcore.shared.remotenode.statusHistorySnapshots
import one.meshcore.shared.remotenode.ui.BatteryCurveSection
import one.meshcore.shared.remotenode.ui.LiveTelemetryReadout
import one.meshcore.shared.remotenode.ui.NodeHistoryDialogs
import one.meshcore.shared.remotenode.ui.NodeLocationFixSheet
import one.meshcore.shared.remotenode.ui.NodeStatusHeader
import one.meshcore.shared.remotenode.ui.SectionErrorText
import one.meshcore.shared.remotenode.ui.StatusHistoryLink
import one.meshcore.shared.remotenode.ui.StatusSection
import one.meshcore.shared.remotenode.ui.ViewLocationOnMapButton
import one.meshcore.shared.remotenode.ui.previousVisitLabel
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_status_section
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_telemetry
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_telemetry_footer
import one.meshcore.shared.theme.AppColorSchemePreference
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import one.meshcore.shared.ui.PreferenceHubStatusRow
import one.meshcore.shared.ui.preferenceHubScroll
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Room status body: counters (incl. posts received/pushed), last-visit metric deltas, live LPP
 * telemetry sensor rows, battery curve editor, and History drill-down (iOS `nodeStatusDestinations`).
 * Hosted as a nested destination from Room Info when connected.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun RoomStatusScreen(
    viewModel: RoomStatusViewModel,
    session: RemoteNodeSessionDto,
    modifier: Modifier = Modifier,
    useDemoTelemetryHistory: Boolean = false,
    isRadioReady: Boolean = true,
    mapColorSchemePreference: AppColorSchemePreference = LocalMapColorSchemePreference.current,
    isOffline: Boolean = LocalMapOffline.current,
    locationService: LocationService? = null,
    userLocation: UserLocation? = null,
    mapDisplayPreferences: one.meshcore.shared.map.MapDisplayPreferencesStore? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mapChrome = rememberMapDisplayChrome(mapDisplayPreferences)
    var showLocationFix by remember { mutableStateOf(false) }
    var showStatusHistory by remember { mutableStateOf(false) }
    var showTelemetryHistory by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel, state.statusExpanded, state.telemetryExpanded) {
        if (state.statusExpanded || state.telemetryExpanded) viewModel.loadHistory()
    }

    val nowEpochSeconds = Clock.System.now().epochSeconds.toDouble()
    val historySnapshots = statusHistorySnapshots(
        saved = state.historySnapshots,
        useDemo = useDemoTelemetryHistory,
        demo = { TelemetryHistoryDemo.sampleSnapshots(session.publicKey, nowEpochSeconds) },
    )

    Box(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.preferenceHubScroll()) {
            NodeStatusHeader(
                session = session,
                modifier = Modifier.padding(horizontal = PreferenceHubHorizontalPadding),
            )
            StatusSection(
                title = stringResource(Res.string.remotenodes_remote_nodes_status_status_section),
                isLoading = state.isLoadingStatus,
                expanded = state.statusExpanded,
                onExpandedChange = viewModel::setStatusExpanded,
                isRadioReady = isRadioReady,
                onReload = { viewModel.requestStatus() },
            ) {
                val status = state.status
                when {
                    state.statusSectionError != null && status == null -> SectionErrorText(state.statusSectionError)
                    status != null -> {
                        RoomStatusRows(status, state.statusDeltas, state.ocvValues)
                        state.previousStatusSnapshot?.let { previous ->
                            PreferenceHubStatusRow(previousVisitLabel(previous.timestamp, nowEpochSeconds))
                        }
                        StatusHistoryLink(onClick = { showStatusHistory = true })
                    }
                }
            }
            StatusSection(
                title = stringResource(Res.string.remotenodes_remote_nodes_status_telemetry),
                isLoading = state.isLoadingTelemetry,
                expanded = state.telemetryExpanded,
                onExpandedChange = viewModel::setTelemetryExpanded,
                isRadioReady = isRadioReady,
                onReload = { viewModel.requestTelemetry() },
                footer = stringResource(Res.string.remotenodes_remote_nodes_status_telemetry_footer),
            ) {
                LiveTelemetryReadout(
                    isLoading = state.isLoadingTelemetry,
                    loaded = state.telemetryLoaded,
                    sectionError = state.telemetrySectionError,
                    points = state.telemetryDataPoints,
                    ocvValues = state.ocvValues,
                )
                if (state.telemetryLoaded && state.currentLocationFix != null) {
                    ViewLocationOnMapButton(onClick = { showLocationFix = true })
                }
                if (state.telemetryLoaded) {
                    StatusHistoryLink(onClick = { showTelemetryHistory = true })
                }
            }
            BatteryCurveSection(
                expanded = state.batteryCurveExpanded,
                onExpandedChange = viewModel::setBatteryCurveExpanded,
                isSavingOcv = state.isSavingOcv,
                ocvError = state.ocvError,
                selectedPreset = state.ocvPreset,
                values = state.ocvValues,
                isRadioReady = isRadioReady,
                onSelectPreset = viewModel::selectOcvPreset,
                onCommitValues = viewModel::commitOcvValues,
            )
        }
        val fix = state.currentLocationFix
        if (showLocationFix && fix != null) {
            NodeLocationFixSheet(
                fix = fix,
                label = session.name,
                colorSchemePreference = mapColorSchemePreference,
                isOffline = isOffline,
                locationService = locationService,
                userLocation = userLocation,
                onDismiss = { showLocationFix = false },
                mapChrome = mapChrome,
            )
        }
        NodeHistoryDialogs(
            showStatusHistory = showStatusHistory,
            showTelemetryHistory = showTelemetryHistory,
            onDismissStatus = { showStatusHistory = false },
            onDismissTelemetry = { showTelemetryHistory = false },
            snapshots = historySnapshots,
            nowEpochSeconds = nowEpochSeconds,
            ocvArray = state.ocvValues,
            neighborNameResolver = { null },
            mapColorSchemePreference = mapColorSchemePreference,
            mapStyle = mapChrome.style,
            isOffline = isOffline,
            locationService = locationService,
            userLocation = userLocation,
            mapChrome = mapChrome,
        )
    }
}
