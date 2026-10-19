// PortedFrom: MC1/Views/Map/MapView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Map/MapViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.map

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.maps.ContactMapType
import com.meshcoreone.android.core.maps.GeoPoint
import com.meshcoreone.android.core.maps.GeoBounds
import com.meshcoreone.android.core.maps.MapAvailability
import com.meshcoreone.android.core.maps.MapCamera
import com.meshcoreone.android.core.maps.MapFilterHost
import com.meshcoreone.android.core.maps.MapFilterPreferences
import com.meshcoreone.android.core.maps.MapFilterState
import com.meshcoreone.android.core.maps.MapLibreMapSurface
import com.meshcoreone.android.core.maps.MapLibreOpenFreeMap
import com.meshcoreone.android.core.maps.MapLibreRuntime
import com.meshcoreone.android.core.maps.MapMarker
import com.meshcoreone.android.core.maps.MapPinStyle
import com.meshcoreone.android.core.maps.MapPresentationState
import com.meshcoreone.android.core.maps.MapStyle
import com.meshcoreone.android.core.maps.OfflineLayer
import com.meshcoreone.android.core.maps.OfflineMapError
import com.meshcoreone.android.core.maps.OfflinePack
import com.meshcoreone.android.core.maps.OfflinePackState
import com.meshcoreone.android.core.maps.boundingCamera
import com.meshcoreone.android.core.ui.FeatureShellCopy
import com.meshcoreone.android.core.ui.ScaffoldFeatureContent
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun MapEntry(
    route: FeatureRoute,
    onNavigate: (FeatureRoute) -> Unit,
    dependencies: MapFeatureDependencies? = null,
    focus: GeoPoint? = null,
    onFocusConsumed: () -> Unit = {},
) {
    if (dependencies == null) {
        ScaffoldFeatureContent(
            FeatureId.MAP,
            route,
            FeatureShellCopy(
                stringResource(R.string.tab_map),
                stringResource(R.string.scaffold_map_description),
                stringResource(R.string.scaffold_open_map),
            ),
            onNavigate,
        )
        return
    }
    MapContent(dependencies, focus, onFocusConsumed)
}

@Composable
private fun MapRuntimeUnavailable() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.tab_map), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.scaffold_map_description))
            }
        }
    }
}

@Composable
private fun MapContent(
    dependencies: MapFeatureDependencies,
    focus: GeoPoint?,
    onFocusConsumed: () -> Unit,
) {
    if (!MapLibreRuntime.isSupported) {
        MapRuntimeUnavailable()
        return
    }
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
    var filter by remember {
        mutableStateOf(
            MapFilterPreferences.decodeOrSeed(
                preferences.getString(FILTER_KEY, null).orEmpty(),
                MapFilterHost.MAIN_MAP,
            ),
        )
    }
    var clustering by remember { mutableStateOf(preferences.getBoolean(CLUSTER_KEY, true)) }
    var labels by remember { mutableStateOf(preferences.getBoolean(LABEL_KEY, true)) }
    var content by remember { mutableStateOf<MapFeatureContent?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var refreshGeneration by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<MapMarker?>(null) }
    var listVisible by remember { mutableStateOf(false) }
    var focusedPoint by remember { mutableStateOf(focus) }
    var offlineVisible by remember { mutableStateOf(false) }
    var camera by remember {
        mutableStateOf(preferences.getString(CAMERA_KEY, null)?.let(MapCamera::decode))
    }

    LaunchedEffect(dependencies, refreshGeneration) {
        loadError = false
        try {
            val loaded = dependencies.dataSource.load()
            content = loaded
            if (camera == null && focusedPoint == null) {
                camera = loaded.markers.map { it.marker.position }.boundingCamera()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadError = true
        }
    }
    LaunchedEffect(focus) {
        if (focus != null) {
            focusedPoint = focus
            camera = MapCamera(focus, 0.05, 0.05)
            onFocusConsumed()
        }
    }

    val filtered = content?.markers.orEmpty().filter { item ->
        (!filter.favoritesOnly || item.isFavorite) &&
            (filter.favoritesOnly || !item.isDiscovered || filter.effectiveShowDiscovered) &&
            filter.allows(item.type)
    }.map { it.marker }.toMutableList()
    focusedPoint?.let {
        filtered += MapMarker(
            id = UUID(it.latitude.toBits(), it.longitude.toBits()),
            position = it,
            style = MapPinStyle.DROPPED_PIN,
            label = stringResource(R.string.l10n_app_map_map_callout_message),
            clusterable = false,
        )
    }

    val state = MapPresentationState(
        availability = MapAvailability.Available(MapLibreOpenFreeMap.catalog),
        style = MapStyle.STANDARD,
        camera = camera,
        points = filtered,
        lines = content?.lines.orEmpty(),
    )

    Box(Modifier.fillMaxSize()) {
        MapLibreMapSurface(
            presentation = state,
            clusteringEnabled = clustering,
            labelsEnabled = labels,
            accessibilityLabel = stringResource(R.string.tab_map),
            onCameraChanged = {
                camera = it
                preferences.edit().putString(CAMERA_KEY, it.encode()).apply()
            },
            onMarkerSelected = { selected = it },
            modifier = Modifier.fillMaxSize(),
        )
        MapControls(
            filter = filter,
            onFilter = {
                filter = it
                preferences.edit().putString(FILTER_KEY, MapFilterPreferences.encode(it, MapFilterHost.MAIN_MAP)).apply()
            },
            clustering = clustering,
            onClustering = {
                clustering = it
                preferences.edit().putBoolean(CLUSTER_KEY, it).apply()
            },
            labels = labels,
            onLabels = {
                labels = it
                preferences.edit().putBoolean(LABEL_KEY, it).apply()
            },
            listVisible = listVisible,
            onListVisible = { listVisible = it },
            onRefresh = { refreshGeneration += 1 },
            onOffline = { offlineVisible = !offlineVisible },
            modifier = Modifier.align(Alignment.TopCenter),
        )
        Attribution(
            context = context,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        if (content == null && !loadError) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        if (loadError) {
            Button(onClick = { refreshGeneration += 1 }, modifier = Modifier.align(Alignment.Center)) {
                Text(stringResource(R.string.l10n_app_map_map_controls_refresh))
            }
        }
        selected?.let { marker ->
            Card(Modifier.align(Alignment.BottomStart).padding(bottom = 52.dp, start = 12.dp, end = 12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(marker.label ?: stringResource(R.string.l10n_app_contacts_contacts_list_selectnode))
                    Text("${marker.position.latitude}, ${marker.position.longitude}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { selected = null }) {
                        Text(stringResource(R.string.l10n_app_map_map_common_done))
                    }
                }
            }
        }
        if (listVisible) {
            val mapListDescription = stringResource(R.string.tab_map)
            Card(Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.68f).padding(12.dp)) {
                LazyColumn(
                    Modifier.semantics { contentDescription = mapListDescription },
                ) {
                    items(filtered, key = { it.id }) { marker ->
                        TextButton(onClick = { selected = marker }, modifier = Modifier.fillMaxWidth()) {
                            Text(marker.label ?: "${marker.position.latitude}, ${marker.position.longitude}")
                        }
                    }
                }
            }
        }
        if (offlineVisible) {
            OfflineMapsPanel(
                controller = dependencies.offlineMaps,
                camera = camera,
                onDismiss = { offlineVisible = false },
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun MapControls(
    filter: MapFilterState,
    onFilter: (MapFilterState) -> Unit,
    clustering: Boolean,
    onClustering: (Boolean) -> Unit,
    labels: Boolean,
    onLabels: (Boolean) -> Unit,
    listVisible: Boolean,
    onListVisible: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onOffline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.padding(8.dp)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = onRefresh, label = { Text(stringResource(R.string.l10n_app_map_map_controls_refresh)) })
                AssistChip(
                    onClick = onOffline,
                    label = { Text(stringResource(R.string.l10n_app_settings_offlinemaps_title)) },
                )
                FilterChip(
                    selected = listVisible,
                    onClick = { onListVisible(!listVisible) },
                    label = { Text(stringResource(R.string.l10n_app_contacts_contacts_trace_mode_list)) },
                )
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = true,
                    onClick = {},
                    label = { Text(stringResource(R.string.l10n_app_map_map_style_standard)) },
                )
                FilterChip(
                    selected = false,
                    enabled = false,
                    onClick = {},
                    label = { Text(stringResource(R.string.l10n_app_map_map_style_satellite)) },
                )
                FilterChip(
                    selected = false,
                    enabled = false,
                    onClick = {},
                    label = { Text(stringResource(R.string.l10n_app_map_map_style_topo)) },
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = filter.favoritesOnly,
                    onClick = { onFilter(filter.setFavoritesOnly(!filter.favoritesOnly)) },
                    label = { Text(stringResource(R.string.l10n_app_contacts_contacts_pathedit_filter_favorites)) },
                )
                FilterChip(
                    selected = filter.showDiscovered,
                    enabled = !filter.favoritesOnly,
                    onClick = { onFilter(filter.setShowDiscovered(!filter.showDiscovered)) },
                    label = { Text(stringResource(R.string.l10n_app_map_map_callout_discovered)) },
                )
                FilterChip(
                    selected = clustering,
                    onClick = { onClustering(!clustering) },
                    label = { Text(stringResource(R.string.l10n_app_map_map_controls_clusternodes)) },
                )
                FilterChip(
                    selected = labels,
                    onClick = { onLabels(!labels) },
                    label = { Text(stringResource(R.string.l10n_app_map_map_controls_showlabels)) },
                )
                ContactMapType.entries.forEach { type ->
                    FilterChip(
                        selected = filter.allows(type),
                        enabled = !filter.favoritesOnly,
                        onClick = { onFilter(filter.setContactType(type, !filter.allows(type), MapFilterHost.MAIN_MAP)) },
                        label = {
                            Text(
                                stringResource(
                                    when (type) {
                                        ContactMapType.CHAT -> R.string.l10n_app_map_map_nodekind_chatcontact
                                        ContactMapType.REPEATER -> R.string.l10n_app_map_map_nodekind_repeater
                                        ContactMapType.ROOM -> R.string.l10n_app_map_map_nodekind_room
                                    },
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflineMapsPanel(
    controller: com.meshcoreone.android.core.maps.OfflineMapController,
    camera: MapCamera?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val packs by controller.packs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var regionName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var deleting by remember { mutableStateOf<OfflinePack?>(null) }

    Card(modifier.fillMaxWidth(0.9f).padding(12.dp)) {
        LazyColumn(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.l10n_app_settings_offlinemaps_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.l10n_app_map_map_common_done))
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = regionName,
                    onValueChange = { regionName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.l10n_app_settings_offlinemaps_regionname)) },
                )
            }
            item {
                Button(
                    enabled = regionName.isNotBlank() && camera != null,
                    onClick = {
                        val bounds = camera?.toBounds() ?: return@Button
                        scope.launch {
                            controller.downloadRegion(
                                name = regionName.trim(),
                                bounds = bounds,
                                layers = setOf(OfflineLayer.BASE),
                            ).onSuccess {
                                regionName = ""
                            }.onFailure {
                                error = it
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.l10n_app_settings_offlinemaps_downloadregion))
                }
            }
            if (packs.isEmpty()) {
                item { Text(stringResource(R.string.l10n_app_settings_offlinemaps_emptydescription)) }
            } else {
                items(packs, key = { it.id }) { pack ->
                    OfflinePackRow(
                        pack = pack,
                        onPauseResume = {
                            scope.launch {
                                try {
                                    if (pack.state == OfflinePackState.PAUSED) controller.resume(pack) else controller.pause(pack)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = failure
                                }
                            }
                        },
                        onDelete = { deleting = pack },
                    )
                }
            }
        }
    }

    error?.let { failure ->
        AlertDialog(
            onDismissRequest = { error = null },
            confirmButton = {
                TextButton(onClick = { error = null }) {
                    Text(stringResource(R.string.l10n_app_map_map_common_done))
                }
            },
            text = { Text(offlineErrorMessage(failure)) },
        )
    }
    deleting?.let { pack ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        scope.launch {
                            try {
                                controller.delete(pack)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.l10n_app_localizable_common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.l10n_app_settings_offlinemaps_cancel))
                }
            },
            text = { Text(pack.name) },
        )
    }
}

@Composable
private fun OfflinePackRow(
    pack: OfflinePack,
    onPauseResume: () -> Unit,
    onDelete: () -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(pack.name, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    when (pack.state) {
                        OfflinePackState.COMPLETE -> R.string.l10n_app_settings_offlinemaps_complete
                        OfflinePackState.PAUSED -> R.string.l10n_app_settings_offlinemaps_paused
                        else -> R.string.l10n_app_settings_offlinemaps_downloading
                    },
                ),
            )
            LinearProgressIndicator(
                progress = { pack.completedFraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pack.state != OfflinePackState.COMPLETE) {
                    TextButton(onClick = onPauseResume) {
                        Text(
                            stringResource(
                                if (pack.state == OfflinePackState.PAUSED) {
                                    R.string.l10n_app_settings_offlinemaps_resume
                                } else {
                                    R.string.l10n_app_settings_offlinemaps_pause
                                },
                            ),
                        )
                    }
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.l10n_app_localizable_common_delete))
                }
            }
        }
    }
}

@Composable
private fun offlineErrorMessage(failure: Throwable): String = when (failure) {
    OfflineMapError.InsufficientDiskSpace ->
        stringResource(R.string.l10n_app_settings_offlinemaps_error_insufficientdiskspace)
    OfflineMapError.NetworkUnavailable ->
        stringResource(R.string.l10n_app_settings_offlinemaps_nonetwork)
    else -> failure.message ?: stringResource(R.string.l10n_app_settings_offlinemaps_unknownregion)
}

private fun MapCamera.toBounds(): GeoBounds {
    val halfLatitude = latitudeSpan / 2.0
    val halfLongitude = longitudeSpan / 2.0
    val south = (center.latitude - halfLatitude).coerceAtLeast(-85.0)
    val north = (center.latitude + halfLatitude).coerceAtMost(85.0)
    val west = normalizeLongitude(center.longitude - halfLongitude)
    val east = normalizeLongitude(center.longitude + halfLongitude)
    return GeoBounds(GeoPoint(south, west), GeoPoint(north, east))
}

private fun normalizeLongitude(value: Double): Double {
    var normalized = value
    while (normalized < -180.0) normalized += 360.0
    while (normalized > 180.0) normalized -= 360.0
    return normalized
}

@Composable
private fun Attribution(context: Context, modifier: Modifier = Modifier) {
    Card(modifier.padding(6.dp)) {
        FlowRow(Modifier.padding(horizontal = 4.dp)) {
            MapLibreOpenFreeMap.attribution.forEach { attribution ->
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(attribution.legalUri))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                ) {
                    Text(attribution.label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private const val PREFERENCES = "shared-map"
private const val CAMERA_KEY = "camera-v1"
private const val FILTER_KEY = "filter-v1"
private const val CLUSTER_KEY = "cluster-v1"
private const val LABEL_KEY = "labels-v1"
