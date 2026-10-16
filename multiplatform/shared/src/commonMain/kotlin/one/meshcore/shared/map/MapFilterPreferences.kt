package one.meshcore.shared.map

import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferencesStore

/**
 * Repair stored map-filter JSON: sanitize present rows, migrate a legacy discovered toggle, replace
 * corrupt rows. A pure seed (empty stored JSON, no legacy key) does not write, so a later restore can
 * still land `showDiscoveredNodesOnMap`. Mirrors iOS `MapFilterPreferences.ensureMigrated`.
 */
object MapFilterPreferences {
    data class Outcome(val state: MapFilterState, val didWrite: Boolean)

    fun ensureMigrated(raw: String, host: MapFilterHost, storedRaw: String?, legacyShowDiscovered: Boolean?): Outcome {
        val decoded = MapFilterState.fromStorageString(raw)
        if (decoded != null) {
            val sanitized = decoded.sanitized(host)
            return if (sanitized != decoded) Outcome(sanitized, didWrite = true) else Outcome(decoded, didWrite = false)
        }
        return resolveMigrating(host, storedRaw, legacyShowDiscovered)
    }

    fun resolveMigrating(host: MapFilterHost, storedRaw: String?, legacyShowDiscovered: Boolean?): Outcome =
        if (storedRaw != null) {
            val decoded = MapFilterState.fromStorageString(storedRaw)
            if (decoded == null) {
                Outcome(MapFilterState.seed(host).sanitized(host), didWrite = true)
            } else {
                val sanitized = decoded.sanitized(host)
                Outcome(sanitized, didWrite = sanitized != decoded)
            }
        } else {
            val useLegacy = host == MapFilterHost.MAIN_MAP && legacyShowDiscovered != null
            val seed = if (useLegacy) {
                MapFilterState.seed(host).setShowDiscovered(legacyShowDiscovered == true)
            } else {
                MapFilterState.seed(host)
            }
            Outcome(seed.sanitized(host), didWrite = useLegacy)
        }

    /** Reads, migrates, and optionally writes the main-map filter. */
    suspend fun applyMainMap(preferences: PreferencesStore): MapFilterState {
        val storedRaw = preferences.getString(PersistenceKeys.MAP_FILTER_MAIN_MAP)
        val outcome = ensureMigrated(
            raw = storedRaw.orEmpty(),
            host = MapFilterHost.MAIN_MAP,
            storedRaw = storedRaw,
            legacyShowDiscovered = preferences.getBoolean(PersistenceKeys.SHOW_DISCOVERED_NODES_ON_MAP),
        )
        if (outcome.didWrite) {
            preferences.putString(PersistenceKeys.MAP_FILTER_MAIN_MAP, outcome.state.toStorageString())
        }
        return outcome.state
    }
}
