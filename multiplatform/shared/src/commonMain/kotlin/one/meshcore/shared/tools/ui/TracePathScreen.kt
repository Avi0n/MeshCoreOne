package one.meshcore.shared.tools.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.hasLocation
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.MapFilterHost
import one.meshcore.shared.map.MapFilterState
import one.meshcore.shared.map.ui.LocalMapOffline
import one.meshcore.shared.map.ui.rememberMapDisplayChrome
import one.meshcore.shared.map.ui.rememberSnrBadgeFormatter
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.pathedit.DiscoveredNode
import one.meshcore.shared.pathedit.RecentHopsStore
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferencesStore
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_path_name_multiple_endpoints
import one.meshcore.shared.resources.contacts_path_name_prefix
import one.meshcore.shared.resources.contacts_path_name_two_endpoints
import one.meshcore.shared.resources.contacts_trace_error_all_failed
import one.meshcore.shared.resources.contacts_trace_failed
import one.meshcore.shared.resources.contacts_trace_list_empty_path
import one.meshcore.shared.resources.contacts_trace_map_save_failed_message
import one.meshcore.shared.resources.tools_saved_paths_delete_failed
import one.meshcore.shared.resources.tools_saved_paths_load_failed
import one.meshcore.shared.resources.tools_saved_paths_rename_failed
import one.meshcore.shared.settings.RadioDeviceSnapshot
import one.meshcore.shared.tools.TracePathViewModel
import one.meshcore.shared.tools.TracePathWorkspace
import one.meshcore.shared.tools.attach
import one.meshcore.shared.tools.cancelRun
import one.meshcore.shared.tools.clearPath
import one.meshcore.shared.tools.consumeStoredResult
import one.meshcore.shared.tools.detach
import one.meshcore.shared.tools.load
import one.meshcore.shared.tools.reloadSaved
import one.meshcore.shared.tools.run
import one.meshcore.shared.trace.SavedTracePathStore
import one.meshcore.shared.trace.TracePathBuilder
import one.meshcore.shared.trace.TracePathCandidateMapBuilder
import one.meshcore.shared.trace.TracePathHopSelection
import one.meshcore.shared.trace.TracePathMapBuilder
import one.meshcore.shared.trace.TracePathMapPinFilter
import one.meshcore.shared.trace.effectiveTraceMode
import one.meshcore.shared.trace.findMatchingSavedPath
import one.meshcore.shared.trace.generateTracePathName
import one.meshcore.shared.trace.traceHashSize
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.rememberAppHaptics
import one.meshcore.shared.ui.rememberMinuteTickSeconds
import org.jetbrains.compose.resources.stringResource
import kotlin.time.ExperimentalTime

/**
 * Tools → Trace Path: paste a hop path, choose hash size / auto-return, run single or batch traces,
 * save/reload paths, list hop SNR rows, and map hops with SNR-colored segments after a successful run.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalTime::class, ExperimentalFoundationApi::class)
@Composable
fun TracePathScreen(
    runTrace: TracePathRunner?,
    workspace: TracePathWorkspace? = null,
    /**
     * This device's position for the start and end hops (iOS `bestAvailableLocation`): the phone's own
     * fix when there is one, else the connected radio's coordinates. Read at each run so a fix arriving
     * mid-session lands on the next trace. `null` leaves both ends unplaced.
     */
    deviceLocation: () -> LatLng?,
    modifier: Modifier = Modifier,
    savedPathStore: SavedTracePathStore? = null,
    radioId: () -> Uuid? = { null },
    contactService: () -> ContactService? = { null },
    /**
     * Live LoRa/firmware fields for the connected radio; gates the hash-size override picker on
     * firmware support so a user can't pick a width the radio silently ignores. Absent (`null`)
     * hides the picker, matching iOS's `connectedDevice == nil` behavior.
     */
    radioSnapshot: () -> RadioDeviceSnapshot? = { null },
    /**
     * The connected radio's configured `pathHashMode`. It is the effective trace mode whenever the user
     * sets no per-trace override, so a 2- or 4-byte-hash radio gets hops of the width it expects
     * (iOS `effectiveTraceMode`). `null` (disconnected, or unreadable) falls back to mode 0.
     */
    devicePathHashMode: suspend () -> Int? = { null },
    /** Passively-heard nodes for the Add-Hop picker's Discovered section. */
    discoveredNodes: suspend (Uuid) -> List<DiscoveredNode> = { emptyList() },
    /** Per-radio recent-hop LRU backing the picker's Recent section; `null` disables that section. */
    recentHopsStore: RecentHopsStore? = null,
    preferences: PreferencesStore? = null,
    mapDisplayPreferences: one.meshcore.shared.map.MapDisplayPreferencesStore? = null,
    isOffline: Boolean = LocalMapOffline.current,
    locationService: LocationService? = null,
    onBack: () -> Unit = {},
) {
    val saveFailedMessage = stringResource(Res.string.contacts_trace_map_save_failed_message)
    val scope = rememberCoroutineScope()
    val mapChrome = rememberMapDisplayChrome(mapDisplayPreferences, preferences)
    val haptics = rememberAppHaptics()
    // Hoisted above the saved-paths guard so the tick survives the section appearing and disappearing.
    // A trace or batch run holds this screen open for a long stretch, and a frozen clock would leave a
    // path run an hour ago still reading "Now".
    val nowSeconds by rememberMinuteTickSeconds()
    val emptyPathHint = stringResource(Res.string.contacts_trace_list_empty_path)
    val failedLabel = stringResource(Res.string.contacts_trace_failed)
    val allFailedTemplate = stringResource(Res.string.contacts_trace_error_all_failed)
    val loadFailedMessage = stringResource(Res.string.tools_saved_paths_load_failed)
    val renameFailedMessage = stringResource(Res.string.tools_saved_paths_rename_failed)
    val deleteFailedMessage = stringResource(Res.string.tools_saved_paths_delete_failed)
    val pathNamePrefixTemplate = stringResource(Res.string.contacts_path_name_prefix)
    val pathNameTwoTemplate = stringResource(Res.string.contacts_path_name_two_endpoints)
    val pathNameMultiTemplate = stringResource(Res.string.contacts_path_name_multiple_endpoints)
    val currentContactServiceProvider by rememberUpdatedState(contactService)
    val currentRadioIdProvider by rememberUpdatedState(radioId)
    val currentRadioSnapshotProvider by rememberUpdatedState(radioSnapshot)
    val currentDeviceLocationProvider by rememberUpdatedState(deviceLocation)
    val currentDiscoveredNodesProvider by rememberUpdatedState(discoveredNodes)
    val currentRadioId = radioId()
    val currentContactService = contactService()
    val messages = TracePathWorkspace.Messages(emptyPathHint, failedLabel, allFailedTemplate, loadFailedMessage)
    val currentMessages by rememberUpdatedState(messages)
    val currentRunner by rememberUpdatedState(runTrace)
    val currentDeviceHashMode by rememberUpdatedState(devicePathHashMode)
    val localModel = remember(workspace) {
        if (workspace != null) {
            null
        } else {
            TracePathViewModel(
                scope,
                TracePathViewModel.Dependencies(
                    runner = { currentRunner },
                    builder = TracePathBuilder.Dependencies(
                        contactService = { currentContactServiceProvider() },
                        radioId = { currentRadioIdProvider() },
                        supportsHashSizeOverride = {
                            currentRadioSnapshotProvider()?.supportsTraceHashSizeOverride == true
                        },
                        discoveredNodes = { radio -> currentDiscoveredNodesProvider(radio) },
                    ),
                    deviceHashMode = { currentDeviceHashMode() },
                    deviceLocation = { currentDeviceLocationProvider() },
                    savedPathStore = savedPathStore,
                    messages = {
                        val copy = currentMessages
                        TracePathViewModel.Messages(copy.emptyPath, copy.failed, copy.allFailed, copy.loadFailed)
                    },
                ),
                recentHopsStore,
            )
        }
    }
    val model = workspace?.bind(messages) ?: requireNotNull(localModel)
    val modelState = model.state.collectAsStateWithLifecycle()
    val ui = remember(model, modelState.value.ownerGeneration) { TracePathUiState(model, modelState) }
    val builder = model.builder
    val builderState by builder.state.collectAsStateWithLifecycle()
    val resetPath = { model.clearPath() }
    LaunchedEffect(preferences) {
        ui.mapMode = preferences?.getString(PersistenceKeys.TRACE_PATH_VIEW_MODE) ==
            PersistenceKeys.TRACE_PATH_VIEW_MODE_MAP
        val raw = preferences?.getString(PersistenceKeys.MAP_FILTER_TRACE_PATH)
        ui.mapFilter = MapFilterState.fromRaw(raw, MapFilterHost.TRACE_PATH)
    }
    DisposableEffect(model, ui) {
        val token = model.attach()
        onDispose { model.detach(token, ui.showingListResults) }
    }
    LaunchedEffect(model, ui, modelState.value.successId, modelState.value.isRunning, ui.mapMode) {
        if (model.consumeStoredResult()) ui.showingListResults = true
    }
    val hashOverride by builder.traceHashMode.collectAsStateWithLifecycle()
    val effectiveMode = effectiveTraceMode(hashOverride, ui.deviceHashMode)
    val hashSize = traceHashSize(effectiveMode)
    val outboundPath = builderState.editablePath
    val currentPathBytes = remember(outboundPath, ui.autoReturn) { builder.fullPathBytes(ui.autoReturn) }
    val snrFormatter = rememberSnrBadgeFormatter()
    val mapPlot = remember(ui.result, snrFormatter) {
        ui.result?.let { TracePathMapBuilder.buildFromResult(it, formatter = snrFormatter) }
    }
    val matchingSaved = remember(ui.result, ui.savedPaths) {
        val success = ui.result?.takeIf { it.success } ?: return@remember null
        findMatchingSavedPath(ui.savedPaths, success.tracedPathBytes)
    }
    val reloadSaved: suspend () -> Unit = { model.reloadSaved() }
    LaunchedEffect(model, currentRadioId, currentContactService) { model.load() }
    LaunchedEffect(ui.errorMessage) {
        if (ui.errorMessage != null) haptics.failure()
    }

    val locatedMapCandidates = remember(builderState.availableNodes) {
        builderState.availableNodes.filter { it.hasLocation }
    }
    val discoveredRepeaters = builderState.discoveredRepeaters
    val pathMemberIds = remember(outboundPath, locatedMapCandidates, discoveredRepeaters) {
        TracePathMapPinFilter.pathMemberIds(outboundPath, locatedMapCandidates, discoveredRepeaters)
    }
    val visibleContacts = remember(locatedMapCandidates, pathMemberIds, ui.mapFilter) {
        TracePathMapPinFilter.visibleContacts(locatedMapCandidates, pathMemberIds, ui.mapFilter)
    }
    val visibleDiscovered = remember(
        discoveredRepeaters,
        builderState.availableNodes,
        pathMemberIds,
        ui.mapFilter,
    ) {
        val contactKeys = builderState.availableNodes.map { it.publicKey.toUppercaseHex() }.toSet()
        TracePathMapPinFilter.visibleDiscovered(
            discovered = discoveredRepeaters,
            contactKeys = contactKeys,
            pathMemberIds = pathMemberIds,
            filter = ui.mapFilter,
        )
    }
    val mapPathState = remember(outboundPath, locatedMapCandidates, discoveredRepeaters) {
        TracePathHopSelection.pathState(outboundPath, locatedMapCandidates, discoveredRepeaters)
    }
    val candidatePoints = remember(visibleContacts, visibleDiscovered, mapPathState) {
        TracePathCandidateMapBuilder.points(visibleContacts, mapPathState, showLabels = true) +
            TracePathCandidateMapBuilder.discoveredPoints(visibleDiscovered, mapPathState, showLabels = true)
    }
    val startLocation = deviceLocation()
    val hopCoordinates = remember(outboundPath, locatedMapCandidates, discoveredRepeaters, startLocation) {
        TracePathCandidateMapBuilder.orderedHopCoordinates(
            outboundPath,
            locatedMapCandidates,
            discoveredRepeaters,
            start = startLocation,
        )
    }
    val candidateLines = remember(hopCoordinates) { TracePathCandidateMapBuilder.lines(hopCoordinates) }
    val canRunTrace = !ui.isRunning && runTrace != null && outboundPath.isNotEmpty()
    // A result only describes the path it was traced over; any hop edit since then makes it stale, which
    // both blocks Save and drops the map back to the plain editable pins (iOS canSavePath).
    val resultMatchesPath = remember(ui.result, currentPathBytes) {
        ui.result?.tracedPathBytes?.contentEquals(currentPathBytes) == true
    }
    val canSave = remember(currentPathBytes, ui.result, ui.batchResults, ui.batchEnabled, ui.savedPaths) {
        canSaveTracePath(currentPathBytes, ui.result, ui.batchResults, ui.batchEnabled, ui.savedPaths)
    }
    val performRun: () -> Unit = { model.run() }
    val cancelRun: () -> Unit = { model.cancelRun() }
    val scrollState = rememberScrollState()
    val runTraceRequester = remember { BringIntoViewRequester() }

    TracePathChrome(
        modifier = modifier,
        onBack = onBack,
        savedPathStore = savedPathStore,
        radioId = radioId,
        mapMode = ui.mapMode,
        onMapMode = { enabled ->
            ui.mapMode = enabled
            persistTracePathViewMode(scope, preferences, enabled)
        },
        onShowSavedPaths = { ui.showingSavedPaths = true },
        showJumpToPath = ui.showJumpToPath,
        onJumpToPath = {
            ui.showJumpToPath = false
            scope.launch { runTraceRequester.bringIntoView() }
        },
        haptics = haptics,
        list = {
            TracePathListPane(
                ui = ui,
                scrollState = scrollState,
                runTraceRequester = runTraceRequester,
                outboundPath = outboundPath,
                builder = builder,
                emptyPathHint = emptyPathHint,
                supportsHashOverride = radioSnapshot()?.supportsTraceHashSizeOverride == true,
                hashSize = hashSize,
                canRunTrace = canRunTrace,
                isRadioReady = runTrace != null,
                onRun = performRun,
                onCancel = cancelRun,
                savedPathStore = savedPathStore,
                hasRadio = radioId() != null,
                canSave = canSave,
                onSave = {
                    ui.saveNameDraft = generateTracePathName(
                        resolvedNames = outboundPath.mapNotNull { it.resolvedName },
                        fallbackHex = builder.outboundPathString,
                        prefix = { formatTemplate(pathNamePrefixTemplate, listOf(it)) },
                        twoEndpoints = { a, b -> formatTemplate(pathNameTwoTemplate, listOf(a, b)) },
                        multipleEndpoints = { a, b ->
                            formatTemplate(pathNameMultiTemplate, listOf(a, b))
                        },
                    )
                },
                matchingSaved = matchingSaved,
                resultMatchesPath = resultMatchesPath,
            )
        },
        map = {
            TracePathMapPane(
                ui = ui,
                mapPlot = mapPlot,
                resultMatchesPath = resultMatchesPath,
                candidatePoints = candidatePoints,
                candidateLines = candidateLines,
                hopCoordinates = hopCoordinates,
                outboundPath = outboundPath,
                canRunTrace = canRunTrace,
                isRadioReady = runTrace != null,
                isOffline = isOffline,
                mapChrome = mapChrome,
                locationService = locationService,
                deviceLocation = deviceLocation,
                visibleContacts = visibleContacts,
                visibleDiscovered = visibleDiscovered,
                mapPathState = mapPathState,
                builder = builder,
                haptics = haptics,
                preferences = preferences,
                scope = scope,
                onRun = performRun,
                onClear = resetPath,
                onViewResults = {
                    ui.mapMode = false
                    persistTracePathViewMode(scope, preferences, enabled = false)
                },
            )
        },
    )
    TracePathCovers(
        ui = ui,
        state = TracePathCoversState(
            resultMatchesPath = resultMatchesPath,
            hashSize = hashSize,
            nowSeconds = nowSeconds,
        ),
        dependencies = TracePathCoversDependencies(
            builder = builder,
            savedPathStore = savedPathStore,
            radioId = radioId,
            scope = scope,
            haptics = haptics,
            reloadSaved = reloadSaved,
            resetPath = resetPath,
        ),
        messages = TracePathCoversMessages(
            saveFailed = saveFailedMessage,
            loadFailed = loadFailedMessage,
            renameFailed = renameFailedMessage,
            deleteFailed = deleteFailedMessage,
        ),
    )
}

internal const val JUMP_FAB_EDGE = 16
