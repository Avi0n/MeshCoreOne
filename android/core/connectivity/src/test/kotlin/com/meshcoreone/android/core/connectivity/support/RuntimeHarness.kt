// AndroidOnly: WP-206 Composes the real core:runtime ConnectionManager with WP-206 platform adapters, a protocol-speaking radio and fakes.
package com.meshcoreone.android.core.connectivity.support

import com.meshcoreone.android.core.ble.BleLinkDiagnostics
import com.meshcoreone.android.core.ble.BlePhase
import com.meshcoreone.android.core.ble.BluetoothAvailability
import com.meshcoreone.android.core.connectivity.ConnectivityDiagnostics
import com.meshcoreone.android.core.connectivity.ConnectivityPlatform
import com.meshcoreone.android.core.connectivity.SystemLinkAdopter
import com.meshcoreone.android.core.connectivity.ble.BleLinkInspector
import com.meshcoreone.android.core.connectivity.ble.BleLinkSnapshot
import com.meshcoreone.android.core.connectivity.ble.BleScanCoordinator
import com.meshcoreone.android.core.connectivity.ble.BleScanGateway
import com.meshcoreone.android.core.connectivity.ble.DiscoveredDevice
import com.meshcoreone.android.core.connectivity.ble.LinkFailureKind
import com.meshcoreone.android.core.connectivity.ble.SystemLinkProbe
import com.meshcoreone.android.core.connectivity.pairing.BluetoothEndpoint
import com.meshcoreone.android.core.connectivity.pairing.CompanionPairingService
import com.meshcoreone.android.core.connectivity.pairing.KnownEndpointStore
import com.meshcoreone.android.core.connectivity.pairing.PairingConnectionPort
import com.meshcoreone.android.core.connectivity.pairing.PairingCoordinator
import com.meshcoreone.android.core.connectivity.pairing.PairingDisconnectReason
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.model.DeviceCapabilities
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import com.meshcoreone.android.core.runtime.*
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

internal val RADIO_KEY: Bytes = Bytes(ByteArray(32) { 0x7b })

@Suppress("UNCHECKED_CAST")
internal fun <T : Any> rejecting(type: Class<T>): T = (Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
    throw AssertionError("Unexpected role invocation: ${method.name}")
} as T)

internal class FakePreferences : ProcessConnectionPreferences {
    val values = linkedMapOf<String, RuntimePreferenceValue>()
    override suspend fun read(): RuntimePreferenceSnapshot = RuntimePreferenceSnapshot(values)
    override suspend fun update(transform: (MutableMap<String, RuntimePreferenceValue>) -> Unit): RuntimePreferenceSnapshot {
        val next = values.toMutableMap()
        transform(next)
        values.clear(); values.putAll(next)
        return RuntimePreferenceSnapshot(values)
    }
}

/** Repository double with core:data's ghost-demotion semantics (new surrogate id, inactive, no methods). */
internal class FakeDevices : DevicePersisting {
    val rows = linkedMapOf<UUID, DeviceDTO>()
    val contacts = mutableListOf<ContactDTO>()
    val messages = mutableListOf<MessageDTO>()
    val channels = mutableListOf<ChannelDTO>()
    var fetchByIdFault: Exception? = null
    val calls = mutableListOf<String>()
    override suspend fun fetchDevice(id: UUID): DeviceDTO? { fetchByIdFault?.let { throw it }; return rows[id] }
    override suspend fun fetchDevice(radioId: RadioId): DeviceDTO? = rows.values.firstOrNull { it.radioId == radioId }
    override suspend fun fetchDevice(publicKey: Bytes): DeviceDTO? = rows.values.firstOrNull { it.publicKey == publicKey }
    override suspend fun fetchDevices(): SnapshotList<DeviceDTO> = rows.values.snapshot()
    override suspend fun fetchActiveDevice(): DeviceDTO? = rows.values.firstOrNull { it.isActive }
    override suspend fun saveDevice(dto: DeviceDTO) { calls += "save"; rows[dto.id] = dto }
    override suspend fun setActiveDevice(id: UUID) { rows.replaceAll { key, row -> row.copy(isActive = key == id) } }
    override suspend fun updateDeviceLastContactSync(radioId: RadioId, timestamp: UInt) = Unit
    override suspend fun addDeviceKnownRegion(radioId: RadioId, region: String) { calls += "addRegion:$region" }
    override suspend fun removeDeviceKnownRegion(radioId: RadioId, region: String) { calls += "removeRegion:$region" }
    override suspend fun deleteDeviceData(id: UUID) { calls += "deleteData" }
    override suspend fun deleteDevice(id: UUID) { calls += "delete"; rows.remove(id) }
    override suspend fun demoteDeviceToGhost(id: UUID) {
        calls += "demote"
        val row = rows.remove(id) ?: return
        val ghost = row.copy(id = UUID.randomUUID(), isActive = false, connectionMethods = SnapshotList.empty())
        rows[ghost.id] = ghost
    }
    override suspend fun deleteDeviceAndData(id: UUID) {
        calls += "deleteAndData"
        val row = rows.remove(id) ?: return
        contacts.removeAll { it.radioId == row.radioId }
        messages.removeAll { it.radioId == row.radioId }
        channels.removeAll { it.radioId == row.radioId }
    }
    override suspend fun reconcileGhostIdentity(currentDeviceID: UUID, newPublicKey: Bytes): RadioId? = null
}

internal class FakeContacts(private val devices: FakeDevices) : ContactPersisting by rejecting(ContactPersisting::class.java) {
    override suspend fun fetchContacts(radioId: RadioId): SnapshotList<ContactDTO> =
        devices.contacts.filter { it.radioId == radioId }.snapshot()
    override suspend fun saveContact(dto: ContactDTO) { devices.contacts += dto }
    override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? =
        devices.contacts.firstOrNull { it.radioId == radioId && it.publicKey == publicKey }
}

/** A real-protocol radio over the merged MeshCoreSession: answers the startup handshake frames. */
internal class FakeRadio(private val key: Bytes = RADIO_KEY) : MeshTransport {
    private var inbound = Channel<Bytes>(Channel.UNLIMITED)
    private var connected = false
    var connects = 0
    var connectFailure: Exception? = null
    var beforeConnect: suspend () -> Unit = {}
    override suspend fun connect() {
        beforeConnect()
        connectFailure?.let { throw it }
        if (!connected) { inbound = Channel(Channel.UNLIMITED); connects++; connected = true }
    }
    override suspend fun disconnect() {
        if (connected) { connected = false; inbound.close() }
    }
    override suspend fun isConnected(): Boolean = connected
    override suspend fun receivedData(): Flow<Bytes> {
        val stream = inbound
        return flow { for (frame in stream) emit(frame) }
    }
    override suspend fun send(data: Bytes) {
        check(connected)
        when (data[0].toInt()) {
            0x01 -> inbound.send(selfPacket(key))
            0x16 -> inbound.send(devicePacket())
            0x3b -> inbound.send(Bytes.of(0x19, 0, 0))
            0x05 -> inbound.send(Bytes.of(9) + little32(EPOCH.epochSecond))
            0x06 -> inbound.send(Bytes.of(0))
            else -> throw AssertionError("Unexpected command ${data.hexString}")
        }
    }

    private fun little32(value: Long): Bytes =
        Bytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array())
    private fun selfPacket(key: Bytes): Bytes =
        Bytes.of(5, 1, 22, 22) + key + little32(0) + little32(0) + Bytes.of(0, 0, 2, 0) +
            little32(915_000) + little32(250_000) + Bytes.of(10, 5) + Bytes.utf8("TestNode")
    private fun devicePacket(): Bytes =
        Bytes.of(13, 9, 50, 8) + little32(0) + Bytes.utf8("01 Jan 2025").paddedOrTruncated(12) +
            Bytes.utf8("T-Deck").paddedOrTruncated(40) + Bytes.utf8("v1.13.0").paddedOrTruncated(20) + Bytes.of(0)
}

internal class FakeLink(val radio: FakeRadio, override val type: TransportType, val target: ConnectionTarget) : RuntimeLink {
    override val transport: MeshTransport get() = radio
    var callbacks: LinkCallbacks? = null
    var live: SessionToken? = null
    /** The link's in-memory bond ledger is the runtime ReconnectPolicy verification map (single owner). */
    val policy = ReconnectPolicy()
    val bondVerifications: Map<UUID, Instant> get() = policy.bondVerificationDates
    val clearedBonds = mutableListOf<UUID>()
    override fun register(callbacks: LinkCallbacks): AutoCloseable {
        this.callbacks = callbacks
        return AutoCloseable { this.callbacks = null }
    }
    override suspend fun configure(capabilities: DeviceCapabilities, platform: DevicePlatform) = Unit
    override suspend fun setSessionLive(token: SessionToken?) { live = token }
    override suspend fun recordBondVerification(deviceId: UUID, at: Instant) = policy.recordBondVerification(deviceId, at)
    override suspend fun clearBondVerification(deviceId: UUID) { clearedBonds += deviceId; policy.clearBondVerification(deviceId) }
    override suspend fun mayRefreshBond(deviceId: UUID): Boolean = live != null && deviceId in bondVerifications
}

internal class FakeServices(val inputs: RuntimeServiceInputs) : RuntimeServices {
    override val token: SessionToken get() = inputs.connection.token
    var syncResult: RuntimeSyncResult = RuntimeSyncResult.Usable
    var beforeSync: suspend () -> Unit = {}
    var ensureListenersCalls = 0
    var teardowns = 0
    private val jobs = mutableListOf<Job>()
    override suspend fun hydrate() = Unit
    override suspend fun startMonitoring(options: MonitoringOptions) {
        jobs += inputs.connection.scope.launch { awaitCancellation() }
    }
    override suspend fun initialSync(forceFullSync: Boolean): RuntimeSyncResult {
        inputs.callbacks.channelSyncAttempted()
        beforeSync()
        if (syncResult == RuntimeSyncResult.Usable) inputs.callbacks.cleanChannelSync()
        return syncResult
    }
    override suspend fun remoteDisconnected(): Set<UUID> = emptySet()
    override suspend fun reauthenticate(sessionIds: Set<UUID>) = Unit
    override suspend fun resetSyncState() = Unit
    override suspend fun ensureListeners() { ensureListenersCalls++ }
    override suspend fun tearDown(): TeardownReport {
        teardowns++
        jobs.forEach { it.cancelAndJoin() }
        return TeardownReport(SnapshotList.empty())
    }
}

/** Platform link facts the tests stub; projected through the production [BleLinkInspector]. */
internal class LinkStub {
    var connected = false
    var autoReconnecting = false
    var poweredOff = false
    var connectedDeviceId: UUID? = null
    var phase: BlePhase = BlePhase.Idle
    var systemConnected: (UUID) -> Boolean = { false }
    var adoptionSucceeds = false
    val systemConnectedCalls = mutableListOf<UUID>()

    fun snapshot(): BleLinkSnapshot {
        val effective = when {
            connected -> BlePhase.Connected
            autoReconnecting -> BlePhase.AutoReconnecting
            else -> phase
        }
        val availability = if (poweredOff) BluetoothAvailability.PoweredOff else BluetoothAvailability.Ready
        return BleLinkSnapshot(connectedDeviceId ?: UUID(0, 0), BleLinkDiagnostics(
            availability, effective, 1, null, null, null, null, false, false, false, 0, null, null,
        ))
    }
}

internal class StubProbe(private val stub: LinkStub) : SystemLinkProbe {
    override suspend fun isDeviceConnectedToSystem(deviceId: UUID): Boolean {
        stub.systemConnectedCalls += deviceId
        return stub.systemConnected(deviceId)
    }
    override suspend fun systemConnectedDeviceIds(): Set<UUID> = emptySet()
}

/** Scan-fallback endpoint memory that resolves any id unless explicitly unknown. */
internal class SyntheticEndpoints : KnownEndpointStore {
    val unknown = mutableSetOf<UUID>()
    val forgotten = mutableListOf<UUID>()
    override suspend fun endpoint(deviceId: UUID): BluetoothEndpoint? =
        if (deviceId in unknown) null else BluetoothEndpoint(deviceId, syntheticAddress(deviceId), null)
    override suspend fun remember(endpoint: BluetoothEndpoint) { unknown -= endpoint.deviceId }
    override suspend fun forget(deviceId: UUID) { forgotten += deviceId }
}

internal class FakeScanGateway : BleScanGateway {
    var scanning = false
    var startCount = 0
    var stopCount = 0
    private var deliver: ((DiscoveredDevice) -> Unit)? = null
    override fun startScan(onDevice: (DiscoveredDevice) -> Unit, onFailure: (Throwable) -> Unit) {
        startCount++; scanning = true; deliver = onDevice
    }
    override fun stopScan() { stopCount++; scanning = false; deliver = null }
    fun discover(device: DiscoveredDevice) { deliver?.invoke(device) }
}

/**
 * The host mapping WP-303 must provide: runtime `ConnectionPlatform` over [ConnectivityPlatform].
 * Kept in test sources because core:connectivity may not depend on core:runtime.
 */
internal class RuntimePlatformAdapter(val connectivity: ConnectivityPlatform) : ConnectionPlatform {
    override val hasSystemPairingRegistry: Boolean get() = connectivity.hasSystemPairingRegistry
    override val registryActive: Boolean get() = connectivity.registryActive
    override suspend fun activate() = connectivity.activate()
    override suspend fun foreground(active: Boolean) = connectivity.foreground(active)
    override suspend fun state(target: ConnectionTarget): PlatformLinkState {
        val id = (target as? ConnectionTarget.Bluetooth)?.deviceId
            ?: return PlatformLinkState(false, false, false, null, "idle", false)
        val state = connectivity.linkState(id)
        return PlatformLinkState(state.connected, state.autoReconnecting, state.bluetoothPoweredOff,
            state.connectedDeviceId, state.phase, state.systemConnected)
    }
    override suspend fun isRegistered(deviceId: UUID): Boolean = connectivity.isRegistered(deviceId)
    override suspend fun targetForDevice(deviceId: UUID): ConnectionTarget? =
        connectivity.endpointFor(deviceId)?.let { ConnectionTarget.Bluetooth(it.handle, it.deviceId) }
    override suspend fun adoptSystemLink(target: ConnectionTarget): Boolean =
        (target as? ConnectionTarget.Bluetooth)?.let { connectivity.adoptSystemLink(it.deviceId) } ?: false
    override fun classifyFailure(failure: Throwable): LinkFailure? = failure as? LinkFailure ?: when (connectivity.classifyFailure(failure)) {
        LinkFailureKind.AuthenticationFailed -> LinkFailure.AuthenticationFailed(failure)
        LinkFailureKind.ConnectionTimeout -> LinkFailure.ConnectionTimeout(failure)
        LinkFailureKind.BluetoothPoweredOff -> LinkFailure.BluetoothPoweredOff()
        LinkFailureKind.BluetoothUnavailable -> LinkFailure.BluetoothUnavailable()
        LinkFailureKind.BluetoothUnauthorized -> LinkFailure.BluetoothUnauthorized()
        LinkFailureKind.DeviceConnectedToOtherApp -> LinkFailure.DeviceConnectedToOtherApp()
        LinkFailureKind.ConnectionFailed, LinkFailureKind.DeviceNotFound -> LinkFailure.ConnectionFailed(failure.message ?: "", failure)
        null -> null
    }
}

/** The host mapping WP-303 must provide: [PairingConnectionPort] over the runtime manager. */
internal class RuntimePairingPort(
    private val manager: ConnectionManager,
    private val platform: RuntimePlatformAdapter,
) : PairingConnectionPort {
    val connectRequests = mutableListOf<UUID>()
    override val connectionState: DeviceConnectionState get() = manager.connectionState
    override val connectedDevice: DeviceDTO? get() = manager.connectedDevice
    override val activeConnectionAttemptDeviceId: UUID? get() = manager.activeConnectionAttemptDeviceId
    override fun setPairingActivity(pairingInProgress: Boolean, pairingFlowActive: Boolean) =
        manager.setPairingActivity(pairingInProgress, pairingFlowActive)
    override suspend fun connect(deviceId: UUID, forceFullSync: Boolean, forceReconnect: Boolean) {
        connectRequests += deviceId
        val target = platform.targetForDevice(deviceId) ?: throw ConnectionError.DeviceNotFound()
        manager.connect(target, forceFullSync, forceReconnect)
    }
    override suspend fun disconnect(reason: PairingDisconnectReason) {
        manager.disconnect(when (reason) {
            PairingDisconnectReason.FORGET_DEVICE -> RuntimeDisconnectReason.FORGET_DEVICE
            PairingDisconnectReason.FACTORY_RESET -> RuntimeDisconnectReason.FACTORY_RESET
            PairingDisconnectReason.DEVICE_REMOVED_FROM_SETTINGS -> RuntimeDisconnectReason.DEVICE_REMOVED_FROM_SETTINGS
            PairingDisconnectReason.PAIRING_FAILED -> RuntimeDisconnectReason.PAIRING_FAILED
        })
    }
    override suspend fun disconnectTransport() = manager.disconnectTransport()
    override suspend fun clearReconnectingDevice() {
        manager.reconnectionCoordinator.cancelTimeout()
        manager.reconnectionCoordinator.clearReconnectingDevice()
    }
    override fun markDisconnected() {
        if (!manager.connectionState.isOperational) manager.setConnectionState(DeviceConnectionState.DISCONNECTED)
    }
    override suspend fun clearPersistedConnection(deviceId: UUID) = manager.clearPersistedConnection(deviceId)
    override fun isAuthenticationFailure(failure: Throwable): Boolean =
        platform.classifyFailure(failure) is LinkFailure.AuthenticationFailed
    override fun isDeviceConnectedToOtherApp(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }.take(8).any { it is LinkFailure.DeviceConnectedToOtherApp }
}

internal class RuntimeHarness(val scenario: Scenario) {
    val clock = scenario.clock
    val preferences = FakePreferences()
    val last = LastConnectionStore(preferences, clock)
    val devices = FakeDevices()
    val contacts = FakeContacts(devices)
    val companion = MockCompanionSetupService()
    val pairing = CompanionPairingService(companion)
    val stub = LinkStub()
    val probe = StubProbe(stub)
    val endpoints = SyntheticEndpoints()
    val adoptions = mutableListOf<UUID>()
    val foregroundCalls = mutableListOf<Boolean>()
    val connectivity = ConnectivityPlatform(
        pairing,
        BleLinkInspector(probe, stub::snapshot) {
            if (stub.poweredOff) BluetoothAvailability.PoweredOff else BluetoothAvailability.Ready
        },
        endpoints,
        SystemLinkAdopter { endpoint -> adoptions += endpoint.deviceId; stub.adoptionSucceeds },
        onForegroundChanged = { foregroundCalls += it },
    )
    val platform = RuntimePlatformAdapter(connectivity)
    val radios = mutableListOf<FakeRadio>()
    val links = mutableListOf<FakeLink>()
    val services = mutableListOf<FakeServices>()
    val diagnostics = mutableListOf<RuntimeDiagnostic>()
    val reports = mutableListOf<Pair<String, Throwable?>>()
    var createRadio: (ConnectionTarget) -> FakeRadio = { FakeRadio() }
    var configureServices: (FakeServices) -> Unit = {}
    var lossCount = 0
    var autoCount = 0
    var syncedCount = 0
    val authFailures = mutableListOf<UUID>()
    val manager = ConnectionManager(
        devices,
        object : RoomPersisting by rejecting(RoomPersisting::class.java) {
            override suspend fun resetAllRemoteNodeSessionConnections() = Unit
        },
        contacts,
        object : ProcessRuntimeMaintenance {
            override suspend fun warmUp() = Unit
            override suspend fun initializeDevicePreferences(device: DeviceDTO) = Unit
        },
        last, platform,
        RuntimeLinkFactory { target ->
            val radio = createRadio(target).also { radios += it }
            FakeLink(radio, if (target is ConnectionTarget.WiFi) TransportType.WIFI else TransportType.BLUETOOTH, target).also { links += it }
        },
        RuntimeServiceFactory { inputs, ownership ->
            FakeServices(inputs).also {
                services += it
                ownership.register(it)
                configureServices(it)
            }
        },
        ConnectionObserver(
            onServicesAvailable = {},
            onConnectionLost = { lossCount++ },
            onAutoReconnectStarted = { autoCount++ },
            onDeviceSynced = { syncedCount++ },
            onAuthenticationFailure = { authFailures += it },
            onLastDeviceCleared = {},
        ),
        RuntimeIssueReporter { diagnostics += it },
        clock,
        context = scenario.scope.coroutineContext,
        configuration = SessionConfiguration(defaultTimeout = 1.0, clientIdentifier = "MCore"),
        jitter = { 0.0 },
    )
    val port = RuntimePairingPort(manager, platform)
    val scanGateway = FakeScanGateway()
    val scans = BleScanCoordinator(scanGateway)

    fun coordinator(bonds: com.meshcoreone.android.core.connectivity.bond.BondInspector? = null): PairingCoordinator = PairingCoordinator(
        port, pairing, devices, probe, scans::stopBleScanning, clock, scenario.scope,
        ConnectivityDiagnostics { operation, failure -> reports += operation to failure },
        endpoints = endpoints,
        bonds = bonds,
    )

    fun target(id: UUID): ConnectionTarget.Bluetooth =
        ConnectionTarget.Bluetooth(BluetoothPairingHandle(BluetoothAddress(syntheticAddress(id)), null), id)

    /** Registers [id] in the companion registry (activated session) so the runtime registry check passes. */
    suspend fun register(id: UUID, name: String = "Radio") {
        companion.setPairedAccessories(companion.storedAccessories + association(id, name))
        companion.activateSession()
    }

    suspend fun connectReady(id: UUID = UUID.randomUUID()): UUID {
        if (companion.accessory(id) == null) register(id)
        manager.connect(target(id))
        settle()
        assertEquals(DeviceConnectionState.READY, manager.connectionState)
        return id
    }

    /** A live link the user still wants, then lost: the natural "disconnected + wantsConnection" state. */
    suspend fun connectThenLose(id: UUID = UUID.randomUUID(), failure: Throwable? = null): UUID {
        connectReady(id)
        loseLink(failure)
        assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
        return id
    }

    suspend fun loseLink(failure: Throwable? = null) {
        checkNotNull(links.last().callbacks).onDisconnected(failure)
        settle()
    }

    suspend fun lastConnectedDeviceId(): UUID? = last.read().deviceId

    suspend fun close() {
        val done = CompletableDeferred<Unit>()
        scenario.scope.launch { manager.close(); done.complete(Unit) }
        scenario.awaitCondition { done.isCompleted }
    }
}

internal fun runtimeScenario(body: suspend RuntimeHarness.() -> Unit) = scenario {
    val harness = RuntimeHarness(this)
    try { harness.body() } finally { harness.manager.stopReconnectionWatchdog(); harness.close() }
}

internal fun contact(radio: RadioId, name: String, favorite: Boolean = false, lastModified: UInt = 0u): ContactDTO =
    ContactDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { (name.hashCode() + it).toByte() }), name = name,
        lastHeardTimestamp = null, isFavorite = favorite, lastModified = lastModified)
