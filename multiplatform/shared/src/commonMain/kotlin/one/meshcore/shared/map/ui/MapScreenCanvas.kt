@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package one.meshcore.shared.map.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.MapNodesBuilder
import one.meshcore.shared.map.MapPinFilter
import one.meshcore.shared.map.MapStyleDisabledReason
import one.meshcore.shared.map.MapStyleSelection
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.map_style_no_offline_coverage
import one.meshcore.shared.resources.map_style_requires_network
import one.meshcore.shared.theme.AppColorSchemePreference
import org.jetbrains.compose.resources.stringResource

private val MAP_FAB_INSET = 16.dp
private const val NO_PACK_REVISION = 0

@Composable
private fun <Owner, T> collectedFrom(owner: Owner?, default: T, select: (Owner) -> StateFlow<T>): State<T> {
    val flow = if (owner == null) null else select(owner)
    val source = remember(flow, default) { flow ?: MutableStateFlow(default) }
    return source.collectAsStateWithLifecycle()
}

@Composable
private fun mapStyleDisabledHints(
    reasons: Map<MapStyleSelection, MapStyleDisabledReason?>,
): Map<MapStyleSelection, String?> {
    val requiresNetwork = stringResource(Res.string.map_style_requires_network)
    val noCoverage = stringResource(Res.string.map_style_no_offline_coverage)
    return reasons.mapValues { (_, reason) ->
        mapStyleDisabledHint(reason, requiresNetwork, noCoverage)
    }
}

@Composable
internal fun MapCanvas(
    state: MapScreenState,
    scope: CoroutineScope,
    contentPadding: PaddingValues,
    inputs: MapScreenInputs,
) {
    val preferences = inputs.preferences
    val mapDisplayPreferences = inputs.mapDisplayPreferences
    val locationService = inputs.locationService
    val nodeLocation = inputs.nodeLocation
    val discoveredNodeStore = inputs.discoveredNodeStore
    val radioId = inputs.radioId
    val offlineMapPackRegistry = inputs.offlineMapPackRegistry
    val isOffline = inputs.isOffline
    val droppedMapPin = inputs.droppedMapPin
    val onSetDroppedMapPin = inputs.onSetDroppedMapPin
    val showLabels = collectedFrom(
        mapDisplayPreferences,
        PersistenceKeys.DEFAULT_MAP_SHOW_LABELS,
    ) { it.mapShowLabels }.value
    val clusteringEnabled = collectedFrom(
        mapDisplayPreferences,
        PersistenceKeys.DEFAULT_MAP_CLUSTERING_ENABLED,
    ) { it.mapClusteringEnabled }.value
    val northLocked = collectedFrom(
        mapDisplayPreferences,
        PersistenceKeys.DEFAULT_MAP_NORTH_LOCKED,
    ) { it.mapNorthLocked }.value
    val mapColorScheme = collectedFrom(
        mapDisplayPreferences,
        AppColorSchemePreference.SYSTEM,
    ) { it.mapColorScheme }.value
    val mapChrome = MapDisplayChrome(
        style = state.mapStyle,
        showLabels = showLabels,
        clusteringEnabled = clusteringEnabled,
        northLocked = northLocked,
        colorScheme = mapColorScheme,
        setStyle = { style ->
            state.onMapStyleSelected(style, mapDisplayPreferences, scope, preferences)
        },
        toggleLabels = { toggleMapDisplay(mapDisplayPreferences, MapDisplayToggle.LABELS, !showLabels) },
        toggleClustering = {
            toggleMapDisplay(mapDisplayPreferences, MapDisplayToggle.CLUSTERING, !clusteringEnabled)
        },
        toggleNorthLock = {
            toggleMapDisplay(mapDisplayPreferences, MapDisplayToggle.NORTH_LOCK, !northLocked)
        },
    )
    val userLocation = collectedFrom<LocationService, LatLng?>(locationService, null) { it.currentLocation }.value
    val discoveredSnapshot = collectedFrom(
        discoveredNodeStore(),
        emptyList<DiscoveredNodeRecord>(),
    ) { it.snapshot }.value
    val packRevision = collectedFrom(offlineMapPackRegistry, NO_PACK_REVISION) { it.revision }.value
    val activeRadio = radioId()
    val contactsById = state.contactsById
    val mapFilter = state.mapFilter
    val now by rememberMapFilterTime(mapFilter.lastHeard, inputs.nowEpochSeconds)
    val inboundHopByKey = remember(discoveredSnapshot, activeRadio) {
        mapInboundHops(discoveredSnapshot, activeRadio)
    }
    val discoveredForRadio = remember(discoveredSnapshot, activeRadio, contactsById, mapFilter, now) {
        discoveredRowsForRadio(discoveredSnapshot, activeRadio, contactsById.values, mapFilter, now)
    }
    val discoveredById = remember(discoveredForRadio) { discoveredForRadio.associateBy { it.id.value } }
    val visibleContacts = remember(contactsById, mapFilter, inboundHopByKey, now) {
        MapPinFilter.contacts(contactsById.values.toList(), mapFilter, now, inboundHopByKey)
    }
    val contactPoints = remember(visibleContacts) { MapNodesBuilder.pointsFromContacts(visibleContacts) }
    val visibleContactsById = remember(contactPoints, visibleContacts) {
        val pointIds = contactPoints.map { it.id }.toSet()
        visibleContacts.filter { it.id.value in pointIds }.associateBy { it.id.value }
    }
    LaunchedEffect(visibleContactsById, discoveredById) {
        state.selectedContact = state.selectedContact?.let { visibleContactsById[it.id.value] }
        state.contactDetail = state.contactDetail?.let { visibleContactsById[it.id.value] }
        state.selectedDiscovered = state.selectedDiscovered?.let { discoveredById[it.id.value] }
        state.discoveredDetail = state.discoveredDetail?.let { discoveredById[it.id.value] }
    }
    val discoveredPoints = remember(discoveredForRadio) {
        MapNodesBuilder.pointsFromDiscovered(discoveredForRadio, emptySet())
    }
    val droppedPinRequest by droppedMapPin.collectAsStateWithLifecycle()
    val droppedPin = focusedMapPin(droppedPinRequest)
    val points = contactPoints + discoveredPoints + listOfNotNull(droppedPin)
    val isNetworkAvailable = !isOffline()
    val viewportRegion = state.viewportRegion
    // Gate styles that need a network route or an offline pack while the device is offline, mirroring iOS
    // MapControlsToolbar. Recomputes when connectivity flips, the completed-pack set changes, or the map
    // is panned somewhere a pack no longer covers.
    val disabledReasons = remember(isNetworkAvailable, packRevision, viewportRegion) {
        mapStyleDisabledReasons(isNetworkAvailable, offlineMapPackRegistry, viewportBoundsOrNull(viewportRegion))
    }
    val disabledReasonHints = mapStyleDisabledHints(disabledReasons)
    Box(
        Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        if (state.prefsLoaded) {
            MapLibreHost(
                points = points,
                style = mapChrome.style,
                initialCamera = state.initialCamera,
                cameraTarget = state.cameraTarget.region,
                cameraTargetVersion = state.cameraTarget.version,
                colorSchemePreference = mapChrome.colorScheme,
                isOffline = !isNetworkAvailable,
                showLabels = mapChrome.showLabels,
                clusteringEnabled = mapChrome.clusteringEnabled,
                northLocked = mapChrome.northLocked,
                showsUserLocation = locationService != null,
                showsScale = true,
                onStyleLoadingChange = { state.isStyleLoading = it },
                onCameraIdle = { region ->
                    state.isCenteredOnUser = false
                    state.viewportRegion = region
                    scope.persistMapCamera(preferences, region)
                },
                onPointClick = { id -> state.onMapPointClick(id, visibleContactsById, discoveredById) },
                onMapClick = { state.dismissMapCallouts() },
                onMapLongPress = { coordinate -> state.dropMapPin(coordinate, onSetDroppedMapPin) },
                modifier = Modifier.fillMaxSize(),
            )
            Column(Modifier.align(Alignment.TopStart).padding(MAP_FAB_INSET)) {
                MapOfflineBadge(isOffline())
                MapActiveFiltersBar(
                    filter = mapFilter,
                    onChange = { state.updateMapFilter(it, preferences, scope) },
                    onEdit = { state.showAdvancedFilters = true },
                )
            }
            MapStyleOrContactsOverlay(state.isStyleLoading, state.contactsLoadChrome)
            MapCanvasControls(
                state = state,
                scope = scope,
                preferences = preferences,
                mapChrome = mapChrome,
                locationService = locationService,
                nodeLocation = nodeLocation,
                userLocation = userLocation,
                disabledReasons = disabledReasons,
                disabledReasonHints = disabledReasonHints,
                contactPoints = contactPoints,
                discoveredPoints = discoveredPoints,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(MAP_FAB_INSET),
            )
        }
        if (state.showAdvancedFilters) {
            MapAdvancedFiltersSheet(
                filter = mapFilter,
                onChange = { state.updateMapFilter(it, preferences, scope) },
                onDismiss = { state.showAdvancedFilters = false },
            )
        }
    }
}
