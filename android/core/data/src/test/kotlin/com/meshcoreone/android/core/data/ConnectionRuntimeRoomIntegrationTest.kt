// PortedFrom: MC1Services/Tests/MC1ServicesTests/ConnectRadioIDResolutionTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Connection/ConnectionManager+Lifecycle.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: test-only factory over actual merged Room roles and a real JVM session.
package com.meshcoreone.android.core.data.runtime

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.data.repository.RepositoryTest
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.model.DeviceCapabilities
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import com.meshcoreone.android.core.runtime.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionRuntimeRoomIntegrationTest : RepositoryTest() {
    @Test
    fun changedEndpointPreservesRestoredPartitionAndRealContactMessagePendingTriple() = runTest(scheduler) {
        val h = Harness(this)
        try {
            val original = RadioId(UUID.fromString("447175B7-557D-4F48-B671-4B2CEE2DC932"))
            val restored = DeviceDTO(radioId = original, publicKey = KEY, nodeName = "Restored", isActive = false)
            store.saveDevice(restored)
            h.manager.connect(h.target)
            assertEquals(original, h.manager.connectedDevice!!.radioId)
            val contact = ContactDTO(radioId = original, publicKey = Bytes(ByteArray(32) { 7 }), name = "Recipient", lastHeardTimestamp = null)
            store.saveContact(contact)
            val message = MessageDTO(
                radioId = original, contactID = contact.id, text = "queued before reconnect",
                timestamp = 1_704_067_200u, createdAt = AT, status = MessageStatus.PENDING,
            )
            store.saveMessage(message)
            val pending = PendingSendDTO(
                UUID.randomUUID(), original, message.id, PendingSendKind.DM, contact.id, null, false,
                message.text, message.timestamp, null, 0, AT, attemptCount = 0,
            )
            store.insertPendingSendAssigningSequence(pending)
            val legacy = pending.copy(id = UUID.randomUUID(), messageID = UUID.randomUUID(), attemptCount = null)
            store.insertPendingSendAssigningSequence(legacy)
            h.manager.disconnect(RuntimeDisconnectReason.WIFI_RECONNECT_PREP)
            val next = h.target.copy(deviceId = UUID.randomUUID())
            h.manager.connect(next)
            assertEquals(original, h.manager.connectedDevice!!.radioId)
            assertEquals(listOf(message.id), h.hydrated.last().map { it.messageID })
            assertEquals(0L, h.hydrated.last().single().attemptCount)
            assertTrue(store.fetchPendingSends(original).none { it.id == legacy.id })
            assertEquals(MessageStatus.PENDING, store.fetchMessage(EntityKey(original, message.id))!!.status)
            assertNotNull(store.fetchContact(EntityKey(original, contact.id)))
            assertNull(store.fetchDevice(restored.id)); assertEquals(original, store.fetchDevice(next.deviceId)!!.radioId)
            assertEquals(1, h.radios.first().closes); assertEquals(0, h.radios.first().readers)
            assertEquals(1, h.radios.last().readers)
        } finally { h.manager.close() }
        assertEquals(1, store.fetchPendingSends(RadioId(UUID.fromString("447175B7-557D-4F48-B671-4B2CEE2DC932"))).size)
    }

    @Test
    fun processStartupGloballyResetsStaleSessionsIncludingOrphanRadioWithoutTouchingPermissionsOrNativeSortDates() = runTest(scheduler) {
        val h = Harness(this)
        try {
            val ordinary = RadioId(UUID.randomUUID()); val orphan = RadioId(UUID.randomUUID())
            store.saveDevice(DeviceDTO(radioId = ordinary, publicKey = KEY, nodeName = "Known"))
            val known = RemoteNodeSessionDTO(radioId = ordinary, publicKey = KEY, name = "Known room",
                role = RemoteNodeRole.ROOM_SERVER, isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
            val orphaned = known.copy(id = UUID.randomUUID(), radioId = orphan, name = "Orphan room", permissionLevel = RoomPermissionLevel.READ_WRITE)
            store.saveRemoteNodeSessionDTO(known); store.saveRemoteNodeSessionDTO(orphaned)
            val preserved = MessageDTO(radioId = ordinary, text = "native sort date", timestamp = 1_704_067_200u,
                createdAt = AT, sortDate = AT.minusSeconds(99), status = MessageStatus.DELIVERED)
            store.saveMessage(preserved)
            h.preferences[PersistenceKeys.USER_EXPLICITLY_DISCONNECTED] = RuntimePreferenceValue.Flag(true)
            h.manager.activate(); h.manager.activate()
            assertFalse(store.fetchRemoteNodeSession(EntityKey(ordinary, known.id))!!.isConnected)
            assertFalse(store.fetchRemoteNodeSession(EntityKey(orphan, orphaned.id))!!.isConnected)
            assertEquals(RoomPermissionLevel.ADMIN, store.fetchRemoteNodeSession(EntityKey(ordinary, known.id))!!.permissionLevel)
            assertEquals(RoomPermissionLevel.READ_WRITE, store.fetchRemoteNodeSession(EntityKey(orphan, orphaned.id))!!.permissionLevel)
            assertEquals(preserved.sortDate, store.fetchMessage(EntityKey(ordinary, preserved.id))!!.sortDate)
            assertTrue(h.radios.isEmpty()); assertEquals(1, h.platformActivations)
        } finally { h.manager.close() }
    }

    @Test
    fun actualProcessStoreSurvivesTwoPhysicalGenerationsAndRuntimeClose() = runTest(scheduler) {
        val h = Harness(this)
        h.manager.connect(h.target)
        val first = h.manager.connectedDevice!!
        h.manager.disconnect(RuntimeDisconnectReason.WIFI_RECONNECT_PREP)
        h.manager.connect(h.target.copy(deviceId = UUID.randomUUID()))
        val latest = h.manager.connectedDevice!!
        assertEquals(first.radioId, latest.radioId)
        h.manager.close()
        assertEquals(latest, store.fetchDevice(latest.id))
        assertTrue(h.radios.all { it.closes == 1 && it.readers == 0 })
        assertTrue(h.scopes.all { !it.isActive })
        assertTrue(issues.isEmpty())
    }

    @Test
    fun ghostReconciliationExecutesTheActualRepositoryAlgorithmWithoutRekeyingUnrelatedPartitions() = runTest(scheduler) {
        val h = Harness(this)
        try {
            h.manager.connect(h.target)
            val original = h.manager.connectedDevice!!
            val ghostRadio = RadioId(UUID.randomUUID()); val newKey = Bytes(ByteArray(32) { 9 })
            val ghost = DeviceDTO(radioId = ghostRadio, publicKey = newKey, nodeName = "Ghost", isActive = false)
            store.saveDevice(ghost)
            val independent = ContactDTO(radioId = original.radioId, publicKey = Bytes(ByteArray(32) { 17 }), name = "Original partition", lastHeardTimestamp = null)
            store.saveContact(independent)
            val actual = store.reconcileGhostIdentity(original.id, newKey)
            assertEquals(ghostRadio, actual); assertEquals(ghostRadio, store.fetchDevice(original.id)!!.radioId)
            assertNull(store.fetchDevice(ghost.id))
            assertNotNull(store.fetchContact(EntityKey(original.radioId, independent.id)))
            assertNull(store.fetchContact(EntityKey(ghostRadio, independent.id)))
        } finally { h.manager.close() }
    }

    private inner class Harness(test: TestScope) {
        val target = ConnectionTarget.Bluetooth(BluetoothPairingHandle(BluetoothAddress("C0:00:00:00:00:02"), null), UUID.randomUUID())
        val preferences = mutableMapOf<String, RuntimePreferenceValue>()
        val hydrated = mutableListOf<SnapshotList<PendingSendDTO>>()
        val radios = mutableListOf<Radio>()
        val scopes = mutableListOf<Job>()
        var platformActivations = 0
        private val runtimeClock = object : RuntimeClock {
            override val elapsed get() = scheduler.currentTime.milliseconds
            override val instant get() = AT.plusMillis(scheduler.currentTime)
            override suspend fun sleep(duration: Duration) = delay(duration)
        }
        private val preferenceRole = object : ProcessConnectionPreferences {
            override suspend fun read() = RuntimePreferenceSnapshot(preferences)
            override suspend fun update(transform: (MutableMap<String, RuntimePreferenceValue>) -> Unit): RuntimePreferenceSnapshot {
                transform(preferences); return read()
            }
        }
        val manager = ConnectionManager(
            store, store, store,
            object : ProcessRuntimeMaintenance {
                override suspend fun warmUp() = store.warmUp()
                override suspend fun initializeDevicePreferences(device: DeviceDTO) = Unit
            },
            LastConnectionStore(preferenceRole, runtimeClock),
            object : ConnectionPlatform {
                override val hasSystemPairingRegistry = false
                override val registryActive = false
                override suspend fun activate() { platformActivations++ }
                override suspend fun foreground(active: Boolean) = Unit
                override suspend fun state(target: ConnectionTarget) = PlatformLinkState(false, false, false, null, "idle", false)
                override suspend fun isRegistered(deviceId: UUID) = true
                override suspend fun targetForDevice(deviceId: UUID) = target.copy(deviceId = deviceId)
                override suspend fun adoptSystemLink(target: ConnectionTarget) = false
                override fun classifyFailure(failure: Throwable): LinkFailure? = failure as? LinkFailure
            },
            RuntimeLinkFactory {
                val radio = Radio().also(radios::add)
                object : RuntimeLink {
                    override val transport = radio
                    override val type = TransportType.BLUETOOTH
                    override fun register(callbacks: LinkCallbacks) = AutoCloseable {}
                    override suspend fun configure(capabilities: DeviceCapabilities, platform: DevicePlatform) = Unit
                    override suspend fun setSessionLive(token: SessionToken?) = Unit
                    override suspend fun recordBondVerification(deviceId: UUID, at: Instant) = Unit
                    override suspend fun clearBondVerification(deviceId: UUID) = Unit
                    override suspend fun mayRefreshBond(deviceId: UUID) = false
                }
            },
            RuntimeServiceFactory { inputs, ownership ->
                scopes += checkNotNull(inputs.connection.scope.coroutineContext[Job])
                val service = object : RuntimeServices {
                    override val token = inputs.connection.token
                    private var monitor: Job? = null
                    override suspend fun hydrate() { hydrated += store.fetchPendingSends(token.radioId) }
                    override suspend fun startMonitoring(options: MonitoringOptions) {
                        check(monitor == null)
                        monitor = inputs.connection.scope.launch { awaitCancellation() }
                    }
                    override suspend fun initialSync(forceFullSync: Boolean) = RuntimeSyncResult.Usable
                    override suspend fun remoteDisconnected() = emptySet<UUID>()
                    override suspend fun reauthenticate(sessionIds: Set<UUID>) = Unit
                    override suspend fun resetSyncState() = Unit
                    override suspend fun ensureListeners() { check(monitor?.isActive == true) }
                    override suspend fun tearDown(): TeardownReport {
                        monitor?.cancelAndJoin()
                        return TeardownReport(SnapshotList.empty())
                    }
                }
                ownership.register(service)
                service
            },
            ConnectionObserver({}, {}, {}, {}, {}, {}),
            RuntimeIssueReporter { event -> if (event is RuntimeDiagnostic.Failure) throw AssertionError(event.operation, event.cause) },
            runtimeClock, context = test.backgroundScope.coroutineContext,
            configuration = SessionConfiguration(defaultTimeout = 1.0, clientIdentifier = "MCore"),
            jitter = { 0.0 },
        )
    }

    private class Radio : MeshTransport {
        private var connected = false
        private var incoming = Channel<Bytes>(Channel.UNLIMITED)
        var closes = 0
        var readers = 0
        override suspend fun isConnected() = connected
        override suspend fun connect() { if (!connected) { connected = true; incoming = Channel(Channel.UNLIMITED) } }
        override suspend fun disconnect() { if (connected) { connected = false; closes++; incoming.close() } }
        override suspend fun receivedData(): Flow<Bytes> {
            val source = incoming
            return flow {
                readers++
                try { for (packet in source) emit(packet) } finally { readers-- }
            }
        }
        override suspend fun send(data: Bytes) {
            val response = when (data[0].toInt() and 255) {
                1 -> {
                    assertEquals(Bytes.fromHex("01032020202020204d436f7265"), data)
                    Bytes.of(5, 1, 22, 22) + KEY + le32(0) + le32(0) + Bytes.of(0, 0, 2, 0) +
                        le32(915_000) + le32(250_000) + Bytes.of(10, 5) + Bytes.utf8("Room runtime")
                }
                0x16 -> Bytes.of(13, 9, 50, 8) + le32(0) + Bytes.utf8("01 Jan 2025").paddedOrTruncated(12) +
                    Bytes.utf8("T-Deck").paddedOrTruncated(40) + Bytes.utf8("v1.13.0").paddedOrTruncated(20) + Bytes.of(0)
                0x3b -> Bytes.of(0x19, 0, 0)
                5 -> Bytes.of(9) + le32(AT.epochSecond)
                6 -> Bytes.of(0)
                else -> throw AssertionError("Unexpected runtime packet ${data.hexString}")
            }
            incoming.send(response)
        }
    }

    companion object {
        private val KEY = Bytes(ByteArray(32) { 0xc3.toByte() })
        private val AT = Instant.ofEpochSecond(1_704_067_200)
        private fun le32(value: Long) = Bytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array())
    }
}
