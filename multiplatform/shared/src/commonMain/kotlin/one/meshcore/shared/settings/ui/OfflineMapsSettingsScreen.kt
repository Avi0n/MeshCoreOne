package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.chat.ui.ThreadErrorDialog
import one.meshcore.shared.map.CoordinateBounds
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.MapDisplayPreferencesStore
import one.meshcore.shared.map.OfflineMapDownloadEstimate
import one.meshcore.shared.map.OfflineMapLayer
import one.meshcore.shared.map.OfflineMapPack
import one.meshcore.shared.map.OfflineMapPackRegistry
import one.meshcore.shared.map.displayNameOrUnknown
import one.meshcore.shared.map.displayedOfflineDownloadSpeed
import one.meshcore.shared.map.mapsAppearancePreference
import one.meshcore.shared.map.offlineMapDownloadErrorResource
import one.meshcore.shared.map.offlineMapStorageUsedBytes
import one.meshcore.shared.map.offlinePackHeadline
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_offline_maps_complete
import one.meshcore.shared.resources.settings_offline_maps_delete
import one.meshcore.shared.resources.settings_offline_maps_download_region
import one.meshcore.shared.resources.settings_offline_maps_downloading
import one.meshcore.shared.resources.settings_offline_maps_empty_description
import one.meshcore.shared.resources.settings_offline_maps_empty_title
import one.meshcore.shared.resources.settings_offline_maps_layer_base
import one.meshcore.shared.resources.settings_offline_maps_layer_topo
import one.meshcore.shared.resources.settings_offline_maps_pause
import one.meshcore.shared.resources.settings_offline_maps_paused
import one.meshcore.shared.resources.settings_offline_maps_resume
import one.meshcore.shared.resources.settings_offline_maps_storage
import one.meshcore.shared.resources.settings_offline_maps_storage_footer
import one.meshcore.shared.resources.settings_offline_maps_storage_used
import one.meshcore.shared.resources.settings_offline_maps_unknown_region
import one.meshcore.shared.ui.EMPTY_STATE_ICON_SIZE
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.preferenceHubEdgeFill
import one.meshcore.shared.ui.preferenceHubFormScroll
import org.jetbrains.compose.resources.stringResource

/**
 * Settings → Offline Maps: pack list from [OfflineMapPackRegistry] plus a region picker sheet that
 * chooses bounds, name, and layers before [onStartDownload] (never silent ocean tiles at (0,0)).
 * Storage total, free-space gate on the picker, and pause/resume match iOS Offline Maps settings.
 */
@Composable
fun OfflineMapsSettingsScreen(
    registry: OfflineMapPackRegistry = OfflineMapPackRegistry(),
    onStartDownload: (OfflineMapPack, CoordinateBounds) -> Unit = { _, _ -> },
    onDeletePack: (String) -> Unit = {},
    onPausePack: (String) -> Unit = {},
    onResumePack: (String) -> Unit = {},
    onRegistryChanged: () -> Unit = {},
    nowEpochSeconds: () -> Long = { 0L },
    loadMapCameraRegion: suspend () -> MapCameraRegion? = { null },
    /** Free bytes on the app data volume; null skips the free-space gate (desktop / unknown). */
    availableBytes: () -> Long? = { null },
    /** MapLibre offline db file length (iOS `databaseSize`); 0 when the file is missing. */
    databaseSizeBytes: () -> Long = { 0L },
    /** Whether a live network route exists; the region picker needs one to fetch tiles. */
    isNetworkAvailable: () -> Boolean = { true },
    /** The last download failure, surfaced in an alert as iOS does; null when there is none. */
    downloadError: String? = null,
    /** Clears [downloadError] once the user has seen it. */
    onDownloadErrorShown: () -> Unit = {},
    mapDisplayPreferences: MapDisplayPreferencesStore? = null,
    modifier: Modifier = Modifier,
) {
    val mapsAppearance =
        mapsAppearancePreference(mapDisplayPreferences?.mapColorScheme?.collectAsStateWithLifecycle()?.value)
    val revision by registry.revision.collectAsStateWithLifecycle()
    val packs = remember(revision) { registry.list() }
    var showPicker by remember { mutableStateOf(false) }
    val initialCamera by produceState(
        initialValue = OfflineMapDownloadEstimate.DEFAULT_PICKER_CAMERA,
        loadMapCameraRegion,
    ) {
        value = loadMapCameraRegion() ?: OfflineMapDownloadEstimate.DEFAULT_PICKER_CAMERA
    }

    // A failed download otherwise leaves the row frozen at "Downloading", so the failure has to be said
    // out loud once, as iOS does, and then cleared. Tokens map to L10n; SDK English stays as reported.
    if (downloadError != null) {
        val resource = offlineMapDownloadErrorResource(downloadError)
        val message = if (resource != null) stringResource(resource) else downloadError
        ThreadErrorDialog(message = message, onDismiss = onDownloadErrorShown)
    }

    Column(
        modifier.preferenceHubEdgeFill(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (packs.isEmpty()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                Icon(
                    imageVector = OFFLINE_MAPS_EMPTY_ICON,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(EMPTY_STATE_ICON_SIZE),
                )
                Text(
                    text = stringResource(Res.string.settings_offline_maps_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(Res.string.settings_offline_maps_empty_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Column(
                preferenceHubFormScroll(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PreferenceHubSection(applyHorizontalPadding = false) {
                    packs.forEach { pack ->
                        OfflinePackRow(
                            pack = pack,
                            onDelete = {
                                onDeletePack(pack.id)
                                registry.remove(pack.id)
                                onRegistryChanged()
                            },
                            onPause = {
                                onPausePack(pack.id)
                                val current = registry.get(pack.id) ?: pack
                                if (!current.paused) {
                                    registry.upsert(current.copy(paused = true))
                                }
                                onRegistryChanged()
                            },
                            onResume = {
                                onResumePack(pack.id)
                                val current = registry.get(pack.id) ?: pack
                                if (current.paused) {
                                    registry.upsert(current.copy(paused = false))
                                }
                                onRegistryChanged()
                            },
                        )
                    }
                }
                StorageSection(totalBytes = offlineMapStorageUsedBytes(databaseSizeBytes()))
            }
        }
        Button(
            onClick = { showPicker = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.settings_offline_maps_download_region))
        }
    }

    if (showPicker) {
        OfflineRegionPickerSheet(
            initialCamera = initialCamera,
            nowEpochSeconds = nowEpochSeconds,
            availableBytes = availableBytes(),
            isNetworkAvailable = isNetworkAvailable(),
            colorSchemePreference = mapsAppearance,
            onDismiss = { showPicker = false },
            onConfirm = { newPacks, bounds ->
                newPacks.forEach { pack ->
                    registry.upsert(pack)
                    onStartDownload(pack, bounds)
                }
                onRegistryChanged()
                showPicker = false
            },
        )
    }
}

@Composable
private fun StorageSection(totalBytes: Long) {
    PreferenceHubSection(
        title = stringResource(Res.string.settings_offline_maps_storage),
        applyHorizontalPadding = false,
    ) {
        ListItem(
            headlineContent = {
                Text(stringResource(Res.string.settings_offline_maps_storage_used))
            },
            supportingContent = {
                Text(
                    text = OfflineMapDownloadEstimate.formatApproximateSize(totalBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
    PreferenceHubFooter(
        text = stringResource(Res.string.settings_offline_maps_storage_footer),
        applyHorizontalPadding = false,
    )
}

@Composable
private fun OfflinePackRow(pack: OfflineMapPack, onDelete: () -> Unit, onPause: () -> Unit, onResume: () -> Unit) {
    val statusLabel = when {
        pack.completed -> stringResource(Res.string.settings_offline_maps_complete)
        pack.paused -> stringResource(Res.string.settings_offline_maps_paused)
        else -> stringResource(Res.string.settings_offline_maps_downloading)
    }
    val progressFraction = pack.progress.coerceIn(0.0, 1.0).toFloat()
    val sizeLabel = OfflineMapDownloadEstimate.formatApproximateSize(pack.sizeBytes)
    val speedBytes = displayedOfflineDownloadSpeed(
        completed = pack.completed,
        paused = pack.paused,
        speedBytesPerSec = pack.downloadSpeedBytesPerSec,
    )
    val speedLabel = speedBytes?.let { "${OfflineMapDownloadEstimate.formatApproximateSize(it)}/s" }
    val displayName = pack.displayNameOrUnknown(
        stringResource(Res.string.settings_offline_maps_unknown_region),
    )
    val layerLabel = when (pack.layer) {
        OfflineMapLayer.BASE -> stringResource(Res.string.settings_offline_maps_layer_base)
        OfflineMapLayer.TOPO -> stringResource(Res.string.settings_offline_maps_layer_topo)
    }
    Column(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = {
                Text(offlinePackHeadline(displayName, layerLabel))
            },
            supportingContent = {
                Text(
                    text = if (speedLabel != null) {
                        "$statusLabel, $sizeLabel, $speedLabel"
                    } else {
                        "$statusLabel, $sizeLabel"
                    },
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!pack.completed) {
                        AppIconButton(
                            imageVector = if (pack.paused) AppIcons.PlayArrow else AppIcons.Pause,
                            contentDescription = stringResource(
                                if (pack.paused) {
                                    Res.string.settings_offline_maps_resume
                                } else {
                                    Res.string.settings_offline_maps_pause
                                },
                            ),
                            onClick = { if (pack.paused) onResume() else onPause() },
                        )
                    }
                    AppIconButton(
                        imageVector = AppIcons.Delete,
                        contentDescription = stringResource(Res.string.settings_offline_maps_delete),
                        onClick = onDelete,
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        if (!pack.completed) {
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
            )
        }
    }
}

/** iOS `OfflineMapSettingsView` empty: `map`. */
internal val OFFLINE_MAPS_EMPTY_ICON get() = AppIcons.Map
