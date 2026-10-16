package one.meshcore.shared.settings

import kotlinx.serialization.json.Json
import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferencesStore

/**
 * Reads and writes the persisted onboarding/settings region selection (the JSON stored under
 * [PersistenceKeys.REGION_SELECTION]). Both directions go through the one encoder here so the stored
 * blob stays a single wire format no matter which caller wrote it.
 */
object RegionSelectionStore {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(prefs: PreferencesStore): RegionSelection? {
        val raw = prefs.getString(PersistenceKeys.REGION_SELECTION) ?: return null
        return runCatching { json.decodeFromString(RegionSelection.serializer(), raw) }.getOrNull()
    }

    suspend fun save(prefs: PreferencesStore, selection: RegionSelection) {
        prefs.putString(PersistenceKeys.REGION_SELECTION, json.encodeToString(RegionSelection.serializer(), selection))
    }
}
