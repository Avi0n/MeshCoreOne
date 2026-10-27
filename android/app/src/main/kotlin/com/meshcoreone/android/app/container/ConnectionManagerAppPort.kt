// AndroidOnly: WP-303 App-state connection port and runtime sync state over the process ConnectionManager.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.state.AppConnectionPort
import com.meshcoreone.android.core.connectivity.device.ConnectedDeviceAccess
import com.meshcoreone.android.core.connectivity.device.ConnectedDeviceEditor
import com.meshcoreone.android.core.connectivity.device.RemoveUnfavoritedResult
import com.meshcoreone.android.core.connectivity.pairing.PairingCoordinator
import com.meshcoreone.android.core.connectivity.pairing.SystemPairedAccessory
import com.meshcoreone.android.core.contracts.domain.ConnectionIntent
import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.DevicePlatform
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.event.FrequencyRange
import com.meshcoreone.android.core.protocol.model.AutoAddConfig
import com.meshcoreone.android.core.protocol.model.SelfInfo
import com.meshcoreone.android.core.runtime.ConnectionError
import com.meshcoreone.android.core.runtime.ConnectionManager
import com.meshcoreone.android.core.runtime.ConnectionPlatform
import com.meshcoreone.android.core.runtime.LastConnection
import com.meshcoreone.android.core.runtime.LastConnectionStore
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

/** Runtime state the sync retry policy reads, plus the out-of-band disconnect the resync-failure path needs. */
internal class ManagerRuntimeSyncState(
    private val manager: () -> ConnectionManager,
    private val scope: CoroutineScope,
) : RuntimeSyncState {
    override val detectedPlatform: DevicePlatform get() = manager().detectedPlatform
    override val lastCleanChannelSync: Pair<RadioId, Instant>? get() = manager().lastCleanChannelSync
    override val lastAttemptedChannelSync: Pair<RadioId, Instant>? get() = manager().lastAttemptedChannelSync

    override fun requestDisconnect(reason: RuntimeDisconnectReason) {
        scope.launch { manager().disconnect(reason) }
    }
}

/**
 * [AppConnectionPort] over the runtime manager, the pairing coordinator and the connected-device editor. The
 * last-connection ids are an in-memory cache of the persisted store (app state reads them synchronously); the
 * cache is refreshed after every runtime observer event and after activation.
 */
class ConnectionManagerAppPort(
    private val manager: ConnectionManager,
    private val platform: ConnectionPlatform,
    private val pairing: PairingCoordinator,
    private val last: LastConnectionStore,
    private val editor: ConnectedDeviceEditor,
    private val stopScanning: () -> Unit,
) : AppConnectionPort {
    @Volatile private var cached: LastConnection = LastConnection(null, null, null, null, null, null)
    @Volatile private var repeatRangesOverride: SnapshotList<FrequencyRange>? = null

    suspend fun refreshLastConnection() {
        cached = last.read()
    }

    override val connectionState: DeviceConnectionState get() = manager.connectionState
    override val connectedDevice: DeviceDTO? get() = manager.connectedDevice
    override val lastConnectedDeviceId: UUID? get() = cached.deviceId
    override val lastConnectedRadioId: RadioId? get() = cached.radioId
    val lastConnectedDeviceName: String? get() = cached.deviceName
    override val shouldSuppressDisconnectedPill: Boolean get() = manager.connectionIntent == ConnectionIntent.UserDisconnected
    override val hasSystemPairingRegistry: Boolean get() = platform.hasSystemPairingRegistry
    override val shouldDeferOpportunisticReconnect: Boolean get() = manager.shouldDeferOpportunisticReconnect
    override var isPairingFlowActive: Boolean
        get() = pairing.isPairingFlowActive
        set(value) = pairing.setPairingFlags(flowActive = value)

    /** Allowed repeat frequency ranges: the settings-event overlay, else the runtime's connect-time value. */
    val allowedRepeatFreqRanges: SnapshotList<FrequencyRange>
        get() = repeatRangesOverride ?: manager.allowedRepeatFrequencyRanges

    override suspend fun activate() {
        manager.activate()
        refreshLastConnection()
    }

    override suspend fun appDidEnterBackground() = manager.appDidEnterBackground()
    override suspend fun appDidBecomeActive() = manager.appDidBecomeActive()
    override suspend fun checkWiFiConnectionHealth() = manager.checkWiFiConnectionHealth()

    /**
     * The runtime has no generation-checked promotion entry point, so a sync that failed while connected is retried
     * by the runtime's own reconnect path; the resync loop's exhaustion disconnects and the watchdog reconnects.
     */
    override suspend fun checkSyncHealth() = Unit

    override fun clearSurfacedAuthenticationFailure() = manager.clearSurfacedAuthenticationFailure()
    override suspend fun disconnect(reason: RuntimeDisconnectReason) {
        manager.disconnect(reason)
        refreshLastConnection()
    }

    override suspend fun connectViaWiFi(host: String, port: UShort, forceFullSync: Boolean) =
        manager.connect(ConnectionTarget.WiFi(host, port), forceFullSync, forceReconnect = false)

    override suspend fun connect(deviceId: UUID, forceReconnect: Boolean) {
        val target = platform.targetForDevice(deviceId) ?: throw ConnectionError.DeviceNotFound()
        manager.connect(target, forceFullSync = false, forceReconnect = forceReconnect)
    }

    override suspend fun pairNewDevice() = pairing.pairNewDevice()
    override suspend fun systemAccessoriesMissingDeviceRecord(): List<SystemPairedAccessory> =
        pairing.systemAccessoriesMissingDeviceRecord()
    override suspend fun removeSystemAccessoriesMissingDeviceRecord(ids: List<UUID>) =
        pairing.removeSystemAccessoriesMissingDeviceRecord(ids)
    override suspend fun removeFailedPairing(deviceId: UUID) = pairing.removeFailedPairing(deviceId)
    override suspend fun stopBleScanning() = stopScanning()
    override suspend fun removeStaleNodes(olderThanDays: Int): RemoveUnfavoritedResult = editor.removeStaleNodes(olderThanDays)

    override fun updateDevice(info: SelfInfo, appliedRadioPresetId: String?) { editor.updateDevice(info, appliedRadioPresetId) }
    override fun updateDevice(device: DeviceDTO) = editor.updateDevice(device)
    override fun updateAutoAddConfig(config: AutoAddConfig) { editor.updateAutoAddConfig(config) }
    override fun updateClientRepeat(enabled: Boolean) { editor.updateClientRepeat(enabled) }
    override fun updatePathHashMode(mode: UByte) { editor.updatePathHashMode(mode) }
    override fun updateDefaultFloodScopeName(name: String?) { editor.updateDefaultFloodScopeName(name) }

    /** The runtime exposes the ranges read-only; the settings stream's latest value is kept here (see WP-303.md). */
    override fun setAllowedRepeatFreqRanges(ranges: SnapshotList<FrequencyRange>) { repeatRangesOverride = ranges }
}

/** [ConnectedDeviceAccess] over the runtime manager's guarded connected-device value. */
class ManagerDeviceAccess(private val manager: ConnectionManager) : ConnectedDeviceAccess {
    private val mutableChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)
    internal val changes: kotlinx.coroutines.flow.StateFlow<Long> = mutableChanges
    override val connectedDevice: DeviceDTO? get() = manager.connectedDevice
    override fun replaceConnectedDevice(device: DeviceDTO) {
        manager.replaceConnectedDevice(device)
        if (manager.connectedDevice == device) {
            mutableChanges.update { Math.incrementExact(it) }
        }
    }
}
