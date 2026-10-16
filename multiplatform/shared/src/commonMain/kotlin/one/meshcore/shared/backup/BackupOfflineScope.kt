package one.meshcore.shared.backup

import one.meshcore.shared.connection.LastConnectionStore
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.prefs.runPreferences

/**
 * A host that has never connected has no last-connected radio, so imported rows would sit unseen.
 * Adopt the imported device with the latest [one.meshcore.shared.persistence.DeviceEntity.lastConnected]
 * as the offline partition. [LastConnectionStore.persistOfflineScopeIfAbsent] writes only the radio
 * id and name, so activate cannot try to reconnect a transport this host cannot open.
 */
suspend fun adoptImportedRadioForOfflineScope(
    database: MeshDatabase,
    lastConnectionStore: LastConnectionStore,
    warning: (String) -> Unit,
) {
    val adopted = database.deviceDao().listAll().maxByOrNull { it.lastConnected } ?: return
    // DataStore can throw; iOS UserDefaults does not. Keep the imported rows visible.
    runPreferences(warning, OFFLINE_SCOPE_ADOPT_FAILED, fallback = {}) {
        lastConnectionStore.persistOfflineScopeIfAbsent(adopted.radioID, adopted.nodeName)
    }
}

private const val OFFLINE_SCOPE_ADOPT_FAILED = "Imported radio adopt failed"
