package one.meshcore.shared.map

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory and prefs-backed record updated by MapLibre's offline downloader. Mirrors the iOS
 * `OfflinePack` and `OfflinePackMetadata` fields used by Offline Maps settings.
 */
data class OfflineMapPack(
    val id: String,
    val name: String,
    val layer: OfflineMapLayer,
    val createdAtEpochSeconds: Long,
    val completed: Boolean = false,
    val progress: Double = 0.0,
    val sizeBytes: Long = 0L,

    /**
     * Live bytes/s while MapLibre is downloading, or null when paused/complete/unknown.
     * Ephemeral — not written to prefs.
     */
    val downloadSpeedBytesPerSec: Long? = null,

    /** User paused an in-flight MapLibre download (iOS isPaused / suspend). */
    val paused: Boolean = false,

    /**
     * The region the pack was downloaded for, the iOS `OfflinePack.bounds`. Null on a pack recorded
     * before the field existed; such a pack backs no viewport, so its layer reads as uncovered until
     * it is downloaded again.
     */
    val bounds: CoordinateBounds? = null,
)

/**
 * iOS `OfflinePack.name` falls back to Unknown Region when MapLibre metadata is missing.
 */
fun OfflineMapPack.displayNameOrUnknown(unknownRegion: String): String = name.trim().ifEmpty { unknownRegion }

/**
 * Pure registry of offline pack metadata for Settings UI and style gating.
 * Android OfflineManager download/delete wires into [upsert]/[remove].
 */
class OfflineMapPackRegistry {
    private val packs = MutableStateFlow<Map<String, OfflineMapPack>>(emptyMap())
    private val _revision = MutableStateFlow(0)

    /** Bumps on every mutation so Compose can collect and re-list packs. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun list(): List<OfflineMapPack> = packs.value.values.sortedByDescending { it.createdAtEpochSeconds }

    fun upsert(pack: OfflineMapPack) {
        packs.update { it + (pack.id to pack) }
        bump()
    }

    fun remove(id: String) {
        packs.update { it - id }
        bump()
    }

    fun get(id: String): OfflineMapPack? = packs.value[id]

    /**
     * Whether a finished pack backs [layer] over [viewport]. A null viewport asks the layer-wide
     * question, matching the iOS fallback when no camera bounds are known yet; otherwise the pack must
     * cover what the map is showing, so a pack downloaded for another region cannot leave a style
     * selectable over a blank map. Mirrors iOS `OfflineMapService.hasCompletedPack`.
     */
    fun hasCompletedPack(layer: OfflineMapLayer, viewport: CoordinateBounds? = null): Boolean =
        packs.value.values.any { pack ->
            pack.layer == layer && pack.completed && (viewport == null || pack.bounds?.overlaps(viewport) == true)
        }

    fun clear() {
        packs.value = emptyMap()
        bump()
    }

    internal fun loadSnapshot(): Map<String, OfflineMapPack> = packs.value

    /** Applies disk state while retaining every pack changed after [beforeLoad] was captured. */
    internal fun applyLoaded(loaded: List<OfflineMapPack>, beforeLoad: Map<String, OfflineMapPack>) {
        packs.update { current ->
            val merged = loaded.associateByTo(linkedMapOf(), OfflineMapPack::id)
            (beforeLoad.keys + current.keys).forEach { id ->
                val currentPack = current[id]
                if (currentPack != beforeLoad[id]) {
                    if (currentPack == null) merged.remove(id) else merged[id] = currentPack
                }
            }
            merged
        }
        bump()
    }

    private fun bump() {
        _revision.update { it + 1 }
    }

    companion object {
        fun newPackId(name: String, createdAtEpochSeconds: Long): String = "${name.trim()}_$createdAtEpochSeconds"
    }
}

/**
 * Packs that a foreground resume should restart: incomplete and not user-paused. Mirrors iOS
 * `OfflineMapService.resumeAllPacks` (inactive native packs that the user did not suspend).
 */
fun offlineMapPacksToResume(packs: List<OfflineMapPack>): List<OfflineMapPack> =
    packs.filter { !it.completed && !it.paused }
