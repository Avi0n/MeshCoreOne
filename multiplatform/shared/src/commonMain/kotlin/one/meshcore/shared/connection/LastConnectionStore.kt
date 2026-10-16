package one.meshcore.shared.connection

import one.meshcore.shared.model.Uuid
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferencesStore

/**
 * The "which radio did we last connect" axis, ported from Swift `LastConnectionStore`. It records the
 * stable `Device` row id, the data-partition `radioID`, and the display name of the last successful
 * connection, plus the most recent disconnect diagnostic. It is deliberately separate from
 * [ConnectionIntentStore] (does the user *want* to be connected) and from the per-`Device`
 * `connectionMethods` (how to reach it) — the three must never collapse.
 *
 * [deviceId] is the stable `Device` row id, not a transport handle: it survives re-pair and is the key
 * `activate()` uses to look up the device row and its reachability descriptors.
 */
class LastConnectionStore(private val prefs: PreferencesStore) : LastConnectionBond by LastConnectionBondStore(prefs) {
    /** The last connected device's stable row id, for auto-reconnect and offline scoping. */
    suspend fun deviceId(): Uuid? = prefs.getString(PersistenceKeys.LAST_CONNECTED_DEVICE_ID)?.let(Uuid::normalized)

    /** The last connected radio id, for scoping offline data while disconnected. */
    suspend fun radioId(): Uuid? = prefs.getString(PersistenceKeys.LAST_CONNECTED_RADIO_ID)?.let(Uuid::normalized)

    /** The last connected device name, for offline display. */
    suspend fun deviceName(): String? = prefs.getString(PersistenceKeys.LAST_CONNECTED_DEVICE_NAME)

    /** The last connected radio's transport handle (Android-new), for BLE cross-launch reconnect. */
    suspend fun handle(): String? = prefs.getString(PersistenceKeys.LAST_CONNECTED_DEVICE_HANDLE)

    /** Records a successful connection for future restoration. Persisted on every successful connect. */
    suspend fun persist(deviceId: Uuid, radioId: Uuid, deviceName: String, handle: String) {
        prefs.putString(PersistenceKeys.LAST_CONNECTED_DEVICE_ID, deviceId.value)
        prefs.putString(PersistenceKeys.LAST_CONNECTED_RADIO_ID, radioId.value)
        prefs.putString(PersistenceKeys.LAST_CONNECTED_DEVICE_NAME, deviceName)
        prefs.putString(PersistenceKeys.LAST_CONNECTED_DEVICE_HANDLE, handle)
    }

    /**
     * Records only the offline-scope radio when a backup lands on a host that has never connected.
     * Does not write [deviceId] or [handle], so [one.meshcore.shared.app.AppStateViewModel.activate]
     * cannot try to reconnect a transport this host cannot open.
     */
    suspend fun persistOfflineScopeIfAbsent(radioId: Uuid, deviceName: String) {
        if (radioId() != null) return
        prefs.putString(PersistenceKeys.LAST_CONNECTED_RADIO_ID, radioId.value)
        if (deviceName.isNotBlank()) {
            prefs.putString(PersistenceKeys.LAST_CONNECTED_DEVICE_NAME, deviceName)
        }
    }

    /** Clears the persisted connection record. */
    suspend fun clear() {
        prefs.remove(PersistenceKeys.LAST_CONNECTED_DEVICE_ID)
        prefs.remove(PersistenceKeys.LAST_CONNECTED_RADIO_ID)
        prefs.remove(PersistenceKeys.LAST_CONNECTED_DEVICE_NAME)
        prefs.remove(PersistenceKeys.LAST_CONNECTED_DEVICE_HANDLE)
    }

    /** The most recent disconnect diagnostic summary, persisted across launches. */
    suspend fun disconnectDiagnostic(): String? = prefs.getString(PersistenceKeys.LAST_DISCONNECT_DIAGNOSTIC)

    /** Persists a disconnect diagnostic prefixed with an ISO8601-style timestamp. */
    suspend fun persistDisconnectDiagnostic(summary: String, timestamp: String) {
        prefs.putString(PersistenceKeys.LAST_DISCONNECT_DIAGNOSTIC, "$timestamp $summary")
    }
}
