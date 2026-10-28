// AndroidOnly: WP-303 Test doubles for app state: a recording connection port with a pairing-registry model, and builders.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.navigation.NavigationCoordinator
import com.meshcoreone.android.app.state.AppConnectionPort
import com.meshcoreone.android.app.state.AppSession
import com.meshcoreone.android.app.state.AppState
import com.meshcoreone.android.app.state.AppStateDependencies
import com.meshcoreone.android.app.state.AppStatePlatform
import com.meshcoreone.android.app.state.BatteryMonitor
import com.meshcoreone.android.app.state.ChatPrimerFactory
import com.meshcoreone.android.app.state.ConnectionUiState
import com.meshcoreone.android.app.state.RegionSelectionStore
import com.meshcoreone.android.app.state.ResyncFailureRegistry
import com.meshcoreone.android.app.state.StaleCleanupPreferences
import com.meshcoreone.android.core.connectivity.device.RemoveUnfavoritedResult
import com.meshcoreone.android.core.connectivity.pairing.SystemPairedAccessory
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.event.FrequencyRange
import com.meshcoreone.android.core.protocol.model.AutoAddConfig
import com.meshcoreone.android.core.protocol.model.SelfInfo
import com.meshcoreone.android.core.runtime.DeadlineClock
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.runtime.SystemRuntimeClock
import com.meshcoreone.android.core.services.rendering.ChatCoordinatorRegistry
import java.time.Instant
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CoroutineScope

/** A recording [AppConnectionPort]; the pairing registry models the system accessories the Swift ASK mock modeled. */
internal class FakeConnectionPort : AppConnectionPort {
    override var connectionState: DeviceConnectionState = DeviceConnectionState.DISCONNECTED
    override var connectedDevice: DeviceDTO? = null
    override var lastConnectedDeviceId: UUID? = null
    override var lastConnectedRadioId: RadioId? = null
    override var shouldSuppressDisconnectedPill: Boolean = false
    override var hasSystemPairingRegistry: Boolean = true
    override val shouldDeferOpportunisticReconnect: Boolean get() = isPairingFlowActive
    override var isPairingFlowActive: Boolean = false
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    // Pairing registry model.
    val paired = mutableListOf<SystemPairedAccessory>()
    var pickerCalls = 0
    val pickerOutcomes = ArrayDeque<() -> Unit>()
    var removeFailure: Exception? = null
    var lastRemoved: UUID? = null
    var connectFailure: Exception? = null
    var beforePicker: suspend () -> Unit = {}

    override suspend fun activate() { calls += "activate" }
    override suspend fun appDidEnterBackground() { calls += "background" }
    override suspend fun appDidBecomeActive() { calls += "active" }
    override suspend fun checkWiFiConnectionHealth() { calls += "wifiHealth" }
    override suspend fun checkSyncHealth() { calls += "syncHealth" }
    override fun clearSurfacedAuthenticationFailure() { calls += "clearAuthLatch" }
    override suspend fun disconnect(reason: RuntimeDisconnectReason) {
        calls += "disconnect:$reason"
        connectionState = DeviceConnectionState.DISCONNECTED
        connectedDevice = null
    }
    override suspend fun connectViaWiFi(host: String, port: UShort, forceFullSync: Boolean) { calls += "wifi:$host:$port" }
    override suspend fun connect(deviceId: UUID, forceReconnect: Boolean) {
        calls += "connect:$deviceId:$forceReconnect"
        connectFailure?.let { throw it }
    }
    override suspend fun pairNewDevice() {
        pickerCalls += 1
        beforePicker()
        pickerOutcomes.removeFirstOrNull()?.invoke()
    }
    override suspend fun systemAccessoriesMissingDeviceRecord(): List<SystemPairedAccessory> = paired.toList()
    override suspend fun removeSystemAccessoriesMissingDeviceRecord(ids: List<UUID>) {
        removeFailure?.let { throw it }
        lastRemoved = ids.lastOrNull()
        paired.removeAll { it.id in ids }
    }
    override suspend fun removeFailedPairing(deviceId: UUID) { calls += "removeFailed:$deviceId" }
    override suspend fun stopBleScanning() { calls += "stopScan" }
    override suspend fun removeStaleNodes(olderThanDays: Int): RemoveUnfavoritedResult {
        calls += "removeStale:$olderThanDays"
        return RemoveUnfavoritedResult(0, 0)
    }
    override fun updateDevice(info: SelfInfo, appliedRadioPresetId: String?) { calls += "updateDeviceInfo" }
    override fun updateDevice(device: DeviceDTO) { calls += "updateDevice" }
    override fun updateAutoAddConfig(config: AutoAddConfig) { calls += "autoAdd" }
    override fun updateClientRepeat(enabled: Boolean) { calls += "clientRepeat:$enabled" }
    override fun updatePathHashMode(mode: UByte) { calls += "pathHash:$mode" }
    override fun updateDefaultFloodScopeName(name: String?) { calls += "floodScope:$name" }
    override fun setAllowedRepeatFreqRanges(ranges: SnapshotList<FrequencyRange>) { calls += "repeatRanges" }
}

/** Stores the selection as the production JSON text, so a round trip proves the stored form decodes to the same value. */
internal class MemoryRegionStore(var storedJson: String? = null) : RegionSelectionStore {
    val persisted = Collections.synchronizedList(mutableListOf<RegionSelection?>())
    override suspend fun load(): RegionSelection? = storedJson?.let(com.meshcoreone.android.app.state.RegionSelectionJson::decode)
    override suspend fun persist(selection: RegionSelection?) {
        persisted += selection
        storedJson = selection?.let(com.meshcoreone.android.app.state.RegionSelectionJson::encode)
    }
}

internal class RecordingPlatform : AppStatePlatform {
    val events = Collections.synchronizedList(mutableListOf<String>())
    override suspend fun flushDebugLog() { events += "flush" }
    override suspend fun onUnreadCountChanged(totalUnread: Int) { events += "unread:$totalUnread" }
    override suspend fun onConnectionLost() { events += "lost" }
    override fun onEnterBackground() { events += "enterBackground" }
    override suspend fun onReturnToForeground() { events += "returnToForeground" }
    override fun resumeOfflineMaps() { events += "resumeMaps" }
}

internal class MemoryStaleCleanup(var days: Int = 0, var last: Instant? = null) : StaleCleanupPreferences {
    override suspend fun thresholdDays(): Int = days
    override suspend fun lastRun(): Instant? = last
    override suspend fun recordRun(at: Instant) { last = at }
}

internal fun appStateOf(
    scope: CoroutineScope,
    port: FakeConnectionPort = FakeConnectionPort(),
    clock: DeadlineClock = SystemRuntimeClock(),
    session: () -> AppSession? = { null },
    store: PersistenceStoreProtocol? = null,
    navigation: NavigationCoordinator = NavigationCoordinator(),
    platform: AppStatePlatform = AppStatePlatform.NONE,
    regionStore: RegionSelectionStore? = null,
    stale: StaleCleanupPreferences? = null,
    primerFactory: ChatPrimerFactory = ChatPrimerFactory { null },
    now: () -> Instant = Instant::now,
    closeChatRoute: () -> Unit = {},
): AppState = AppState(
    AppStateDependencies(
        connection = port,
        scope = scope,
        session = session,
        processStore = { store },
        draftStore = com.meshcoreone.android.core.services.rendering.DraftStore(MemoryDraftDefaults()),
        connectionUi = ConnectionUiState(scope, clock),
        batteryMonitor = BatteryMonitor(scope, clock),
        resyncFailure = ResyncFailureRegistry { },
        registryFactory = { persistence -> ChatCoordinatorRegistry(persistence, scope) },
        primerFactory = primerFactory,
        navigation = navigation,
        platform = platform,
        regionStore = regionStore,
        stalePreferences = stale,
        clock = clock,
        now = now,
        closeChatRoute = closeChatRoute,
    ),
)

internal fun deviceOf(id: UUID = UUID.randomUUID()): DeviceDTO =
    DeviceDTO(id = id, radioId = RadioId(id), publicKey = key(1), nodeName = "TestDevice", isActive = false)
