package one.meshcore.shared.connection

import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferencesStore

/**
 * Persists the "does the user want to be connected" axis — a single boolean, exactly as Swift persists
 * only the `.userDisconnected` bit of `ConnectionIntent`. [WantsConnection]/[None] clear the key, so a
 * fresh launch restores to [ConnectionIntent.UserDisconnected] only when the user last disconnected on
 * purpose. That bit is the sole gate that suppresses foreground auto-reconnect and the disconnected pill.
 *
 * Kept separate from [LastConnectionStore]: last-device state and connection intent are different axes.
 */
class ConnectionIntentStore(private val prefs: PreferencesStore) {
    /** Restores intent from the persisted bit: [ConnectionIntent.UserDisconnected] if set, else `None`. */
    suspend fun restore(): ConnectionIntent =
        ConnectionIntent.restored(prefs.getBoolean(PersistenceKeys.USER_EXPLICITLY_DISCONNECTED) ?: false)

    /**
     * Persists [intent]'s disconnect bit: `true` only for [ConnectionIntent.UserDisconnected], so
     * persisting any other state clears it.
     */
    suspend fun persist(intent: ConnectionIntent) {
        prefs.putBoolean(PersistenceKeys.USER_EXPLICITLY_DISCONNECTED, intent.persistedDisconnectFlag)
    }
}
