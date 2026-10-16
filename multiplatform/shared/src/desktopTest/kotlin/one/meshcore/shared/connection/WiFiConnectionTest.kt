package one.meshcore.shared.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.backup.ConnectionMethod
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.identity.DeviceIdentity
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.persistence.seedDevice
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.transport.LinkDropReason
import one.meshcore.shared.transport.MeshLinkConnectException
import one.meshcore.shared.transport.TcpTransportException
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The WiFi connect ceremony, heartbeat gating, and WiFi reconnect schedule over the fake WiFi link and
 * fake session, against a real desktop Room database. The exact backoff *values* are proven in
 * [WiFiReconnectPolicyTest]; here a millisecond-scaled policy keeps behaviour fast and the cooldown is
 * removed so a reconnect run starts immediately.
 */
class WiFiConnectionTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var prefs: InMemoryPreferencesStore

    private val publicKey = ByteArray(32) { 0x01 }
    private val derivedId get() = DeviceIdentity.deriveUuid(publicKey)
    private val fastWiFiPolicy = WiFiReconnectPolicy(
        cooldown = 0.seconds,
        window = 10.seconds,
        baseBackoff = 20.milliseconds,
        maxBackoff = 100.milliseconds,
    )

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-wifi", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        prefs = InMemoryPreferencesStore()
    }

    @AfterTest
    fun tearDown() {
        scope.cancelAndJoinBlocking()
        db.close()
        dbFile.delete()
    }

    @Test
    fun connectViaWiFiReachesReadyAndPersistsReachability() = runBlocking {
        val store = LastConnectionStore(prefs)
        val manager =
            manager(session = { FakeMeshSession(selfInfo = defaultSelfInfo(publicKey)) }, lastConnectionStore = store)

        manager.connectViaWiFi(HOST, PORT)

        assertEquals(ConnectionState.Ready(handle()), manager.state.value)
        assertEquals(TransportType.WIFI, manager.currentTransportType)

        val device = db.deviceDao().getById(derivedId)!!
        assertContains(device.connectionMethods, ConnectionMethod.WiFi(HOST, PORT, null))
        assertEquals(derivedId, store.deviceId())
        assertEquals(handle(), store.handle())
    }

    @Test
    fun connectViaWiFiIgnoresAnOpenBleCircuitBreaker() = runBlocking {
        val bleLink = FakeMeshLink().apply { failConnectWith = MeshLinkConnectException("boom") }
        val manager = manager(
            session = { FakeMeshSession(selfInfo = defaultSelfInfo(publicKey)) },
            bleLink = bleLink,
        )
        assertFailsWith<MeshLinkConnectException> { manager.connect("ble-handle") }

        manager.connectViaWiFi(HOST, PORT)

        assertEquals(ConnectionState.Ready(handle()), manager.state.value)
    }

    @Test
    fun connectViaWiFiRejectsABlankHost() = runBlocking {
        val manager = manager(session = { FakeMeshSession(selfInfo = defaultSelfInfo(publicKey)) })
        assertIs<TcpTransportException.InvalidHost>(
            assertFailsWith<TcpTransportException> { manager.connectViaWiFi("   ", PORT) },
        )
        assertEquals(ConnectionState.Disconnected, manager.state.value)
    }

    @Test
    fun connectViaWiFiTrimsTheHostBeforePersistingReachability() = runBlocking {
        val manager = manager(session = { FakeMeshSession(selfInfo = defaultSelfInfo(publicKey)) })
        manager.connectViaWiFi("  $HOST  ", PORT)
        val device = db.deviceDao().getById(derivedId)!!
        assertContains(device.connectionMethods, ConnectionMethod.WiFi(HOST, PORT, null))
        assertEquals(ConnectionState.Ready("$HOST:$PORT"), manager.state.value)
    }

    @Test
    fun persistPreservesBluetoothWhenConnectingOverWiFi() = runBlocking {
        // A radio already reachable over BLE (its row pre-seeded) must keep that descriptor after a
        // WiFi connect adds the WiFi one — the replace-by-transport-type merge through persistDevice.
        val bluetooth = ConnectionMethod.Bluetooth(Uuid.normalized("C0000000-0000-0000-0000-000000000003"), null)
        db.deviceDao().upsert(deviceEntity(derivedId, listOf(bluetooth)))

        manager(session = { FakeMeshSession(selfInfo = defaultSelfInfo(publicKey)) }).connectViaWiFi(HOST, PORT)

        val methods = db.deviceDao().getById(derivedId)!!.connectionMethods
        assertContains(methods, bluetooth)
        assertContains(methods, ConnectionMethod.WiFi(HOST, PORT, null))
    }

    @Test
    fun heartbeatProbesWhenOperationalAndPausesDuringSync() = runBlocking {
        val session = FakeMeshSession(selfInfo = defaultSelfInfo(publicKey))
        val gate = CompletableDeferred<Unit>()
        session.contactsGate = gate
        val links = mutableListOf<FakeWiFiLink>()
        val manager = manager(session = { session }, wifiLinks = links)

        val connectJob = scope.launch { manager.connectViaWiFi(HOST, PORT) }

        awaitCondition { manager.state.value is ConnectionState.Syncing }
        links.single().heartbeat() // during sync — must be skipped
        assertEquals(0, session.getTimeCount)

        gate.complete(Unit)
        awaitCondition { manager.state.value == ConnectionState.Ready(handle()) }
        connectJob.join()
        // Promotion reads the clock once to check the radio's drift; the heartbeat adds its own probe.
        val afterPromotion = session.getTimeCount
        links.single().heartbeat() // operational — must probe
        assertEquals(afterPromotion + 1, session.getTimeCount)
    }

    @Test
    fun heartbeatFailedGetTimeDropsTheLinkAndReconnects() = runBlocking {
        val session = FakeMeshSession(selfInfo = defaultSelfInfo(publicKey))
        val links = mutableListOf<FakeWiFiLink>()
        val manager = manager(session = { session }, wifiLinks = links)

        manager.connectViaWiFi(HOST, PORT)
        assertEquals(ConnectionState.Ready(handle()), manager.state.value)

        session.getTimeFailures += Exception("timeout")
        links.single().heartbeat()

        awaitCondition { links.size == 2 && manager.state.value == ConnectionState.Ready(handle()) }
    }

    @Test
    fun heartbeatPausesWhileAChannelRetryIsActive() {
        assertEquals(false, shouldPauseWiFiHeartbeat(ConnectionState.Ready(handle()), channelRetryActive = false))
        assertEquals(true, shouldPauseWiFiHeartbeat(ConnectionState.Ready(handle()), channelRetryActive = true))
        assertEquals(true, shouldPauseWiFiHeartbeat(ConnectionState.Syncing(handle()), channelRetryActive = false))
    }

    @Test
    fun wifiDropArmsWiFiReconnectAndReachesReadyOnAFreshLink() = runBlocking {
        val links = mutableListOf<FakeWiFiLink>()
        val manager = manager(session = { FakeMeshSession(selfInfo = defaultSelfInfo(publicKey)) }, wifiLinks = links)

        manager.connectViaWiFi(HOST, PORT)
        links.first().drop(LinkDropReason.CONNECTION_LOST)

        awaitCondition { links.size == 2 && manager.state.value == ConnectionState.Ready(handle()) }
    }

    @Test
    fun wifiReconnectBacksOffAcrossFailedAttempts() = runBlocking {
        var attempt = 0
        val links = mutableListOf<FakeWiFiLink>()
        val manager = manager(
            session = { FakeMeshSession(selfInfo = defaultSelfInfo(publicKey)) },
            wifiLinkFactory = { h, p, hb ->
                attempt++
                FakeWiFiLink(h, p, hb).apply {
                    if (attempt == 2 || attempt == 3) failConnectWith = MeshLinkConnectException("boom")
                    links += this
                }
            },
        )

        manager.connectViaWiFi(HOST, PORT)
        links.first().drop(LinkDropReason.CONNECTION_LOST)

        awaitCondition { attempt == 4 && manager.state.value == ConnectionState.Ready(handle()) }
    }

    private fun manager(
        session: (one.meshcore.shared.transport.MeshLink) -> FakeMeshSession,
        lastConnectionStore: LastConnectionStore? = null,
        wifiLinks: MutableList<FakeWiFiLink>? = null,
        wifiLinkFactory: ((String, Int, suspend () -> Unit) -> FakeWiFiLink)? = null,
        bleLink: FakeMeshLink = FakeMeshLink(),
    ): ConnectionManager {
        val factory = wifiLinkFactory ?: { h, p, hb -> FakeWiFiLink(h, p, hb).also { wifiLinks?.add(it) } }
        return ConnectionManager(
            database = db,
            scope = scope,
            linkFactory = { bleLink },
            sessionFactory = { session(it) },
            nowEpochSeconds = FIXED_CLOCK,
            ports = ConnectionManagerPorts(
                wifiPolicy = fastWiFiPolicy,
                wifiLinkFactory = factory,
                lastConnectionStore = lastConnectionStore,
                connectRetryPolicy = ConnectRetryPolicy(baseBackoff = 1.milliseconds),
            ),
        )
    }

    private fun handle(): String = "$HOST:$PORT"

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(TIMEOUT_MS) {
            while (!condition()) delay(POLL_MS)
        }
    }

    private fun deviceEntity(id: Uuid, methods: List<ConnectionMethod>) =
        seedDevice(id, Uuid.normalized("D0000000-0000-0000-0000-000000000004"), publicKey, methods)

    private companion object {
        const val HOST = "10.0.0.5"
        const val PORT = 4403
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
        val FIXED_CLOCK: () -> Double = { 1_000.0 }
    }
}
