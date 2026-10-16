package one.meshcore.android

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import one.meshcore.shared.connection.ConnectionState
import one.meshcore.shared.connection.DevicePlatform
import one.meshcore.shared.connection.TransportType
import one.meshcore.shared.connection.deviceOtherParamsSnapshot
import one.meshcore.shared.connection.recommendedWritePacingMillis
import one.meshcore.shared.connection.resolveDevicePlatform
import one.meshcore.shared.persistence.runRoom

internal fun AppContainer.bleWritePacingMillis(deviceId: String): Long =
    bleWritePacingByHandle[deviceId] ?: DevicePlatform.UNKNOWN.recommendedWritePacingMillis

/** Records the connected radio's chip family against its handle, for that handle's next link. */
internal suspend fun AppContainer.refreshBleWritePacing() {
    val handle = lastConnectionStore.handle() ?: return
    val deviceRowId = lastConnectionStore.deviceId() ?: return
    val model = runRoom(connectionLogger::warning, RADIO_SESSION_ROOM_FAILED, fallback = { null }) {
        database.deviceDao().getById(deviceRowId)?.manufacturerName
    } ?: return
    bleWritePacingByHandle[handle] =
        resolveDevicePlatform(model, TransportType.BLUETOOTH).recommendedWritePacingMillis
}
internal fun AppContainer.startRadioSessionObservers() {
    // Seed the pacing a stored radio needs before its first reconnect of this launch.
    appScope.launch { refreshBleWritePacing() }
    // Contact public keys for RX-log DM decrypt land after GET_CONTACTS (Ready). The radio
    // private key is exported in ServiceContainer.start() before sync, matching iOS.
    appScope.launch {
        connectionManager.state.collectLatest { state ->
            val services = connectionManager.services
            val session = services?.session
            if (session != null) {
                storedChannelDecryptJob?.cancelAndJoin()
                storedChannelDecryptJob = null
            }
            when {
                session == null -> {
                    clearRxLogDecryptMaterial()
                    clearConnectedFloodScopeInputs()
                    connectedPathHashMode.set(null)
                    refreshSettingsSnapshotCache(connected = false)
                }
                state is ConnectionState.Connected -> {
                    // Flood inputs load here so inherit push at Connected sees Device.defaultFloodScopeName
                    // before Ready drains parked channel sends.
                    runRoom(
                        connectionLogger::warning,
                        RADIO_SESSION_ROOM_FAILED,
                        fallback = {},
                    ) {
                        refreshConnectedFloodScopeInputs()
                    }
                }
                state is ConnectionState.Ready -> {
                    runStartupStepsIndependently(
                        connectionLogger::warning,
                        RADIO_SESSION_ROOM_FAILED,
                        { refreshBleWritePacing() },
                        { refreshRxLogContactDecryptKeys() },
                        { refreshConnectedFloodScopeInputs() },
                        {
                            connectedPathHashMode.set(connectionManager.deviceOtherParamsSnapshot()?.pathHashMode)
                        },
                        { refreshSettingsSnapshotCache(connected = true) },
                        {
                            // Ready is the Kotlin sync-complete point (iOS connectionManager.onDeviceSynced);
                            // the scheduler's own threshold gate and 3-hour cooldown throttle repeat Readys.
                            staleNodeCleanupScheduler.performCleanup()
                            staleCleanupSettings.setLastRunSeconds(staleCleanupPrefs.lastRunSeconds())
                        },
                        {
                            // Seed the badge from persisted unread rows once the partition is resolved.
                            notificationCoordinator.updateBadge()
                        },
                    )
                }
            }
        }
    }
}

private const val RADIO_SESSION_ROOM_FAILED = "Radio session Room read failed"
