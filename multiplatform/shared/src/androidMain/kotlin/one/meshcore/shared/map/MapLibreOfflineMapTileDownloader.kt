package one.meshcore.shared.map

import android.content.Context
import android.os.SystemClock
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * MapLibre [OfflineManager] download/delete for offline map packs. Metadata bytes store the pack id so
 * progress callbacks update the correct [OfflineMapPackRegistry] row. Pause uses STATE_INACTIVE;
 * resume re-activates (and re-lists by metadata after process death).
 */
class MapLibreOfflineMapTileDownloader(context: Context) : OfflineMapTileDownloader {
    private val appContext = context.applicationContext
    private val regionsByPackId = ConcurrentHashMap<String, OfflineRegion>()
    private val speedSnapshots = ConcurrentHashMap<String, OfflineDownloadSpeedSnapshot>()

    init {
        MapLibre.getInstance(appContext)
    }

    override fun startDownload(
        pack: OfflineMapPack,
        bounds: CoordinateBounds,
        onUpdate: (OfflineMapPack) -> Unit,
        onError: (String) -> Unit,
    ) {
        // The region travels on the pack from here on, so the style gate can ask whether a finished pack
        // covers the viewport rather than merely whether one exists.
        val tracked = pack.copy(bounds = bounds)
        val styleUrl = pack.layer.styleUrlForOffline()
        val maxZoom = pack.layer.maxDownloadZoom()
        val latLngBounds = LatLngBounds.from(
            bounds.ne.latitude,
            bounds.ne.longitude,
            bounds.sw.latitude,
            bounds.sw.longitude,
        )
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl,
            latLngBounds,
            OfflineMapTileDownloader.DEFAULT_MIN_ZOOM,
            maxZoom,
            appContext.resources.displayMetrics.density,
        )
        val metadata = pack.id.toByteArray(StandardCharsets.UTF_8)
        OfflineManager.getInstance(appContext).createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    regionsByPackId[pack.id] = offlineRegion
                    attachObserver(offlineRegion, tracked, onUpdate, onError)
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    onError(error.ifBlank { OFFLINE_MAP_CREATE_FAILED_TOKEN })
                }
            },
        )
    }

    override fun pauseDownload(packId: String) {
        val region = regionsByPackId[packId]
        if (region != null) {
            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            return
        }
        findRegionByPackId(packId) { found ->
            regionsByPackId[packId] = found
            found.setDownloadState(OfflineRegion.STATE_INACTIVE)
        }
    }

    override fun resumeDownload(pack: OfflineMapPack, onUpdate: (OfflineMapPack) -> Unit, onError: (String) -> Unit) {
        val existing = regionsByPackId[pack.id]
        if (existing != null) {
            attachObserver(existing, pack, onUpdate, onError)
            existing.setDownloadState(OfflineRegion.STATE_ACTIVE)
            return
        }
        findRegionByPackId(pack.id) { found ->
            regionsByPackId[pack.id] = found
            attachObserver(found, pack, onUpdate, onError)
            found.setDownloadState(OfflineRegion.STATE_ACTIVE)
        }
    }

    override fun deleteNativePack(packId: String) {
        val region = regionsByPackId.remove(packId)
        if (region != null) {
            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            region.delete(
                object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() = Unit
                    override fun onError(error: String) = Unit
                },
            )
            return
        }
        findRegionByPackId(packId) { candidate ->
            candidate.delete(
                object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() = Unit
                    override fun onError(error: String) = Unit
                },
            )
        }
    }

    private fun attachObserver(
        offlineRegion: OfflineRegion,
        pack: OfflineMapPack,
        onUpdate: (OfflineMapPack) -> Unit,
        onError: (String) -> Unit,
    ) {
        offlineRegion.setObserver(
            object : OfflineRegion.OfflineRegionObserver {
                override fun onStatusChanged(status: OfflineRegionStatus) {
                    val required = status.requiredResourceCount.coerceAtLeast(1)
                    val progress = status.completedResourceCount.toDouble() / required.toDouble()
                    val completed = status.isComplete
                    val snapshot = nextOfflineDownloadSpeed(
                        previous = speedSnapshots[pack.id],
                        currentBytes = status.completedResourceSize,
                        currentElapsedMs = SystemClock.elapsedRealtime(),
                    )
                    if (completed) {
                        speedSnapshots.remove(pack.id)
                    } else {
                        speedSnapshots[pack.id] = snapshot
                    }
                    onUpdate(
                        pack.copy(
                            progress = progress.coerceIn(0.0, 1.0),
                            completed = completed,
                            sizeBytes = status.completedResourceSize,
                            downloadSpeedBytesPerSec = if (completed) null else snapshot.speedBytesPerSec,
                            paused = false,
                        ),
                    )
                    if (completed) {
                        offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    }
                }

                override fun onError(error: OfflineRegionError) {
                    onError(error.message.ifBlank { OFFLINE_MAP_DOWNLOAD_FAILED_TOKEN })
                }

                override fun mapboxTileCountLimitExceeded(limit: Long) {
                    onError(OFFLINE_MAP_TILE_LIMIT_TOKEN)
                }
            },
        )
    }

    private fun findRegionByPackId(packId: String, onFound: (OfflineRegion) -> Unit) {
        OfflineManager.getInstance(appContext).listOfflineRegions(
            object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    offlineRegions.orEmpty().forEach { candidate ->
                        val id = candidate.metadata?.toString(StandardCharsets.UTF_8)
                        if (id == packId) {
                            onFound(candidate)
                        }
                    }
                }

                override fun onError(error: String) = Unit
            },
        )
    }
}
