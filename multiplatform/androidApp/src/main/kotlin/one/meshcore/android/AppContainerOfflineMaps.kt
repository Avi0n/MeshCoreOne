package one.meshcore.android

import kotlinx.coroutines.launch
import one.meshcore.shared.map.OfflineMapStorageGate
import one.meshcore.shared.map.offlineMapDownloadErrorResource
import one.meshcore.shared.map.offlineMapPacksToResume
import one.meshcore.shared.map.saveToPrefs
import one.meshcore.shared.map.validatedBounds
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_offline_maps_error_insufficient_disk_space
import org.jetbrains.compose.resources.getString

/** Clears the surfaced failure once shown, mirroring iOS `clearLastPackError`. */
fun AppContainer.clearOfflineMapError() {
    offlineMapErrorState.value = null
}
internal fun AppContainer.reportOfflineMapDownloadError(raw: String) {
    offlineMapErrorReporting.launch {
        offlineMapErrorState.value =
            offlineMapDownloadErrorResource(raw)?.let { getString(it) } ?: raw
    }
}
fun AppContainer.persistOfflineMapPacks() {
    offlineMapPackPersistence.launch {
        offlineMapPackRegistry.saveToPrefs(prefs)
    }
}

/**
 * Start an offline pack download for the region the Settings picker confirmed.
 * Bounds come from the picker (never a silent (0,0) ocean default).
 */
fun AppContainer.startOfflineMapDownload(
    pack: one.meshcore.shared.map.OfflineMapPack,
    bounds: one.meshcore.shared.map.CoordinateBounds,
) {
    appScope.launch {
        val expanded = one.meshcore.shared.map.OfflineMapDownloadEstimate
            .expandBoundsForDownload(bounds)
        if (expanded == null) {
            offlineMapPackRegistry.remove(pack.id)
            persistOfflineMapPacks()
            return@launch
        }
        if (OfflineMapStorageGate.exceedsAvailableSpace(
                estimatedBytes = null,
                availableBytes = offlineMapAvailableBytes(),
            )
        ) {
            offlineMapErrorState.value =
                getString(Res.string.settings_offline_maps_error_insufficient_disk_space)
            offlineMapPackRegistry.remove(pack.id)
            persistOfflineMapPacks()
            return@launch
        }
        offlineMapTileDownloader.startDownload(
            pack = pack,
            bounds = expanded,
            onUpdate = { updated ->
                offlineMapPackRegistry.upsert(updated)
                persistOfflineMapPacks()
            },
            onError = { reportOfflineMapDownloadError(it) },
        )
    }
}

/** Last Map-tab camera for the offline region picker; null seeds the continental default. */
suspend fun AppContainer.loadMapCameraRegionForOfflinePicker(): one.meshcore.shared.map.MapCameraRegion? =
    prefs.getString(one.meshcore.shared.prefs.PersistenceKeys.MAP_CAMERA_REGION)
        ?.let(one.meshcore.shared.map.MapCameraStore::decode)
        ?.takeIf { it.validatedBounds() != null }
fun AppContainer.deleteOfflineMapPack(packId: String) {
    offlineMapTileDownloader.deleteNativePack(packId)
}
fun AppContainer.pauseOfflineMapPack(packId: String) {
    offlineMapTileDownloader.pauseDownload(packId)
    offlineMapPackRegistry.get(packId)?.let { pack ->
        offlineMapPackRegistry.upsert(pack.copy(paused = true))
        persistOfflineMapPacks()
    }
}
fun AppContainer.resumeAllOfflineMapPacks() {
    offlineMapPacksToResume(offlineMapPackRegistry.list()).forEach { pack ->
        resumeOfflineMapPack(pack.id)
    }
}
