package one.meshcore.android

import one.meshcore.shared.connection.persistDeviceCoordinates
import one.meshcore.shared.connection.setNodeName
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogBuffer
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.DebugLogSource
import one.meshcore.shared.settings.DeviceGpsState
import one.meshcore.shared.settings.DeviceLocationPreferences
import one.meshcore.shared.settings.SelfAdvertSender

/** Reads the radio's on-board GPS capability and state (iOS `SettingsService.getDeviceGPSState`). */
suspend fun AppContainer.readDeviceGpsState(): DeviceGpsState {
    val session = connectionManager.services?.session ?: error("No radio connected")
    return session.deviceGpsState()
}

/** Writes the radio's on-board GPS toggle and proves it stuck. */
suspend fun AppContainer.setDeviceGpsEnabled(enabled: Boolean) {
    val session = connectionManager.services?.session ?: error("No radio connected")
    session.setDeviceGpsEnabledVerified(enabled)
}

/** Renames the connected radio and refreshes the BLE-menu header with the name the radio adopted. */
suspend fun AppContainer.setNodeName(name: String) {
    localNodeNameFlow.value = connectionManager.setNodeName(name)
    refreshSettingsSnapshotCache(connected = true)
}

/** Writes a manually picked position to the radio (Settings → Location map picker). */
suspend fun AppContainer.setDeviceCoordinates(latitude: Double, longitude: Double) {
    val session = connectionManager.services?.session
        ?: error("No radio connected")
    session.setManualCoordinatesVerified(latitude, longitude, settingsLogger)
    connectionManager.persistDeviceCoordinates(latitude, longitude)
    refreshSettingsSnapshotCache(connected = true)
}
internal fun AppContainer.buildSelfAdvertSender() = SelfAdvertSender(
    preferences = DeviceLocationPreferences(prefs),
    locationService = { locationService },
    onRefreshFailure = { failure ->
        DebugLogBuffer.record(
            DebugLogEntryDto.of(
                id = Uuid.normalized(java.util.UUID.randomUUID().toString()),
                timestamp = System.currentTimeMillis(),
                level = DebugLogLevel.WARNING,
                source = DebugLogSource("io.pocketmesh.app", "Advert"),
                message = "Failed to update location from GPS: ${failure.message ?: failure}",
            ),
        )
    },
)
