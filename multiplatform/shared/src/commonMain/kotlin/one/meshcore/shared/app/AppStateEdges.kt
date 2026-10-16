package one.meshcore.shared.app

import one.meshcore.shared.notification.BatteryNotifier
import one.meshcore.shared.settings.PersistentLogger

/** Battery alerts, connection logging, and the adapter-power gate. A null logger builds one on the owner scope. */
data class AppStateEdges(
    val batteryNotifier: BatteryNotifier = BatteryNotifier.NoOp,
    val logger: PersistentLogger? = null,
    val bluetoothAdapterEnabled: () -> Boolean = { true },
)
