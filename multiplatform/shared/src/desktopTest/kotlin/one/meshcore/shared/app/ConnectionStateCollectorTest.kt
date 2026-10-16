package one.meshcore.shared.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.connection.ConnectionAlert
import one.meshcore.shared.connection.ConnectionIntentStore
import one.meshcore.shared.connection.ConnectionManager
import one.meshcore.shared.connection.ConnectionManagerPorts
import one.meshcore.shared.connection.ConnectionState
import one.meshcore.shared.connection.ConnectionUiState
import one.meshcore.shared.connection.FakeMeshLink
import one.meshcore.shared.connection.FakeMeshSession
import one.meshcore.shared.connection.LastConnectionStore
import one.meshcore.shared.connection.PairingFailureKind
import one.meshcore.shared.connection.defaultSelfInfo
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferencesStore
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The connection-state collector keeps routing after a prefs fault. Cancellation and an error stop it,
 * so a later bond-loss episode is not presented.
 */
class ConnectionStateCollectorTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-state-collector", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun aPrefsFaultStillRoutesTheNextBondLoss() = runBlocking {
        val prefs = ThrowingReadStore { IllegalStateException("db locked") }
        val harness = harness(prefs)
        try {
            awaitCondition { prefs.reads.get() >= 1 }
            prefs.failNext = false
            harness.manager.mutableState.value = ConnectionState.BondLost(DEVICE)
            awaitCondition { harness.viewModel.connectionAlert.value != null }
            val alert = assertIs<ConnectionAlert.Pairing>(harness.viewModel.connectionAlert.value)
            assertEquals(DEVICE, alert.deviceId)
            assertEquals(PairingFailureKind.AUTHENTICATION, alert.kind)
        } finally {
            harness.close()
        }
    }

    @Test
    fun cancellationStopsRouting() = runBlocking {
        val prefs = ThrowingReadStore { CancellationException("stopped") }
        val harness = harness(prefs)
        try {
            awaitCondition { prefs.reads.get() >= 1 }
            val readsAtStop = prefs.reads.get()
            harness.manager.mutableState.value = ConnectionState.BondLost(DEVICE)
            delay(SETTLE_MS)
            assertEquals(readsAtStop, prefs.reads.get())
            assertNull(harness.viewModel.connectionAlert.value)
        } finally {
            harness.close()
        }
    }

    @Test
    fun anErrorStopsRouting() {
        runBlocking {
            val prefs = ThrowingReadStore { StateCollectorProbeError() }
            val thrown = mutableListOf<Throwable>()
            val harness = harness(
                prefs,
                CoroutineExceptionHandler { _, error -> thrown += error },
            )
            try {
                awaitCondition { prefs.reads.get() >= 1 }
                val readsAtStop = prefs.reads.get()
                harness.manager.mutableState.value = ConnectionState.BondLost(DEVICE)
                delay(SETTLE_MS)
                assertEquals(readsAtStop, prefs.reads.get())
                assertNull(harness.viewModel.connectionAlert.value)
                awaitCondition { thrown.isNotEmpty() }
                assertIs<StateCollectorProbeError>(thrown.single())
            } finally {
                harness.close()
            }
        }
    }

    private fun harness(prefs: PreferencesStore, handler: CoroutineExceptionHandler? = null): Harness {
        val base = SupervisorJob() + Dispatchers.Default
        val scope = CoroutineScope(if (handler == null) base else base + handler)
        val manager = ConnectionManager(
            database = db,
            scope = scope,
            linkFactory = { FakeMeshLink() },
            sessionFactory = { FakeMeshSession(selfInfo = defaultSelfInfo(PUBLIC_KEY)) },
            nowEpochSeconds = { EPOCH_SECONDS },
            ports = ConnectionManagerPorts(
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
        )
        return Harness(scope, manager, viewModel)
    }

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(AWAIT_TIMEOUT_MS) {
            while (!condition()) delay(AWAIT_POLL_MS)
        }
    }

    private class Harness(
        val scope: CoroutineScope,
        val manager: ConnectionManager,
        val viewModel: AppStateViewModel,
    ) {
        suspend fun close() {
            scope.cancel()
            withTimeout(AWAIT_TIMEOUT_MS) { scope.coroutineContext[Job]?.join() }
        }
    }

    private class ThrowingReadStore(private val failure: () -> Throwable) : PreferencesStore {
        private val backing = InMemoryPreferencesStore()
        val reads = AtomicInteger()

        @Volatile
        var failNext = true

        override suspend fun getString(key: String): String? {
            if (failNext && key == PersistenceKeys.LAST_CONNECTED_DEVICE_ID) {
                reads.incrementAndGet()
                throw failure()
            }
            return backing.getString(key)
        }

        override suspend fun getBoolean(key: String): Boolean? = backing.getBoolean(key)

        override suspend fun getInt(key: String): Int? = backing.getInt(key)

        override suspend fun getDouble(key: String): Double? = backing.getDouble(key)

        override suspend fun putString(key: String, value: String) = backing.putString(key, value)

        override suspend fun putBoolean(key: String, value: Boolean) = backing.putBoolean(key, value)

        override suspend fun putInt(key: String, value: Int) = backing.putInt(key, value)

        override suspend fun putDouble(key: String, value: Double) = backing.putDouble(key, value)

        override suspend fun remove(key: String) = backing.remove(key)
    }

    private companion object {
        const val DEVICE = "device-1"
        const val EPOCH_SECONDS = 1_000.0
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val AWAIT_POLL_MS = 10L
        const val SETTLE_MS = 100L
        val PUBLIC_KEY = ByteArray(PUBLIC_KEY_LENGTH) { 0x01 }
        const val PUBLIC_KEY_LENGTH = 32
    }
}

private class StateCollectorProbeError : Error("probe")
