@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package one.meshcore.shared.map.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.MapFilterHost
import one.meshcore.shared.map.MapFilterState
import one.meshcore.shared.map.MapPoint
import one.meshcore.shared.map.MapStyleDisabledReason
import one.meshcore.shared.map.MapStyleSelection
import one.meshcore.shared.map.hasPinsForCenterAll
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferencesStore
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.map_controls_center_all
import one.meshcore.shared.resources.map_controls_center_on_my_location
import one.meshcore.shared.resources.map_offline_badge_label
import one.meshcore.shared.ui.AnnounceOnAppear
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

private val MAP_LOADING_OVERLAY_PADDING = 16.dp
private val MAP_FAB_GAP = 8.dp

@Composable
internal fun MapOfflineBadge(isOffline: Boolean) {
    if (!isOffline) return
    val offlineLabel = stringResource(Res.string.map_offline_badge_label)
    // iOS `OfflineBadge` posts an announcement when the capsule appears.
    AnnounceOnAppear(announce = true, message = offlineLabel)
    Text(
        text = offlineLabel,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .padding(12.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun MapContactsLoadingOverlay() {
    Box(
        Modifier
            .fillMaxSize()
            .background(mapContactsLoadingScrim(MaterialTheme.colorScheme.scrim)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surface)
                .padding(MAP_LOADING_OVERLAY_PADDING),
        ) {
            CircularProgressIndicator()
        }
    }
}

@Composable
internal fun MapStyleOrContactsOverlay(isStyleLoading: Boolean, contactsLoadChrome: Boolean) {
    if (isStyleLoading || contactsLoadChrome) MapContactsLoadingOverlay()
}

@Composable
private fun MapFloatingControls(
    mapChrome: MapDisplayChrome,
    styleDisabled: (MapStyleSelection) -> Boolean,
    styleDisabledReason: (MapStyleSelection) -> String?,
    showLocate: Boolean,
    isCenteredOnUser: Boolean,
    onLocate: () -> Unit,
    showCenterAll: Boolean,
    onCenterAll: () -> Unit,
    filter: MapFilterState,
    onFilterChange: (MapFilterState) -> Unit,
    onAdvancedFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val surfaceContainer = FloatingActionButtonDefaults.containerColor
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(MAP_FAB_GAP),
        horizontalAlignment = Alignment.End,
    ) {
        if (showLocate) {
            SmallFloatingActionButton(
                onClick = onLocate,
                containerColor = if (isCenteredOnUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    surfaceContainer
                },
            ) {
                Icon(
                    imageVector = AppIcons.MyLocation,
                    contentDescription = stringResource(Res.string.map_controls_center_on_my_location),
                )
            }
        }
        if (showCenterAll) {
            SmallFloatingActionButton(
                onClick = onCenterAll,
                containerColor = surfaceContainer,
            ) {
                Icon(
                    imageVector = AppIcons.LocationOn,
                    contentDescription = stringResource(Res.string.map_controls_center_all),
                )
            }
        }
        MapFilterButton(
            host = MapFilterHost.MAIN_MAP,
            filter = filter,
            onFilterChange = onFilterChange,
            onAdvancedFilters = onAdvancedFilters,
        )
        MapOptionsMenu(
            chrome = mapChrome,
            styleDisabled = styleDisabled,
            styleDisabledReason = styleDisabledReason,
        )
    }
}

@Composable
internal fun MapCanvasControls(
    state: MapScreenState,
    scope: CoroutineScope,
    preferences: PreferencesStore,
    mapChrome: MapDisplayChrome,
    locationService: LocationService?,
    nodeLocation: () -> LatLng?,
    userLocation: LatLng?,
    disabledReasons: Map<MapStyleSelection, MapStyleDisabledReason?>,
    disabledReasonHints: Map<MapStyleSelection, String?>,
    contactPoints: List<MapPoint>,
    discoveredPoints: List<MapPoint>,
    modifier: Modifier = Modifier,
) {
    MapFloatingControls(
        mapChrome = mapChrome,
        styleDisabled = { disabledReasons[it] != null },
        styleDisabledReason = { disabledReasonHints[it] },
        showLocate = locationService != null,
        isCenteredOnUser = state.isCenteredOnUser,
        onLocate = state.locateOnUser(
            scope = scope,
            preferences = preferences,
            inputs = MapLocateInputs(
                service = locationService,
                userLocation = { userLocation },
                nodeLocation = nodeLocation,
            ),
        ),
        filter = state.mapFilter,
        onFilterChange = { next ->
            state.updateMapFilter(next, preferences, scope)
        },
        onAdvancedFilters = { state.showAdvancedFilters = true },
        showCenterAll = hasPinsForCenterAll(contactPoints, discoveredPoints),
        onCenterAll = { state.centerAll(contactPoints, discoveredPoints, scope, preferences) },
        modifier = modifier,
    )
}

internal fun MapScreenState.updateMapFilter(
    next: MapFilterState,
    preferences: PreferencesStore,
    scope: CoroutineScope,
) {
    val sanitized = next.sanitized(MapFilterHost.MAIN_MAP)
    dismissMapCallouts()
    mapFilter = sanitized
    scope.launch { preferences.putString(PersistenceKeys.MAP_FILTER_MAIN_MAP, sanitized.toStorageString()) }
}
