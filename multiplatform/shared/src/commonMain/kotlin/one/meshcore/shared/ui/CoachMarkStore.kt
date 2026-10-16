package one.meshcore.shared.ui

import one.meshcore.shared.prefs.PreferencesStore

/**
 * Persisted one-time coach marks / tips (TipKit equivalent). Keys match iOS tip identifiers where
 * known so a restored backup's userDefaults can align later.
 */
class CoachMarkStore(private val prefs: PreferencesStore) {
    suspend fun hasSeen(id: String): Boolean = prefs.getBoolean(key(id)) == true

    suspend fun markSeen(id: String) {
        prefs.putBoolean(key(id), true)
    }

    suspend fun reset(id: String) {
        prefs.putBoolean(key(id), false)
    }

    private fun key(id: String): String = "$PREFIX$id"

    companion object {
        private const val PREFIX = "coach_mark_seen_"

        /** Device menu tip on chats/nodes/map tabs. */
        const val DEVICE_MENU = "device_menu"

        /** Live status pill tip. */
        const val LIVE_STATUS = "live_status"

        /** Repeater drag hint on map. */
        const val REPEATER_DRAG = "repeater_drag"
    }
}
