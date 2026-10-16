package one.meshcore.shared.tools.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import one.meshcore.shared.map.ElevationService
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.ui.LocalMapOffline
import one.meshcore.shared.map.ui.rememberMapDisplayChrome
import one.meshcore.shared.map.ui.rememberNetworkOnlyStyleGate
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.platformName
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.tools_line_of_sight_elevation_unavailable
import one.meshcore.shared.resources.tools_line_of_sight_tap_map_instruction
import one.meshcore.shared.rf.ClearanceStatus
import one.meshcore.shared.tools.LineOfSightViewModel
import one.meshcore.shared.tools.REPEATER_DRAG_HINT_MS
import one.meshcore.shared.tools.consumeCameraTarget
import one.meshcore.shared.tools.shouldShowRepeaterDragHint
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.preferenceHubEdgeFillScroll
import org.jetbrains.compose.resources.stringResource

/**
 * Tools → Line of Sight: map-first placement (long-press A/B, repeater pins). Compact hosts keep
 * MapLibre under a peekable analysis sheet; measured workspace size selects analysis beside the map.
 * Desktop has no MapLibre surface and uses the analysis panel with manual placement fields.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineOfSightScreen(
    modifier: Modifier = Modifier,
    elevationService: ElevationService? = null,
    contactService: (() -> one.meshcore.shared.contact.ContactService?)? = null,
    radioId: () -> Uuid? = { null },
    deviceFrequencyKHz: Long? = null,
    shareText: (String) -> Unit = {},
    preferences: one.meshcore.shared.prefs.PreferencesStore? = null,
    mapDisplayPreferences: one.meshcore.shared.map.MapDisplayPreferencesStore? = null,
    isOffline: Boolean = LocalMapOffline.current,
    locationService: LocationService? = null,
    workspace: LineOfSightViewModel? = null,
) {
    val viewModel =
        rememberLineOfSightViewModel(elevationService, contactService, radioId, deviceFrequencyKHz, workspace)

    // Collect revision so Compose recomposes when VM fields mutate.
    val revision by viewModel.revision.collectAsStateWithLifecycle()
    check(revision >= 0)

    var hadRepeater by remember { mutableStateOf(false) }
    var hasSeenDragHint by remember { mutableStateOf(false) }
    var showDragHint by remember { mutableStateOf(false) }
    LaunchedEffect(preferences) {
        hasSeenDragHint = preferences?.getBoolean(PersistenceKeys.HAS_SEEN_REPEATER_DRAG_HINT) == true
    }
    val mapChrome = rememberMapDisplayChrome(mapDisplayPreferences, preferences)
    val (styleDisabled, styleDisabledReason) = rememberNetworkOnlyStyleGate(isNetworkAvailable = !isOffline)
    val liveRepeater = viewModel.repeaterPoint
    LaunchedEffect(liveRepeater != null, liveRepeater?.isOnPath) {
        val onPath = liveRepeater?.isOnPath == true
        if (shouldShowRepeaterDragHint(hadRepeater, onPath, hasSeenDragHint)) {
            showDragHint = true
            hasSeenDragHint = true
            preferences?.putBoolean(PersistenceKeys.HAS_SEEN_REPEATER_DRAG_HINT, true)
            delay(REPEATER_DRAG_HINT_MS)
            showDragHint = false
        }
        hadRepeater = liveRepeater != null
    }

    val mapSupported = platformName() == "Android"
    val clearanceLabels = clearanceLabels()
    val seaLevelNote = stringResource(Res.string.tools_line_of_sight_elevation_unavailable)

    // Clear one-shot camera target after the host has had a chance to apply it.
    val target = viewModel.cameraTarget
    val targetVersion = viewModel.cameraTargetVersion
    LaunchedEffect(viewModel, target, targetVersion) {
        if (target != null) {
            viewModel.consumeCameraTarget(targetVersion)
        }
    }

    if (!mapSupported) {
        Column(
            modifier.preferenceHubEdgeFillScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(Res.string.tools_line_of_sight_tap_map_instruction),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ManualCoordinateFields(viewModel)
            AnalysisSheetContent(
                viewModel = viewModel,
                clearanceLabels = clearanceLabels,
                seaLevelNote = seaLevelNote,
                shareText = shareText,
                showMapHints = false,
                showDragHint = showDragHint,
            )
        }
        return
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        if (shouldShowLosSideBySide(maxWidth.value, maxHeight.value)) {
            val panelWidth = losAnalysisPanelWidthDp(maxWidth.value)
            Row(Modifier.fillMaxSize()) {
                LosAnalysisPane(
                    viewModel = viewModel,
                    clearanceLabels = clearanceLabels,
                    seaLevelNote = seaLevelNote,
                    shareText = shareText,
                    showDragHint = showDragHint,
                    modifier = Modifier.width(panelWidth.dp).fillMaxHeight(),
                )
                VerticalDivider(thickness = LOS_DIVIDER_WIDTH_DP.dp)
                LosMapCanvas(
                    viewModel = viewModel,
                    mapChrome = mapChrome,
                    isOffline = isOffline,
                    locationService = locationService,
                    styleDisabled = styleDisabled,
                    styleDisabledReason = styleDisabledReason,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        } else {
            val peekHeight = maxHeight * LOS_COMPACT_SHEET_PEEK_FRACTION
            BottomSheetScaffold(
                scaffoldState = rememberBottomSheetScaffoldState(),
                sheetPeekHeight = peekHeight,
                sheetDragHandle = { BottomSheetDefaults.DragHandle() },
                sheetContent = {
                    LosAnalysisPane(
                        viewModel = viewModel,
                        clearanceLabels = clearanceLabels,
                        seaLevelNote = seaLevelNote,
                        shareText = shareText,
                        showDragHint = showDragHint,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
            ) { _ ->
                // Overlay chrome uses the peek height so Drop pin and map FABs stay above the sheet.
                LosMapCanvas(
                    viewModel = viewModel,
                    mapChrome = mapChrome,
                    isOffline = isOffline,
                    locationService = locationService,
                    styleDisabled = styleDisabled,
                    styleDisabledReason = styleDisabledReason,
                    overlayBottomInset = peekHeight,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun LosAnalysisPane(
    viewModel: LineOfSightViewModel,
    clearanceLabels: Map<ClearanceStatus, String>,
    seaLevelNote: String,
    shareText: (String) -> Unit,
    showDragHint: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(tonalElevation = 3.dp, modifier = modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnalysisSheetContent(
                viewModel = viewModel,
                clearanceLabels = clearanceLabels,
                seaLevelNote = seaLevelNote,
                shareText = shareText,
                showMapHints = true,
                showDragHint = showDragHint,
            )
        }
    }
}

/** iOS `AnalysisErrorView` triangle. */
internal val LOS_ANALYSIS_FAILED_ICON_SIZE = 36.dp

/** iOS `exclamationmark.triangle.fill` on the sea-level fallback. */
internal val LOS_ELEVATION_UNAVAILABLE_ICON get() = AppIcons.Warning
