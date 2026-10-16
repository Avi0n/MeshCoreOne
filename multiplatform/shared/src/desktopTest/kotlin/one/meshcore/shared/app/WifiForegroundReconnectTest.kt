package one.meshcore.shared.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.backup.ConnectionMethod
import one.meshcore.shared.connection.ConnectionIntentStore
import one.meshcore.shared.connection.ConnectionManager
import one.meshcore.shared.connection.ConnectionManagerPorts
import one.meshcore.shared.connection.ConnectionUiState
import one.meshcore.shared.connection.DeviceSelectionFilter
import one.meshcore.shared.connection.FakeMeshLink
import one.meshcore.shared.connection.FakeMeshSession
import one.meshcore.shared.connection.LastConnectionStore
import one.meshcore.shared.connection.defaultSelfInfo
import one.meshcore.shared.connection.wifiForegroundReconnectFailedLine
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.persistence.seedDevice
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import one.meshcore.shared.transport.MeshLink
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Foreground return retries the saved Wi-Fi radio. A connect fault is logged and reconcile continues.
 * Cancellation and an error leave the reconcile instead of being recorded as that fault.
 */
class WifiForegroundReconnectTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var prefs: InMemoryPreferencesStore

    private val publicKey = ByteArray(PUBLIC_KEY_LENGTH) { 0x01 }
    private val lastId = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
    private val radioId = Uuid.normalized("B0000000-0000-0000-0000-000000000002")

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-wifi-foreground", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        prefs = InMemoryPreferencesStore()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun aConnectFaultIsLoggedAndReconcileReturns() = runBlocking {
        val calls = AtomicInteger()
        val harness = harness { _, _, _ ->
            calls.incrementAndGet()
            error("link down")
        }
        try {
            harness.viewModel.ForegroundReconcile().run()
            assertEquals(listOf(wifiForegroundReconnectFailedLine("link down")), harness.warnings)
            assertEquals(1, calls.get())
        } finally {
            harness.close()
        }
    }

    @Test
    fun cancellationPropagatesWithoutAFailureLog() = runBlocking {
        val calls = AtomicInteger()
        val harness = harness { _, _, _ ->
            calls.incrementAndGet()
            throw CancellationException("stopped")
        }
        try {
            val thrown = assertFailsWith<CancellationException> {
                harness.viewModel.ForegroundReconcile().run()
            }
            assertEquals("stopped", thrown.message)
            assertEquals(emptyList(), harness.warnings)
            assertEquals(1, calls.get())
        } finally {
            harness.close()
        }
    }

    @Test
    fun anErrorPropagatesWithoutAFailureLog() = runBlocking {
        val calls = AtomicInteger()
        val harness = harness { _, _, _ ->
            calls.incrementAndGet()
            throw WifiForegroundProbeError()
        }
        try {
            assertFailsWith<WifiForegroundProbeError> {
                harness.viewModel.ForegroundReconcile().run()
            }
            assertEquals(emptyList(), harness.warnings)
            assertEquals(1, calls.get())
        } finally {
            harness.close()
        }
    }

    private suspend fun harness(connect: (String, Int, suspend () -> Unit) -> MeshLink): Harness {
        val handle = DeviceSelectionFilter.wifiTransportHandle(HOST, PORT)
        LastConnectionStore(prefs).persist(lastId, radioId, "Radio", handle = handle)
        db.deviceDao().upsert(deviceEntity(lastId, listOf(ConnectionMethod.WiFi(HOST, PORT, null))))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val logScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val warnings = mutableListOf<String>()
        val logger = PersistentLogger(
            scope = logScope,
            category = "AppState",
            record = { entry -> recordWarning(warnings, entry) },
        )
        val manager = ConnectionManager(
            database = db,
            scope = scope,
            linkFactory = { FakeMeshLink() },
            sessionFactory = { FakeMeshSession(selfInfo = defaultSelfInfo(publicKey)) },
            nowEpochSeconds = { EPOCH_SECONDS },
            ports = ConnectionManagerPorts(
                wifiLinkFactory = connect,
                lastConnectionStore = LastConnectionStore(prefs),
                intentStore = ConnectionIntentStore(prefs),
            ),
        )
        val viewModel = AppStateViewModel(
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
            edges = AppStateEdges(logger = logger),
        )
        return Harness(scope, logScope, viewModel, warnings)
    }

    private fun recordWarning(warnings: MutableList<String>, entry: DebugLogEntryDto) {
        if (entry.level == DebugLogLevel.WARNING) warnings += entry.message
    }

    private fun deviceEntity(id: Uuid, methods: List<ConnectionMethod>) = seedDevice(id, radioId, publicKey, methods)

    private class Harness(
        private val scope: CoroutineScope,
        private val logScope: CoroutineScope,
        val viewModel: AppStateViewModel,
        val warnings: List<String>,
    ) {
        suspend fun close() {
            scope.cancel()
            logScope.cancel()
            withTimeout(AWAIT_TIMEOUT_MS) {
                scope.coroutineContext.job.join()
                logScope.coroutineContext.job.join()
            }
        }
    }

    private companion object {
        const val HOST = "10.0.0.5"
        const val PORT = 4403
        const val PUBLIC_KEY_LENGTH = 32
        const val EPOCH_SECONDS = 1_000.0
        const val AWAIT_TIMEOUT_MS = 5_000L
    }
}

private class WifiForegroundProbeError : Error("probe")
