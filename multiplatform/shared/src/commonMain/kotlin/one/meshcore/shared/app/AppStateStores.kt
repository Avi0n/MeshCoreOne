package one.meshcore.shared.app

import one.meshcore.shared.connection.ConnectionIntentStore
import one.meshcore.shared.connection.LastConnectionStore
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.prefs.PreferencesStore

/** Last-connection, intent, preference, and device rows the connection owner reads. */
data class AppStateStores(
    val lastConnectionStore: LastConnectionStore,
    val intentStore: ConnectionIntentStore,
    val prefs: PreferencesStore,
    val database: MeshDatabase,
)
