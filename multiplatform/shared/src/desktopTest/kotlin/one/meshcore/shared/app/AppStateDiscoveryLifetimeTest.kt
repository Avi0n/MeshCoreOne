package one.meshcore.shared.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.connection.ConnectionIntentStore
import one.meshcore.shared.connection.ConnectionManager
import one.meshcore.shared.connection.ConnectionManagerPorts
import one.meshcore.shared.connection.ConnectionState
import one.meshcore.shared.connection.ConnectionUiState
import one.meshcore.shared.connection.FakeMeshLink
import one.meshcore.shared.connection.FakeMeshSession
import one.meshcore.shared.connection.LastConnectionStore
import one.meshcore.shared.connection.ReconnectPolicy
import one.meshcore.shared.connection.connect
import one.meshcore.shared.connection.defaultSelfInfo
import one.meshcore.shared.connection.disconnect
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.tools.DiscoveryLifetimeFixture
import one.meshcore.shared.tools.NodeDiscoveryViewModel
import one.meshcore.shared.tools.NodeDiscoveryWorkspace
import one.meshcore.shared.transport.LinkDropReason
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class AppStateDiscoveryLifetimeTest {
    private lateinit var file: File
    private lateinit var database: MeshDatabase
    private lateinit var scope: CoroutineScope
    private val prefs = InMemoryPreferencesStore()
    private val links = mutableListOf<FakeMeshLink>()
    private val sessions = mutableListOf<FakeMeshSession>()
    private var keyByte: Byte = 1
    private var reconnectGate: CompletableDeferred<Unit>? = null
    private var sends = 0
    private var clock = 0L
    private lateinit var manager: ConnectionManager
    private lateinit var workspace: NodeDiscoveryWorkspace
    private lateinit var model: NodeDiscoveryViewModel
    private lateinit var app: AppStateViewModel

    @BeforeTest
    fun setUp() = runBlocking {
        file = File.createTempFile("discovery-lifetime", ".db").also { it.delete() }
        database = meshDatabase(file.absolutePath)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        manager = ConnectionManager(
            database = database,
            scope = scope,
            linkFactory = {
                FakeMeshLink().also { link ->
                    if (links.isNotEmpty()) link.connectGate = reconnectGate
                    links += link
                }
            },
            sessionFactory = {
                FakeMeshSession(selfInfo = defaultSelfInfo(ByteArray(32) { keyByte })).also { sessions += it }
            },
            nowEpochSeconds = { 1_000.0 },
            ports = ConnectionManagerPorts(
                policy = ReconnectPolicy(
                    ReconnectPolicy.Config(baseBackoff = 20.milliseconds, maxBackoff = 100.milliseconds),
                ),
                lastConnectionStore = LastConnectionStore(prefs),
                intentStore = ConnectionIntentStore(prefs),
            ),
        )
        workspace = NodeDiscoveryWorkspace(
            scope = CoroutineScope(scope.coroutineContext + Dispatchers.Main.immediate),
            dependencies = NodeDiscoveryWorkspace.Dependencies(
                sendDiscover = { _, _ ->
                    sends++
                    DiscoveryLifetimeFixture.TAG
                },
                events = { manager.services?.session?.events },
                contactService = { manager.services?.contactService },
                discoveredNodeStore = { null },
                radioId = { manager.radioId },
                isRadioConnected = { manager.services != null },
                maxContacts = { null },
            ),
            nowEpochMillis = { clock },
            scanDurationMs = DiscoveryLifetimeFixture.WINDOW,
        )
        withContext(Dispatchers.Main) {
            model = workspace.bind(
                NodeDiscoveryWorkspace.Messages(
                    notConnected = { "Not connected" },
                    scanFailed = "Scan failed",
                    unknownNode = "Unknown",
                    contactError = { "Contact error" },
                ),
            )
        }
        app = AppStateViewModel(
            connectionManager = manager,
            stores = AppStateStores(LastConnectionStore(prefs), ConnectionIntentStore(prefs), prefs, database),
            scope = scope,
            runtime = AppStateRuntime(ConnectionUiState(scope), NavigationCoordinator(scope), workspace),
        )
        manager.connect(DEVICE_A)
        await { app.navigatedRadioId == manager.radioId }
        withContext(Dispatchers.Main) { workspace.scan() }
        await { withContext(Dispatchers.Main) { sends == 1 && model.state.value.isScanning } }
    }

    @AfterTest
    fun tearDown() {
        scope.cancelAndJoinBlocking()
        database.close()
        file.delete()
    }

    @Test
    fun aRealRestingDisconnectRetiresTheWorkspace() = runBlocking {
        manager.disconnect()
        await { withContext(Dispatchers.Main) { !model.state.value.isScanning } }
        withContext(Dispatchers.Main) {
            assertTrue(model.state.value.results.isEmpty())
            assertFalse(model.state.value.hasScannedOnce)
            assertNull(model.state.value.errorMessage)
        }
    }

    @Test
    fun aRealRadioSwitchRetiresThePreviousResults() = runBlocking {
        sessions.last().emit(DiscoveryLifetimeFixture.response())
        await { withContext(Dispatchers.Main) { model.state.value.results.isNotEmpty() } }
        keyByte = 2
        manager.connect(DEVICE_B)
        await { withContext(Dispatchers.Main) { model.state.value.results.isEmpty() && !model.state.value.isScanning } }
        withContext(Dispatchers.Main) {
            assertEquals(1, sends)
            assertNull(model.state.value.errorMessage)
        }
    }

    @Test
    fun aRealSameRadioReconnectRebindsTheOriginalScan() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        reconnectGate = gate
        val originalRadio = manager.radioId
        val originalSubscribers = sessions.last().eventSubscribers.value
        links.first().drop(LinkDropReason.CONNECTION_LOST)
        await { manager.services == null && links.size == 2 }
        withContext(Dispatchers.Main) { assertTrue(model.state.value.isScanning) }
        gate.complete(Unit)
        await { manager.state.value == ConnectionState.Ready(DEVICE_A) && manager.radioId == originalRadio }
        await { sessions.last().eventSubscribers.value >= originalSubscribers }
        sessions.last().emit(DiscoveryLifetimeFixture.response())
        await { withContext(Dispatchers.Main) { model.state.value.results.isNotEmpty() } }
        withContext(Dispatchers.Main) {
            assertEquals(1, sends)
            assertNull(model.state.value.errorMessage)
        }
    }

    @Test
    fun foregroundExpiresTheDeadlineEvenWhenUserIntentReturnsEarly() = runBlocking {
        prefs.putBoolean(PersistenceKeys.USER_EXPLICITLY_DISCONNECTED, true)
        withContext(Dispatchers.Main) { clock = DiscoveryLifetimeFixture.WINDOW }
        app.onEnterForeground()
        await { withContext(Dispatchers.Main) { !model.state.value.isScanning } }
        withContext(Dispatchers.Main) {
            assertTrue(model.state.value.hasScannedOnce)
            assertNull(model.state.value.errorMessage)
            assertEquals(1, sends)
        }
    }

    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(TIMEOUT_MS) {
        while (!condition()) delay(POLL_MS)
    }

    private companion object {
        const val DEVICE_A = "discovery-radio-a"
        const val DEVICE_B = "discovery-radio-b"
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 10L
    }
}
