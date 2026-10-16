package one.meshcore.shared.settings

import one.meshcore.shared.model.Uuid
import one.meshcore.shared.prefs.PreferencesStore

/**
 * Last-applied catalog id, keyed by [radioID] so a shared RF tuple keeps the name the operator picked
 * across reconnect. Repeat Mode must keep a stale id so restore can still paint the community name.
 */
class AppliedRadioPresetStore(private val store: PreferencesStore) {
    suspend fun get(radioId: Uuid): String? = store.getString(key(radioId))

    suspend fun set(radioId: Uuid, presetId: String) {
        store.putString(key(radioId), presetId)
    }

    suspend fun clear(radioId: Uuid) {
        store.remove(key(radioId))
    }

    private fun key(radioId: Uuid) = "$KEY_PREFIX${radioId.value}$KEY_SUFFIX"

    companion object {
        private const val KEY_PREFIX = "device."
        private const val KEY_SUFFIX = ".appliedRadioPresetID"
    }
}
