package one.meshcore.shared.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.backup.RemoteNodeSessionDto
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
import one.meshcore.shared.remotenode.testSession
import one.meshcore.shared.remotenode.ui.NodeSettingsViewModel
import one.meshcore.shared.remotenode.ui.RemoteAdminWorkspaces
import one.meshcore.shared.transport.LinkDropReason
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds

class AppStateRemoteAdminLifetimeTest {
    private lateinit var file: File
    private lateinit var database: MeshDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var manager: ConnectionManager
    private lateinit var app: AppStateViewModel
    private lateinit var workspaces: RemoteAdminWorkspaces
    private lateinit var session: RemoteNodeSessionDto
    private lateinit var settings: NodeSettingsViewModel
    private val preferences = InMemoryPreferencesStore()
    private val links = mutableListOf<FakeMeshLink>()
    private var keyByte: Byte = 1
    private var reconnectGate: CompletableDeferred<Unit>? = null

    @BeforeTest
    fun setUp() = runBlocking {
        file = File.createTempFile("remote-admin-lifetime", ".db").also { it.delete() }
        database = meshDatabase(file.absolutePath)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        manager = ConnectionManager(
            database,
            scope,
            linkFactory = {
                FakeMeshLink().also { link ->
                    if (links.isNotEmpty()) link.connectGate = reconnectGate
                    links += link
                }
            },
            sessionFactory = { FakeMeshSession(selfInfo = defaultSelfInfo(ByteArray(32) { keyByte })) },
            nowEpochSeconds = { 1_000.0 },
            ports = ConnectionManagerPorts(
                policy = ReconnectPolicy(
                    ReconnectPolicy.Config(baseBackoff = 20.milliseconds, maxBackoff = 100.milliseconds),
                ),
                lastConnectionStore = LastConnectionStore(preferences),
                intentStore = ConnectionIntentStore(preferences),
            ),
        )
        workspaces = RemoteAdminWorkspaces(
            CoroutineScope(scope.coroutineContext + Dispatchers.Main.immediate),
            RemoteAdminWorkspaces.Dependencies(
                repeaterAdminService = { manager.services?.repeaterAdminService },
                roomAdminService = { manager.services?.roomAdminService },
                contactService = { manager.services?.contactService },
                nodeSnapshotService = { manager.services?.nodeSnapshotService },
                radioId = { manager.radioId },
                isRadioConnected = { manager.services != null },
            ),
        )
        app = AppStateViewModel(
            manager,
            stores = AppStateStores(
                LastConnectionStore(preferences),
                ConnectionIntentStore(preferences),
                preferences,
                database,
            ),
            scope = scope,
            runtime = AppStateRuntime(
                ConnectionUiState(scope),
                NavigationCoordinator(scope),
                remoteAdminWorkspaces = workspaces,
            ),
        )
        manager.connect(DEVICE_A)
        await { app.navigatedRadioId == manager.radioId }
        session = testSession(radioID = requireNotNull(manager.radioId), isConnected = true)
        withContext(Dispatchers.Main) {
            settings = workspaces.family(session).repeaterSettings()
            settings.setOwnerInfo("Retained draft")
        }
    }

    @AfterTest
    fun tearDown() {
        scope.cancelAndJoinBlocking()
        database.close()
        file.delete()
    }

    @Test
    fun aRealTerminalDisconnectRetiresRemoteModels() = runBlocking {
        manager.disconnect()
        await { withContext(Dispatchers.Main) { workspaces.family(session).repeaterSettings() !== settings } }
        withContext(Dispatchers.Main) {
            assertNotSame(settings, workspaces.family(session).repeaterSettings())
        }
    }

    @Test
    fun aRealRadioSwitchRetiresThePreviousDraft() = runBlocking {
        keyByte = 2
        manager.connect(DEVICE_B)
        await { app.navigatedRadioId == manager.radioId && manager.radioId != session.radioID }
        await { withContext(Dispatchers.Main) { workspaces.family(session).repeaterSettings() !== settings } }
        withContext(Dispatchers.Main) {
            val next = session.copy(radioID = requireNotNull(manager.radioId))
            assertEquals("", workspaces.family(next).repeaterSettings().state.value.ownerInfo)
        }
    }

    @Test
    fun aRealSameRadioReconnectKeepsTheOriginalModelAndDraft() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        reconnectGate = gate
        links.first().drop(LinkDropReason.CONNECTION_LOST)
        await { manager.services == null && links.size == 2 }
        withContext(Dispatchers.Main) { assertSame(settings, workspaces.family(session).repeaterSettings()) }
        gate.complete(Unit)
        await { manager.state.value == ConnectionState.Ready(DEVICE_A) && manager.radioId == session.radioID }
        withContext(Dispatchers.Main) {
            assertSame(settings, workspaces.family(session).repeaterSettings())
            assertEquals("Retained draft", settings.state.value.ownerInfo)
        }
    }

    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(TIMEOUT_MS) {
        while (!condition()) delay(POLL_MS)
    }

    private companion object {
        const val DEVICE_A = "remote-admin-radio-a"
        const val DEVICE_B = "remote-admin-radio-b"
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 10L
    }
}
