package one.meshcore.shared.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ConnectionMethod
import one.meshcore.shared.connection.ConnectionAlert
import one.meshcore.shared.connection.ConnectionIntentStore
import one.meshcore.shared.connection.ConnectionManager
import one.meshcore.shared.connection.ConnectionManagerPorts
import one.meshcore.shared.connection.ConnectionUiState
import one.meshcore.shared.connection.FakeMeshLink
import one.meshcore.shared.connection.FakeMeshSession
import one.meshcore.shared.connection.LastConnectionStore
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.persistence.seedDevice
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.transport.BluetoothUnauthorizedException
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothPermissionAlertRoutingTest {
    private lateinit var file: File
    private lateinit var database: MeshDatabase

    @BeforeTest
    fun setUp() {
        file = File.createTempFile("mesh-permission-alert", ".db").also { it.delete() }
        database = meshDatabase(file.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        database.close()
        file.delete()
    }

    @Test
    fun deniedFreshPairUsesThePermissionRemedy() = runTest(UnconfinedTestDispatcher()) {
        connectAndCheckPermissionAlert()
    }

    @Test
    fun deniedSavedRadioUsesTheSamePermissionRemedy() = runTest(UnconfinedTestDispatcher()) {
        database.deviceDao().upsert(
            seedDevice(DEVICE, RADIO, PUBLIC_KEY, listOf(ConnectionMethod.Bluetooth(DEVICE, HANDLE))),
        )
        connectAndCheckPermissionAlert()
    }

    @Test
    fun aFreshBridgeAuthorizationFailureUsesThePermissionRemedy() = runTest(UnconfinedTestDispatcher()) {
        connectAndCheckPermissionAlert(MeshBridgeException(BridgeErrorCode.BLUETOOTH_UNAUTHORIZED, "native detail"))
    }

    private suspend fun TestScope.connectAndCheckPermissionAlert(error: Exception = BluetoothUnauthorizedException()) {
        val prefs = InMemoryPreferencesStore()
        val ui = ConnectionUiState(backgroundScope)
        val manager = ConnectionManager(
            database = database,
            scope = backgroundScope,
            linkFactory = { FakeMeshLink().apply { failConnectWith = error } },
            sessionFactory = { FakeMeshSession() },
            nowEpochSeconds = { 0.0 },
            ports = ConnectionManagerPorts(),
        )
        val viewModel = AppStateViewModel(
            connectionManager = manager,
            stores = AppStateStores(LastConnectionStore(prefs), ConnectionIntentStore(prefs), prefs, database),
            scope = backgroundScope,
            runtime = AppStateRuntime(ui, NavigationCoordinator(backgroundScope)),
        )
        val thrown = assertFailsWith<Exception> { viewModel.connect(HANDLE) }
        assertEquals(error, thrown)
        runCurrent()
        assertEquals(ConnectionAlert.BluetoothUnauthorized, viewModel.connectionAlert.value)
    }

    private companion object {
        const val HANDLE = "AA:BB:CC:DD:EE:FF"
        val DEVICE = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        val RADIO = Uuid.normalized("B0000000-0000-0000-0000-000000000002")
        val PUBLIC_KEY = ByteArray(32) { 1 }
    }
}
