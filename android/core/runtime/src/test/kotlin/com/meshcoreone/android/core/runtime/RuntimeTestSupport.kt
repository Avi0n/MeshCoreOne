// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/ConnectionManager+Testing.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: test-only graph and source-layout radio producer over the real JVM session.
package com.meshcoreone.android.core.runtime

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.model.DeviceCapabilities
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.DynamicTest
import kotlin.test.*

internal val epochTime: Instant = Instant.ofEpochSecond(1_704_067_200)
internal val publicKey = Bytes(ByteArray(32) { 0xc3.toByte() })
internal fun target(id: UUID = UUID.randomUUID()) =
    ConnectionTarget.Bluetooth(BluetoothPairingHandle(BluetoothAddress("C0:00:00:00:00:01"), null), id)

@OptIn(ExperimentalCoroutinesApi::class)
internal class TestClock(private val scheduler: TestCoroutineScheduler) : RuntimeClock {
    override val elapsed: Duration get() = scheduler.currentTime.milliseconds
    override val instant: Instant get() = epochTime.plusMillis(scheduler.currentTime)
    val sleeps = mutableListOf<Duration>()
    override suspend fun sleep(duration: Duration) { sleeps += duration; delay(duration) }
}

internal class TestPreferences : ProcessConnectionPreferences {
    val values = linkedMapOf<String, RuntimePreferenceValue>()
    var reads = 0
    var writes = 0
    var failure: Exception? = null
    var beforeUpdate: suspend () -> Unit = {}
    override suspend fun read(): RuntimePreferenceSnapshot {
        failure?.let { throw it }
        reads++
        return RuntimePreferenceSnapshot(values)
    }
    override suspend fun update(transform: (MutableMap<String, RuntimePreferenceValue>) -> Unit): RuntimePreferenceSnapshot {
        failure?.let { throw it }
        beforeUpdate()
        val next = values.toMutableMap()
        transform(next)
        values.clear()
        values.putAll(next)
        writes++
        return RuntimePreferenceSnapshot(values)
    }
}

internal class TestDevices : DevicePersisting {
    val rows = linkedMapOf<UUID, DeviceDTO>()
    val calls = mutableListOf<String>()
    var failure: Exception? = null
    var ghostResult: RadioId? = null
    override suspend fun fetchDevice(id: UUID): DeviceDTO? { failure?.let { throw it }; calls += "id"; return rows[id] }
    override suspend fun fetchDevice(radioId: RadioId): DeviceDTO? = rows.values.firstOrNull { it.radioId == radioId }
    override suspend fun fetchDevice(publicKey: Bytes): DeviceDTO? { failure?.let { throw it }; calls += "key"; return rows.values.firstOrNull { it.publicKey == publicKey } }
    override suspend fun fetchDevices(): SnapshotList<DeviceDTO> = rows.values.snapshot()
    override suspend fun fetchActiveDevice(): DeviceDTO? = rows.values.firstOrNull { it.isActive }
    override suspend fun saveDevice(dto: DeviceDTO) { failure?.let { throw it }; calls += "save"; rows[dto.id] = dto }
    override suspend fun setActiveDevice(id: UUID) { rows.replaceAll { key, row -> row.copy(isActive = key == id) } }
    override suspend fun updateDeviceLastContactSync(radioId: RadioId, timestamp: UInt) {
        val row = checkNotNull(fetchDevice(radioId)); rows[row.id] = row.copy(lastContactSync = timestamp)
    }
    override suspend fun addDeviceKnownRegion(radioId: RadioId, region: String) {
        val row = checkNotNull(fetchDevice(radioId)); rows[row.id] = row.copy(knownRegions = (row.knownRegions + region).snapshot())
    }
    override suspend fun removeDeviceKnownRegion(radioId: RadioId, region: String) {
        val row = checkNotNull(fetchDevice(radioId)); rows[row.id] = row.copy(knownRegions = row.knownRegions.filterNot { it == region }.snapshot())
    }
    override suspend fun deleteDevice(id: UUID) { calls += "delete"; rows.remove(id) }
    override suspend fun deleteDeviceData(id: UUID) = error("Not a runtime operation")
    override suspend fun demoteDeviceToGhost(id: UUID) = error("Not a runtime operation")
    override suspend fun deleteDeviceAndData(id: UUID) = error("Not a runtime operation")
    override suspend fun reconcileGhostIdentity(currentDeviceID: UUID, newPublicKey: Bytes): RadioId? {
        calls += "reconcile"
        ghostResult?.let { id -> rows[currentDeviceID] = checkNotNull(rows[currentDeviceID]).copy(radioId = id, publicKey = newPublicKey) }
        return ghostResult
    }
}

internal fun <T> rejectingRole(type: Class<T>): T = type.cast(Proxy.newProxyInstance(
    type.classLoader, arrayOf(type),
) { _, method, _ -> throw AssertionError("Unexpected runtime role invocation: ${method.name}") })

internal class TestRadio : MeshTransport {
    private var inbound = Channel<Bytes>(Channel.UNLIMITED)
    private var connected = false
    var connects = 0
    var closes = 0
    var collectors = 0
    var maximumCollectors = 0
    var key = publicKey
    var connectFailure: Exception? = null
    var closeFailure: Exception? = null
    var beforeSend: suspend (Bytes) -> Unit = {}
    var beforeConnect: suspend () -> Unit = {}
    var beforeClose: suspend () -> Unit = {}
    val frames = mutableListOf<Bytes>()
    override suspend fun connect() {
        beforeConnect()
        connectFailure?.let { throw it }
        if (!connected) { inbound = Channel(Channel.UNLIMITED); connects++; connected = true }
    }
    override suspend fun disconnect() {
        beforeClose()
        if (connected) { closes++; connected = false; inbound.close() }
        closeFailure?.let { throw it }
    }
    override suspend fun isConnected(): Boolean = connected
    override suspend fun receivedData(): Flow<Bytes> {
        val stream = inbound
        return flow {
            collectors++; maximumCollectors = maxOf(maximumCollectors, collectors)
            try { for (frame in stream) emit(frame) } finally { collectors-- }
        }
    }
    override suspend fun send(data: Bytes) {
        check(connected)
        frames += data
        beforeSend(data)
        when (data[0].toInt() and 255) {
            0x01 -> {
                assertEquals(Bytes.fromHex("01032020202020204d436f7265"), data)
                receive(selfPacket(key))
            }
            0x16 -> { assertEquals(Bytes.fromHex("1603"), data); receive(devicePacket()) }
            0x3b -> { assertEquals(Bytes.of(0x3b), data); receive(Bytes.of(0x19, 0, 0)) }
            0x05 -> { assertEquals(Bytes.of(5), data); receive(Bytes.of(9) + little32(epochTime.epochSecond)) }
            0x06 -> receive(Bytes.of(0))
            else -> throw AssertionError("Unexpected real-session command ${data.hexString}")
        }
    }
    suspend fun receive(data: Bytes) { inbound.send(data) }
    fun finish(cause: Throwable? = null) { inbound.close(cause) }
}

// Independent layouts from frozen Swift Parsers+Device, PacketCodes, and ReconnectRebuildLifecycleTests.
internal fun little32(value: Long): Bytes =
    Bytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array())
internal fun selfPacket(key: Bytes = publicKey): Bytes =
    Bytes.of(5, 1, 22, 22) + key + little32(0) + little32(0) + Bytes.of(0, 0, 2, 0) +
        little32(915_000) + little32(250_000) + Bytes.of(10, 5) + Bytes.utf8("TestNode")
internal fun devicePacket(): Bytes =
    Bytes.of(13, 9, 50, 8) + little32(0) +
        Bytes.utf8("01 Jan 2025").paddedOrTruncated(12) +
        Bytes.utf8("T-Deck").paddedOrTruncated(40) +
        Bytes.utf8("v1.13.0").paddedOrTruncated(20) + Bytes.of(0)

internal class TestPlatform : ConnectionPlatform {
    override var hasSystemPairingRegistry = false
    override var registryActive = false
    var registered = true
    var resolvesTargets = true
    var target = target()
    var state = PlatformLinkState(false, false, false, null, "idle", false)
    var adopts = 0
    var adoptionSucceeds = false
    var onState: suspend () -> Unit = {}
    var activationCount = 0
    var activationFailure: Exception? = null
    var onActivate: suspend () -> Unit = {}
    val foregroundCalls = mutableListOf<Boolean>()
    override suspend fun activate() { activationCount++; activationFailure?.let { throw it }; onActivate() }
    override suspend fun foreground(active: Boolean) { foregroundCalls += active }
    override suspend fun state(target: ConnectionTarget): PlatformLinkState { onState(); return state }
    override suspend fun isRegistered(deviceId: UUID): Boolean = registered
    override suspend fun targetForDevice(deviceId: UUID): ConnectionTarget? =
        if (resolvesTargets) target.copy(deviceId = deviceId) else null
    override suspend fun adoptSystemLink(target: ConnectionTarget): Boolean { adopts++; return adoptionSucceeds }
    override fun classifyFailure(failure: Throwable): LinkFailure? = failure as? LinkFailure
}

internal class TestLink(val radio: TestRadio, override val type: TransportType) : RuntimeLink {
    override val transport: MeshTransport get() = radio
    var callbacks: LinkCallbacks? = null
    var callbackClosures = 0
    var registrations = 0
    var live: SessionToken? = null
    var bondRefreshAllowed = true
    var beforeBondRefresh: suspend () -> Unit = {}
    val clearedBonds = mutableListOf<UUID>()
    var configured = false
    var registrationHook: (LinkCallbacks) -> Unit = {}
    override fun register(callbacks: LinkCallbacks): AutoCloseable {
        check(this.callbacks == null)
        this.callbacks = callbacks
        registrations++
        registrationHook(callbacks)
        return AutoCloseable { callbackClosures++; this.callbacks = null }
    }
    override suspend fun configure(capabilities: DeviceCapabilities, platform: DevicePlatform) { configured = true }
    override suspend fun setSessionLive(token: SessionToken?) { live = token }
    override suspend fun recordBondVerification(deviceId: UUID, at: Instant) { check(live == null) }
    override suspend fun clearBondVerification(deviceId: UUID) { clearedBonds += deviceId; bondRefreshAllowed = false }
    override suspend fun mayRefreshBond(deviceId: UUID): Boolean { beforeBondRefresh(); return bondRefreshAllowed && live != null }
}

internal class TestServices(val inputs: RuntimeServiceInputs) : RuntimeServices {
    override val token: SessionToken get() = inputs.connection.token
    val calls = mutableListOf<String>()
    val ownedJobs = mutableListOf<Job>()
    var monitoringStarts = 0
    var teardowns = 0
    var syncResult: RuntimeSyncResult = RuntimeSyncResult.Usable
    var syncForces = mutableListOf<Boolean>()
    var beforeStart: suspend () -> Unit = {}
    var beforeSync: suspend () -> Unit = {}
    var closeFailure: Exception? = null
    var beforeHydrate: suspend () -> Unit = {}
    var beforeTeardown: suspend () -> Unit = {}
    private val events = EventBroadcaster<MeshEvent>()
    var subscription: ConnectionSubscription? = null
    var callbacksCleared = false
    var remoteSessions = emptySet<UUID>()
    var reauthenticated = emptySet<UUID>()
    override suspend fun hydrate() { calls += "hydrate"; beforeHydrate() }
    override suspend fun startMonitoring(options: MonitoringOptions) {
        calls += "start"
        monitoringStarts++
        beforeStart()
        subscription = inputs.connection.signals.subscribeTransitions()
        ownedJobs += inputs.connection.scope.launch { subscription!!.transitions.collect {} }
        ownedJobs += inputs.connection.scope.launch { awaitCancellation() }
    }
    override suspend fun initialSync(forceFullSync: Boolean): RuntimeSyncResult {
        calls += "sync"; syncForces += forceFullSync
        inputs.callbacks.channelSyncAttempted()
        beforeSync()
        if (syncResult == RuntimeSyncResult.Usable) inputs.callbacks.cleanChannelSync()
        return syncResult
    }
    override suspend fun remoteDisconnected(): Set<UUID> { calls += "remoteDisconnected"; return remoteSessions }
    override suspend fun reauthenticate(sessionIds: Set<UUID>) { reauthenticated = sessionIds }
    override suspend fun resetSyncState() { calls += "resetSync" }
    override suspend fun ensureListeners() { calls += "ensureListeners"; assertEquals(1, monitoringStarts) }
    override suspend fun tearDown(): TeardownReport {
        calls += "teardown"; teardowns++
        beforeTeardown()
        subscription?.close()
        ownedJobs.forEach { it.cancelAndJoin() }
        callbacksCleared = true
        events.finish()
        return TeardownReport(closeFailure?.let { listOf(TeardownIssue(LifecycleStage.FLUSH_RX, it)) }?.snapshot() ?: SnapshotList.empty())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class RuntimeFixture(val test: TestScope, parent: Job? = null, dispatcher: CoroutineDispatcher? = null) {
    val clock = TestClock(test.testScheduler)
    val preferences = TestPreferences()
    val last = LastConnectionStore(preferences, clock)
    val devices = TestDevices()
    val platform = TestPlatform()
    val radios = mutableListOf<TestRadio>()
    val links = mutableListOf<TestLink>()
    val services = mutableListOf<TestServices>()
    val diagnostics = mutableListOf<RuntimeDiagnostic>()
    val order = mutableListOf<String>()
    var resetCount = 0
    var warmed = 0
    var initializedDefaults = 0
    var lossCount = 0
    var autoCount = 0
    var syncedCount = 0
    val authFailures = mutableListOf<UUID>()
    var createRadio: () -> TestRadio = { TestRadio() }
    var onFactory: suspend (TestServices, FactoryOwnership) -> Unit = { _, _ -> }
    var onAvailable: suspend () -> Unit = {}
    var onRegistration: (LinkCallbacks) -> Unit = {}
    var onSubmission: () -> Unit = {}
    var warmUpFailure: Exception? = null
    var onLost: suspend () -> Unit = {}
    val manager = ConnectionManager(
        devices,
        object : RoomPersisting by rejectingRole(RoomPersisting::class.java) {
            override suspend fun resetAllRemoteNodeSessionConnections() { resetCount++; order += "global-reset" }
        },
        rejectingRole(ContactPersisting::class.java),
        object : ProcessRuntimeMaintenance {
            override suspend fun warmUp() {
                warmed++; assertTrue(devices.rows.isNotEmpty()); order += "warmUp"
                warmUpFailure?.let { throw it }
            }
            override suspend fun initializeDevicePreferences(device: DeviceDTO) { initializedDefaults++; order += "defaults" }
        },
        last, platform,
        RuntimeLinkFactory { target ->
            val radio = createRadio().also { radios += it }
            TestLink(radio, if (target is ConnectionTarget.WiFi) TransportType.WIFI else TransportType.BLUETOOTH).also {
                it.registrationHook = onRegistration
                links += it
            }
        },
        RuntimeServiceFactory { inputs, ownership ->
            val result = TestServices(inputs).also { services += it }
            ownership.register(result)
            onFactory(result, ownership)
            result
        },
        ConnectionObserver(
            onServicesAvailable = { order += "available"; onAvailable() },
            onConnectionLost = { lossCount++; onLost() },
            onAutoReconnectStarted = { autoCount++; order += "auto-loss" },
            onDeviceSynced = { syncedCount++ },
            onAuthenticationFailure = authFailures::add,
            onLastDeviceCleared = { order += "forgot" },
        ),
        RuntimeIssueReporter {
            diagnostics += it
            if (it is RuntimeDiagnostic.OperationSubmitted) onSubmission()
        }, clock,
        context = test.backgroundScope.coroutineContext + (parent ?: checkNotNull(test.backgroundScope.coroutineContext[Job])) +
            (dispatcher ?: EmptyCoroutineContext),
        configuration = SessionConfiguration(defaultTimeout = 1.0, clientIdentifier = "MCore"),
        jitter = { 0.0 },
    )
    suspend fun connect(target: ConnectionTarget = platform.target) {
        manager.connect(target)
        test.runCurrent()
        assertEquals(DeviceConnectionState.READY, manager.connectionState)
        assertEquals(1, radios.last().collectors)
    }
    suspend fun close() {
        manager.close()
        test.runCurrent()
        assertTrue(radios.all { it.collectors == 0 })
        assertTrue(services.all { it.ownedJobs.all(Job::isCompleted) })
    }
}

internal fun original(
    suite: String, name: String, signature: String = "()",
    assertions: suspend TestScope.() -> Unit,
): DynamicTest = DynamicTest.dynamicTest("$suite::$name$signature") { runTest(timeout = 10.seconds) { assertions() } }

internal fun nativeCase(name: String, assertions: suspend TestScope.() -> Unit): DynamicTest =
    DynamicTest.dynamicTest("WP-207::$name") { runTest(timeout = 10.seconds) { assertions() } }

internal suspend fun TestScope.withFixture(assertions: suspend RuntimeFixture.() -> Unit) {
    val fixture = RuntimeFixture(this)
    try { fixture.assertions() } finally { fixture.close() }
}

internal inline fun <reified T : Throwable> requiredCause(failure: Throwable): T {
    var current: Throwable? = failure
    val seen = mutableSetOf<Throwable>()
    while (current != null && seen.add(current)) {
        if (current is T) return current
        current = current.cause
    }
    throw AssertionError("Expected retained typed cause ${T::class.java.name}", failure)
}
