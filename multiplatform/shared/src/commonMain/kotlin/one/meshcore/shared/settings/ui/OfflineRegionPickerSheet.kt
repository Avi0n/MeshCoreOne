package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.meshcore.shared.map.CoordinateBounds
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.MapStyleSelection
import one.meshcore.shared.map.OfflineMapDownloadEstimate
import one.meshcore.shared.map.OfflineMapPack
import one.meshcore.shared.map.OfflineMapStorageGate
import one.meshcore.shared.map.buildOfflinePacks
import one.meshcore.shared.map.offlineLayers
import one.meshcore.shared.map.ui.LocalMapColorSchemePreference
import one.meshcore.shared.map.ui.LocalMapOffline
import one.meshcore.shared.map.ui.MapLibreHost
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_offline_maps_download
import one.meshcore.shared.resources.settings_offline_maps_download_hint
import one.meshcore.shared.resources.settings_offline_maps_estimated_size
import one.meshcore.shared.resources.settings_offline_maps_exceeds_storage
import one.meshcore.shared.resources.settings_offline_maps_large_tile_warning
import one.meshcore.shared.resources.settings_offline_maps_layer_topo
import one.meshcore.shared.resources.settings_offline_maps_layers
import one.meshcore.shared.resources.settings_offline_maps_no_network
import one.meshcore.shared.resources.settings_offline_maps_pick_region
import one.meshcore.shared.resources.settings_offline_maps_region_name
import one.meshcore.shared.theme.AppColorSchemePreference
import one.meshcore.shared.ui.AppOutlinedTextField
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

/**
 * Settings → Offline Maps region picker: pan/zoom map, name the pack, optional topo layer, size
 * estimate, free-space gate, then confirm download. Mirrors iOS `RegionPickerSheet`.
 *
 * @param initialCamera last Map-tab camera, or [OfflineMapDownloadEstimate.DEFAULT_PICKER_CAMERA]
 * @param availableBytes free bytes on the data volume; null skips the free-space gate
 * @param isNetworkAvailable whether a live network route exists; download needs one to fetch tiles
 * @param colorSchemePreference Settings → Maps appearance, matching iOS `RegionPickerSheet`
 * @param onConfirm one or two packs (base, optional topo) plus shared download bounds
 * @param onDismiss cancel without starting a download
 */
@Composable
fun OfflineRegionPickerSheet(
    initialCamera: MapCameraRegion,
    onConfirm: (packs: List<OfflineMapPack>, bounds: CoordinateBounds) -> Unit,
    onDismiss: () -> Unit,
    nowEpochSeconds: () -> Long,
    availableBytes: Long? = null,
    isNetworkAvailable: Boolean = !LocalMapOffline.current,
    colorSchemePreference: AppColorSchemePreference = LocalMapColorSchemePreference.current,
    modifier: Modifier = Modifier,
) {
    var regionName by remember { mutableStateOf("") }
    var includeTopo by remember { mutableStateOf(false) }
    var camera by remember(initialCamera) { mutableStateOf(initialCamera) }

    val selection = remember(camera) {
        OfflineMapDownloadEstimate.selectionBounds(
            OfflineMapDownloadEstimate.expandForDownload(camera),
        )
    }
    val layers = remember(includeTopo) { offlineLayers(includeTopo) }
    val estimatedBytes = remember(selection, layers) {
        selection?.let { OfflineMapDownloadEstimate.estimatedDownloadSize(it, layers) }
    }
    val exceedsSpace = OfflineMapStorageGate.exceedsAvailableSpace(estimatedBytes, availableBytes)
    val canDownload = OfflineMapStorageGate.canDownload(
        hasRegionName = regionName.trim().isNotEmpty(),
        hasSelection = selection != null,
        exceedsAvailableSpace = exceedsSpace,
        isNetworkAvailable = isNetworkAvailable,
    )

    FullScreenOverlay(
        onDismiss = onDismiss,
        title = stringResource(Res.string.settings_offline_maps_pick_region),
        modifier = modifier,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                MapLibreHost(
                    isOffline = !isNetworkAvailable,
                    points = emptyList(),
                    modifier = Modifier.fillMaxSize(),
                    style = MapStyleSelection.STANDARD,
                    initialCamera = initialCamera,
                    colorSchemePreference = colorSchemePreference,
                    showLabels = false,
                    northLocked = true,
                    showsUserLocation = true,
                    onCameraIdle = { camera = it },
                )
            }
            AppOutlinedTextField(
                value = regionName,
                onValueChange = { regionName = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(Res.string.settings_offline_maps_region_name)) },
            )
            PreferenceHubSection(
                title = stringResource(Res.string.settings_offline_maps_layers),
                applyHorizontalPadding = false,
            ) {
                SettingsToggleRow(
                    label = stringResource(Res.string.settings_offline_maps_layer_topo),
                    checked = includeTopo,
                    onCheckedChange = { includeTopo = it },
                )
            }
            EstimateFooter(
                estimatedBytes = estimatedBytes,
                exceedsAvailableSpace = exceedsSpace,
                isNetworkAvailable = isNetworkAvailable,
            )
            Button(
                onClick = {
                    val bounds = selection ?: return@Button
                    val downloadBounds =
                        OfflineMapDownloadEstimate.expandBoundsForDownload(bounds) ?: bounds
                    val packs = buildOfflinePacks(
                        name = regionName,
                        layers = layers,
                        createdAtEpochSeconds = nowEpochSeconds(),
                    )
                    onConfirm(packs, downloadBounds)
                },
                enabled = canDownload,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.settings_offline_maps_download))
            }
        }
    }
}

@Composable
private fun EstimateFooter(estimatedBytes: Long?, exceedsAvailableSpace: Boolean, isNetworkAvailable: Boolean) {
    if (!isNetworkAvailable) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                imageVector = AppIcons.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(Res.string.settings_offline_maps_no_network),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        return
    }
    if (estimatedBytes == null) {
        Text(
            text = stringResource(Res.string.settings_offline_maps_download_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val sizeLabel = OfflineMapDownloadEstimate.formatApproximateSize(estimatedBytes)
    val isLarge = estimatedBytes > OfflineMapDownloadEstimate.LARGE_DOWNLOAD_THRESHOLD_BYTES
    val estimateColor = when {
        exceedsAvailableSpace -> MaterialTheme.colorScheme.error
        isLarge -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = formattedStringResource(Res.string.settings_offline_maps_estimated_size, sizeLabel),
        style = MaterialTheme.typography.bodySmall,
        color = estimateColor,
    )
    when {
        exceedsAvailableSpace -> {
            Text(
                text = stringResource(Res.string.settings_offline_maps_exceeds_storage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        isLarge -> {
            Text(
                text = stringResource(Res.string.settings_offline_maps_large_tile_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        else -> {
            Text(
                text = stringResource(Res.string.settings_offline_maps_download_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
