// AndroidOnly: WP-303 Test support: a protocol-speaking fake radio, runtime link/platform doubles and the Room-backed process harness.
// AndroidOnly: WP-214 Observe actual session pipeline entry and vary the firmware model in graph tests.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.state.AccessibilityAnnouncer
import com.meshcoreone.android.core.ble.BluetoothAvailability
import com.meshcoreone.android.core.connectivity.ConnectivityPlatform
import com.meshcoreone.android.core.connectivity.SystemLinkAdopter
import com.meshcoreone.android.core.connectivity.ble.BleLinkInspector
import com.meshcoreone.android.core.connectivity.ble.BleScanCoordinator
import com.meshcoreone.android.core.connectivity.ble.BleScanGateway
import com.meshcoreone.android.core.connectivity.ble.DiscoveredDevice
import com.meshcoreone.android.core.connectivity.ble.SystemLinkProbe
import com.meshcoreone.android.core.connectivity.pairing.BluetoothEndpoint
import com.meshcoreone.android.core.connectivity.pairing.DevicePairingDelegate
import com.meshcoreone.android.core.connectivity.pairing.DevicePairingService
import com.meshcoreone.android.core.connectivity.pairing.KnownEndpointStore
import com.meshcoreone.android.core.connectivity.pairing.RegisteredDevice
import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.NotificationPreferencesPort
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.contracts.notifications.DeliveredNotification
import com.meshcoreone.android.core.contracts.notifications.NotificationAuthorizationStatus
import com.meshcoreone.android.core.contracts.notifications.NotificationCategoryDefinition
import com.meshcoreone.android.core.contracts.notifications.NotificationDeliveryPort
import com.meshcoreone.android.core.contracts.domain.NotificationPostResult
import com.meshcoreone.android.core.contracts.notifications.NotificationRequest
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.model.DevicePlatform
import com.meshcoreone.android.core.model.NotificationPreferences
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.TransportType
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.DeviceCapabilities
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import com.meshcoreone.android.core.runtime.LinkCallbacks
import com.meshcoreone.android.core.runtime.ReconnectPolicy
import com.meshcoreone.android.core.runtime.RuntimeClock
import com.meshcoreone.android.core.runtime.RuntimeLink
import com.meshcoreone.android.core.runtime.RuntimeLinkFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/** Binds a method to the frozen Swift case id it proves; the JUnit XML names the method (same mechanism as core:data). */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
internal annotation class OriginalCase(val value: String, val disposition: String = "source-behavior")

internal val EPOCH: Instant = Instant.ofEpochSecond(1_704_067_200)

internal fun key(seed: Int): Bytes = Bytes(ByteArray(32) { seed.toByte() })

internal fun le32(value: Long): Bytes =
    Bytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array())

/**
 * The firmware's reply to a direct-message send: the expected-ACK code is derived exactly as the service derives it
 * (timestamp, attempt, text and the sender's public key), so a send is recognized rather than retried.
 */
internal fun sentReply(send: Bytes, senderKey: Bytes): Bytes = sentFrames(send, senderKey).first()

/** The sent reply followed by the delivery ACK push the destination's firmware would relay. */
internal fun sentAndDelivered(send: Bytes, senderKey: Bytes): List<Bytes> = sentFrames(send, senderKey)

private fun sentFrames(send: Bytes, senderKey: Bytes): List<Bytes> {
    val attempt = send[2]
    val timestamp = (0..3).fold(0u) { acc, index -> acc or (send[3 + index].toUInt() shl (8 * index)) }
    val text = String(send.toByteArray().copyOfRange(13, send.size), Charsets.UTF_8)
    val ack = com.meshcoreone.android.core.services.messaging.AckCodeBuilder.expectedAck(timestamp, attempt, text, senderKey)
    return listOf(Bytes.of(0x06, 0) + ack + le32(5_000), Bytes.of(0x82) + ack + le32(120))
}

/** A contact-list entry frame (response code 0x03): a chat contact reached by flood routing. */
internal fun contactFrame(publicKey: Bytes, name: String, lastModified: Long = 1): Bytes =
    Bytes.of(3) + publicKey + Bytes.of(1, 0, 0xff) + Bytes(ByteArray(64)) + Bytes.utf8(name).paddedOrTruncated(32) +
        le32(1) + le32(0) + le32(0) + le32(lastModified)

internal fun syntheticAddress(id: UUID): String {
    val bits = id.mostSignificantBits
    return (0..5).joinToString(":") { "%02X".format((bits ushr (8 * it)) and 0xFF) }
}

/**
 * A real-protocol radio over the merged `MeshCoreSession`: answers the startup handshake and the sync
 * commands with an empty mesh (no contacts, channels all unconfigured, no queued messages). Tests inspect
 * [sent] and hook [respond] to add behavior.
 */
internal class FirmwareRadio(private val publicKey: Bytes, private val nodeName: String = "TestNode") : MeshTransport {
    private var inbound = Channel<Bytes>(Channel.UNLIMITED)
    private var connected = false
    val sent = mutableListOf<Bytes>()
    var connects = 0
    var closes = 0
    var readers = 0
    var connectFailure: Exception? = null
    var firmwareVersion: UByte = 9u
    var modelName: String = "T-Deck"
    var pipelinedReadsSupported = false
    var pipelinedReadChecks = 0
        private set

    override suspend fun supportsPipelinedReads(): Boolean {
        pipelinedReadChecks++
        return pipelinedReadsSupported
    }

    /** When set, [connect] suspends until the gate opens (an in-flight connection attempt). */
    var connectGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** Return a frame list to answer a command; null falls through to the default firmware behavior. */
    var respond: (Bytes) -> List<Bytes>? = { null }

    /** Contacts the radio reports on a contact-list request (a full sync deletes local contacts not listed here). */
    val knownContacts = mutableListOf<Bytes>()

    override suspend fun connect() {
        connectGate?.await()
        connectFailure?.let { throw it }
        if (!connected) { inbound = Channel(Channel.UNLIMITED); connected = true; connects++ }
    }

    override suspend fun disconnect() {
        if (connected) { connected = false; closes++; inbound.close() }
    }

    override suspend fun isConnected(): Boolean = connected

    override suspend fun receivedData(): Flow<Bytes> {
        val stream = inbound
        return flow {
            readers++
            try { for (frame in stream) emit(frame) } finally { readers-- }
        }
    }

    /** A push frame from the radio (for example an incoming-message notification). */
    fun push(frame: Bytes) { inbound.trySend(frame) }

    override suspend fun send(data: Bytes) {
        check(connected) { "send while disconnected" }
        sent += data
        val frames = respond(data) ?: defaultResponse(data)
        for (frame in frames) inbound.send(frame)
    }

    private fun defaultResponse(data: Bytes): List<Bytes> = when (data[0].toInt() and 255) {
        0x01 -> listOf(selfPacket())
        0x16 -> listOf(devicePacket())
        0x3b -> listOf(Bytes.of(0x19, 0, 0))
        0x05 -> listOf(Bytes.of(9) + le32(EPOCH.epochSecond))
        0x06 -> listOf(Bytes.of(0))
        0x04 -> listOf(Bytes.of(2) + le32(knownContacts.size.toLong())) + knownContacts + listOf(Bytes.of(4) + le32(0))
        0x0a -> listOf(Bytes.of(10))
        0x1f -> listOf(Bytes.of(1, 2))
        0x3c -> listOf(Bytes.of(0))
        0x36 -> listOf(Bytes.of(0))
        0x14 -> listOf(Bytes.of(0x0c, 0x74, 0x0e))
        0x17 -> listOf(Bytes.of(0x0f))
        0x1e -> listOf(knownContacts.firstOrNull { it.hexString.contains(data.hexString.drop(2)) } ?: Bytes.of(1, 2))
        else -> throw AssertionError("Unexpected command ${data.hexString}")
    }

    private fun selfPacket(): Bytes =
        Bytes.of(5, 1, 22, 22) + publicKey + le32(0) + le32(0) + Bytes.of(0, 0, 2, 0) +
            le32(915_000) + le32(250_000) + Bytes.of(10, 5) + Bytes.utf8(nodeName)

    private fun devicePacket(): Bytes =
        Bytes.of(13, firmwareVersion.toInt(), 50, 8) + le32(0) + Bytes.utf8("01 Jan 2025").paddedOrTruncated(12) +
            Bytes.utf8(modelName).paddedOrTruncated(40) + Bytes.utf8("v1.13.0").paddedOrTruncated(20) + Bytes.of(0)
}

internal class HarnessLink(val radio: FirmwareRadio, override val type: TransportType, val target: ConnectionTarget) : RuntimeLink {
    override val transport: MeshTransport get() = radio
    var callbacks: LinkCallbacks? = null
    var live: SessionToken? = null
    val policy = ReconnectPolicy()
    override fun register(callbacks: LinkCallbacks): AutoCloseable {
        this.callbacks = callbacks
        return AutoCloseable { this.callbacks = null }
    }
    override suspend fun configure(capabilities: DeviceCapabilities, platform: DevicePlatform) = Unit
    override suspend fun setSessionLive(token: SessionToken?) { live = token }
    override suspend fun recordBondVerification(deviceId: UUID, at: Instant) = policy.recordBondVerification(deviceId, at)
    override suspend fun clearBondVerification(deviceId: UUID) = policy.clearBondVerification(deviceId)
    override suspend fun mayRefreshBond(deviceId: UUID): Boolean = live != null
}

/** Creates one radio + link per connect; [identity] chooses which radio identity the target reaches. */
internal class HarnessLinks(private val identity: (ConnectionTarget) -> Bytes) : RuntimeLinkFactory {
    val radios = mutableListOf<FirmwareRadio>()
    val links = mutableListOf<HarnessLink>()
    var configureRadio: (FirmwareRadio) -> Unit = {}

    override fun create(target: ConnectionTarget): RuntimeLink {
        val radio = FirmwareRadio(identity(target)).also(configureRadio).also { radios += it }
        val type = if (target is ConnectionTarget.WiFi) TransportType.WIFI else TransportType.BLUETOOTH
        return HarnessLink(radio, type, target).also { links += it }
    }
}

internal class HarnessPairingService : DevicePairingService {
    override var delegate: DevicePairingDelegate? = null
    override val isSessionActive: Boolean = true
    override val registeredDeviceCount: Int get() = registered.size
    override val hasSystemPairingRegistry: Boolean = true
    override val supportsSystemRename: Boolean = false
    val registered = linkedMapOf<UUID, String>()
    val removed = mutableListOf<UUID>()
    var removeCalls = 0
    var removeFailure: Exception? = null
    var discovery: suspend () -> UUID = {
        throw com.meshcoreone.android.core.connectivity.pairing.DevicePairingError.Cancelled()
    }
    override suspend fun activate() = Unit
    override suspend fun discoverDevice(): UUID = discovery()
    override fun isDeviceConnectable(id: UUID): Boolean = true
    override fun registeredDeviceInfos(): List<RegisteredDevice> = registered.map { RegisteredDevice(it.key, it.value) }
    override suspend fun removeDevice(id: UUID) {
        // Like the companion registry: an unknown association is a no-op with no platform call.
        if (id !in registered) return
        removeCalls += 1
        removeFailure?.let { throw it }
        removed += id
        registered.remove(id)
    }
    override suspend fun renameDevice(id: UUID) = Unit
    override suspend fun clearStaleRegistrations() = Unit
}

internal class SyntheticEndpoints : KnownEndpointStore {
    override suspend fun endpoint(deviceId: UUID): BluetoothEndpoint? = BluetoothEndpoint(deviceId, syntheticAddress(deviceId), null)
    override suspend fun remember(endpoint: BluetoothEndpoint) = Unit
    override suspend fun forget(deviceId: UUID) = Unit
}

internal object NoSystemLinks : SystemLinkProbe {
    override suspend fun isDeviceConnectedToSystem(deviceId: UUID): Boolean = false
    override suspend fun systemConnectedDeviceIds(): Set<UUID> = emptySet()
}

internal object NoScanGateway : BleScanGateway {
    override fun startScan(onDevice: (DiscoveredDevice) -> Unit, onFailure: (Throwable) -> Unit) = Unit
    override fun stopScan() = Unit
}

/** Notification delivery that records posts and reports a denied permission (the WP-401 adapter is unmerged). */
internal class RecordingDelivery : NotificationDeliveryPort {
    val posted = mutableListOf<NotificationRequest>()
    var badge: Long = 0
    override suspend fun authorizationStatus(): NotificationAuthorizationStatus = NotificationAuthorizationStatus.DENIED
    override suspend fun requestAuthorization(): Boolean = false
    override suspend fun registerCategories(categories: SnapshotList<NotificationCategoryDefinition>) = Unit
    override suspend fun post(request: NotificationRequest): NotificationPostResult { posted += request; return NotificationPostResult.Posted }
    override suspend fun setBadgeCount(count: Long) { badge = count }
    override suspend fun deliveredNotifications(): SnapshotList<DeliveredNotification> = SnapshotList.empty()
    override suspend fun removeDelivered(ids: SnapshotList<NotificationId>) = Unit
}

internal class FixedNotificationPreferences : NotificationPreferencesPort {
    private val state = MutableStateFlow(
        NotificationPreferences(
            contactMessagesEnabled = true, channelMessagesEnabled = true, roomMessagesEnabled = true,
            newContactDiscoveredEnabled = true, discoveryContactEnabled = true, discoveryRepeaterEnabled = true,
            discoveryRoomEnabled = true, reactionNotificationsEnabled = true, soundEnabled = false,
            badgeEnabled = true, lowBatteryEnabled = true,
        ),
    )
    override val preferences: StateFlow<NotificationPreferences> = state
    override suspend fun update(preferences: NotificationPreferences) { state.value = preferences }
}

internal fun newConnectivity(
    pairing: HarnessPairingService = HarnessPairingService(),
    onForeground: (Boolean) -> Unit = {},
): ConnectivityPlatform = ConnectivityPlatform(
    pairing, BleLinkInspector(NoSystemLinks, { null }) { BluetoothAvailability.Ready }, SyntheticEndpoints(),
    SystemLinkAdopter { false }, onForegroundChanged = onForeground,
)

internal fun newScans(): BleScanCoordinator = BleScanCoordinator(NoScanGateway)

internal fun openMemoryDatabase(): MeshCoreDatabase {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val immediate = java.util.concurrent.Executor { it.run() }
    return Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
        .allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
}

/** Virtual clock tied to a test scheduler's current time. */
internal class SchedulerClock(private val currentTimeMillis: () -> Long) : RuntimeClock {
    override val elapsed: Duration get() = currentTimeMillis().milliseconds
    override val instant: Instant get() = EPOCH.plusMillis(currentTimeMillis())
    override suspend fun sleep(duration: Duration) = delay(duration)
}

internal object SilentAnnouncements : AccessibilityAnnouncer {
    override fun announce(message: com.meshcoreone.android.core.ui.UiText) = Unit
}
