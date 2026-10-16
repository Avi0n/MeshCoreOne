package one.meshcore.shared.map

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import one.meshcore.shared.prefs.PreferencesStore

/** Prefs key for offline pack metadata list (tile blobs live in MapLibre storage). */
const val OFFLINE_MAP_PACKS_PREFS_KEY = "offlineMapPacksJson"

@Serializable
private data class OfflineMapPackWire(
    val id: String,
    val name: String,
    val layer: String,
    val createdAtEpochSeconds: Long,
    val completed: Boolean = false,
    val progress: Double = 0.0,
    val sizeBytes: Long = 0L,
    val paused: Boolean = false,
    // Absent on a record written before packs carried their region; all four must be present to rebuild it.
    val swLatitude: Double? = null,
    val swLongitude: Double? = null,
    val neLatitude: Double? = null,
    val neLongitude: Double? = null,
)

private fun OfflineMapPackWire.bounds(): CoordinateBounds? {
    val sw = cornerOrNull(swLatitude, swLongitude)
    val ne = cornerOrNull(neLatitude, neLongitude)
    return if (sw == null || ne == null) null else CoordinateBounds(sw = sw, ne = ne)
}

private fun cornerOrNull(latitude: Double?, longitude: Double?): LatLng? =
    if (latitude == null || longitude == null) null else LatLng(latitude, longitude)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Loads pack metadata into [registry] from [prefs]. Invalid JSON is ignored (empty registry).
 */
suspend fun OfflineMapPackRegistry.loadFromPrefs(prefs: PreferencesStore) {
    val beforeLoad = loadSnapshot()
    val raw = prefs.getString(OFFLINE_MAP_PACKS_PREFS_KEY) ?: return
    val wires = runCatching {
        json.decodeFromString(ListSerializer(OfflineMapPackWire.serializer()), raw)
    }.getOrNull() ?: return
    val loaded = wires.map { wire ->
        val layer = OfflineMapLayer.entries.firstOrNull { it.rawValue == wire.layer } ?: OfflineMapLayer.BASE
        OfflineMapPack(
            id = wire.id,
            name = wire.name,
            layer = layer,
            createdAtEpochSeconds = wire.createdAtEpochSeconds,
            completed = wire.completed,
            progress = wire.progress,
            sizeBytes = wire.sizeBytes,
            paused = wire.paused,
            bounds = wire.bounds(),
        )
    }
    applyLoaded(loaded, beforeLoad)
}

/** Persists the registry list to [prefs]. */
suspend fun OfflineMapPackRegistry.saveToPrefs(prefs: PreferencesStore) {
    val wires =
        list().map { pack ->
            OfflineMapPackWire(
                id = pack.id,
                name = pack.name,
                layer = pack.layer.rawValue,
                createdAtEpochSeconds = pack.createdAtEpochSeconds,
                completed = pack.completed,
                progress = pack.progress,
                sizeBytes = pack.sizeBytes,
                paused = pack.paused,
                swLatitude = pack.bounds?.sw?.latitude,
                swLongitude = pack.bounds?.sw?.longitude,
                neLatitude = pack.bounds?.ne?.latitude,
                neLongitude = pack.bounds?.ne?.longitude,
            )
        }
    try {
        prefs.putString(
            OFFLINE_MAP_PACKS_PREFS_KEY,
            json.encodeToString(ListSerializer(OfflineMapPackWire.serializer()), wires),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // DataStore can throw. Keep the in-memory pack list.
    }
}
