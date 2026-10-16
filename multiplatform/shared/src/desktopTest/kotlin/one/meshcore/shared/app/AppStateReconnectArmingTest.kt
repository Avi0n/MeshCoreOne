package one.meshcore.shared.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.connection.ConnectionIntentStore
import one.meshcore.shared.connection.ConnectionManager
import one.meshcore.shared.connection.ConnectionManagerPorts
import one.meshcore.shared.connection.ConnectionState
import one.meshcore.shared.connection.ConnectionUiState
import one.meshcore.shared.connection.DisconnectReason
import one.meshcore.shared.connection.FakeMeshLink
import one.meshcore.shared.connection.FakeMeshSession
import one.meshcore.shared.connection.LastConnectionStore
import one.meshcore.shared.connection.ReconnectPolicy
import one.meshcore.shared.connection.armBleReconnectIfWanted
import one.meshcore.shared.connection.connect
import one.meshcore.shared.connection.defaultSelfInfo
import one.meshcore.shared.connection.disconnect
import one.meshcore.shared.connection.isBleReconnectInProgress
import one.meshcore.shared.connection.lastDisconnectDiagnostic
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.transport.LinkDropReason
import one.meshcore.shared.transport.MeshLinkConnectException
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The auto-reconnect arms that sit outside the drop path, mirroring the two Swift
 * `startReconnectionWatchdog()` calls a drop never reaches: the launch auto-reconnect's failure catch and
 * the `appDidBecomeActive` re-arm while disconnected. Without them a BLE radio the user still wants stays
 * disconnected with no loop running, because Android has no OS-level pending connect to fall back on.
 *
 * A real background scope is used, as in `ConnectionManagerTest`, because Room dispatches to
 * `Dispatchers.IO`; assertions poll the observable state.
 */
class AppStateReconnectArmingTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var prefs: InMemoryPreferencesStore

    private val fastPolicy = ReconnectPolicy(
        ReconnectPolicy.Config(baseBackoff = 20.milliseconds, maxBackoff = 100.milliseconds),
    )

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-rearm", ".db").also { it.delete() }
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
    fun foregroundReArmsTheReconnectLoopForAStillWantedBleRadio() = runBlocking {
        var attempt = 0
        val manager = manager {
            attempt++
            // Only the first connect establishes a link; every later attempt fails the way an
            // out-of-range radio does, so the armed loop stays visibly Reconnecting.
            FakeMeshLink().apply { if (attempt >= 2) failConnectWith = MeshLinkConnectException("out of range") }
        }
        val viewModel = viewModel(manager)

        manager.connect(DEVICE)
        // A resync failure preserves the connection intent (Swift `disconnect(reason:)` leaves
        // `.wantsConnection` for it), so the radio is still wanted with no loop running.
        manager.disconnect(DisconnectReason.RESYNC_FAILED)
        assertEquals(ConnectionState.Disconnected, manager.state.value)

        viewModel.onEnterForeground()

        awaitCondition { manager.state.value is ConnectionState.Reconnecting }
    }

    @Test
    fun foregroundDropsABleCompanionWhoseGattDiedSilently() = runBlocking {
        val live = FakeMeshLink()
        var attempt = 0
        val manager = manager {
            attempt++
            if (attempt == 1) {
                live
            } else {
                FakeMeshLink().apply {
                    failConnectWith = MeshLinkConnectException("gatt dead")
                }
            }
        }
        val viewModel = viewModel(manager)
        manager.connect(DEVICE)
        assertTrue(manager.state.value is ConnectionState.Ready)
        live.silentlyDead = true
        viewModel.onEnterForeground()
        awaitCondition { manager.state.value !is ConnectionState.Ready }
    }

    @Test
    fun foregroundRetriesABondLostBleRadio() = runBlocking {
        val links = mutableListOf<FakeMeshLink>()
        val manager = manager { FakeMeshLink().also { links += it } }
        val viewModel = viewModel(manager)

        manager.connect(DEVICE)
        links.first().drop(LinkDropReason.BOND_LOST)
        awaitCondition {
            manager.state.value == ConnectionState.BondLost(DEVICE) ||
                manager.state.value == ConnectionState.Ready(DEVICE)
        }

        viewModel.onEnterForeground()

        awaitCondition { links.size >= 2 && manager.state.value == ConnectionState.Ready(DEVICE) }
    }

    @Test
    fun activateJoinsAnInFlightBleReconnectInsteadOfStartingASecondConnect() = runBlocking {
        val first = FakeMeshLink()
        val reconnectGate = CompletableDeferred<Unit>()
        val reconnectLinks = mutableListOf<FakeMeshLink>()
        var factoryCalls = 0
        val manager = manager {
            factoryCalls++
            if (factoryCalls == 1) {
                first
            } else {
                FakeMeshLink().also {
                    it.connectGate = reconnectGate
                    reconnectLinks += it
                }
            }
        }
        val viewModel = viewModel(manager)

        manager.connect(DEVICE)
        manager.disconnect(DisconnectReason.RESYNC_FAILED)
        manager.armBleReconnectIfWanted()
        awaitCondition { reconnectLinks.isNotEmpty() && manager.isBleReconnectInProgress() }
        assertEquals(1, reconnectLinks.size)

        val decision = viewModel.activate()

        assertEquals(AutoReconnectDecision.RECONNECT_BLE, decision)
        delay(SETTLE_MS)
        assertEquals(1, reconnectLinks.size)
        assertEquals(1, first.connectCount)
        assertTrue(manager.isBleReconnectInProgress())
        reconnectGate.complete(Unit)
        awaitCondition { manager.state.value is ConnectionState.Ready }
    }

    @Test
    fun aFailedLaunchAutoReconnectArmsTheReconnectLoop() = runBlocking {
        var attempt = 0
        val manager = manager {
            attempt++
            // The first connect seeds the device row and the last-connection store; the launch
            // auto-reconnect and everything after it fail, as a radio left out of range does.
            FakeMeshLink().apply { if (attempt >= 2) failConnectWith = MeshLinkConnectException("out of range") }
        }
        val viewModel = viewModel(manager)

        manager.connect(DEVICE)
        manager.disconnect(DisconnectReason.RESYNC_FAILED)

        val decision = viewModel.activate()

        assertEquals(AutoReconnectDecision.RECONNECT_BLE, decision)
        awaitCondition { manager.state.value is ConnectionState.Reconnecting }
        assertTrue(attempt >= 2, "the launch auto-reconnect must have attempted a connect")
        val diagnostic = manager.lastDisconnectDiagnostic()
        assertTrue(
            diagnostic?.contains("source=activate.autoReconnectFailed") == true,
            "a launch reconnect throw must persist the diagnostic: $diagnostic",
        )
    }

    private fun manager(link: (String) -> FakeMeshLink): ConnectionManager = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = link,
        sessionFactory = { FakeMeshSession(selfInfo = defaultSelfInfo()) },
        nowEpochSeconds = { FIXED_CLOCK_SECONDS },
        ports = ConnectionManagerPorts(
            policy = fastPolicy,
            lastConnectionStore = LastConnectionStore(prefs),
            intentStore = ConnectionIntentStore(prefs),
        ),
    )

    private fun viewModel(manager: ConnectionManager) = AppStateViewModel(
        connectionManager = manager,
        stores = AppStateStores(
            lastConnectionStore = LastConnectionStore(prefs),
            intentStore = ConnectionIntentStore(prefs),
            prefs = prefs,
            database = db,
        ),
        scope = scope,
        runtime = AppStateRuntime(
            connectionUi = ConnectionUiState(scope),
            navigation = NavigationCoordinator(scope),
        ),
    )

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(TIMEOUT_MS) {
            while (!condition()) delay(POLL_MS)
        }
    }

    private companion object {
        const val DEVICE = "device-1"
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 10L
        const val SETTLE_MS = 150L
        const val FIXED_CLOCK_SECONDS = 1_000.0
    }
}
