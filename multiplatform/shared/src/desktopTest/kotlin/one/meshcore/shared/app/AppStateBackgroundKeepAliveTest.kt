package one.meshcore.shared.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.connection.ConnectionIntentStore
import one.meshcore.shared.connection.ConnectionManager
import one.meshcore.shared.connection.ConnectionManagerPorts
import one.meshcore.shared.connection.ConnectionUiState
import one.meshcore.shared.connection.FakeMeshLink
import one.meshcore.shared.connection.FakeMeshSession
import one.meshcore.shared.connection.LastConnectionStore
import one.meshcore.shared.connection.connect
import one.meshcore.shared.connection.defaultSelfInfo
import one.meshcore.shared.contact.getContacts
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MeshSession
import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.toLowercaseHex
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.remotenode.createSession
import one.meshcore.shared.remotenode.startSessionKeepAlive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * Backgrounding stops the room keep-alives, mirroring iOS `AppState.handleEnterBackground`. Proven over a
 * real per-connection graph: a keep-alive tick is held inside the session's send, and the background hook
 * must cancel it. Without the hook the send is never cancelled and the test times out.
 */
class AppStateBackgroundKeepAliveTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var prefs: InMemoryPreferencesStore

    private val selfKey = ByteArray(32) { 0x01 }
    private val repeaterKey = ByteArray(32) { 0x09 }

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-keepalive", ".db").also { it.delete() }
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
    fun enterBackgroundCancelsRunningRoomKeepAlives() = runBlocking {
        val session = KeepAliveProbeSession(
            FakeMeshSession(
                selfInfo = defaultSelfInfo(selfKey),
                contacts = listOf(repeaterContact()),
            ),
        )
        val manager = ConnectionManager(
            database = db,
            scope = scope,
            linkFactory = { FakeMeshLink() },
            sessionFactory = { session },
            nowEpochSeconds = { 1_000.0 },
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

        manager.connect(DEVICE)
        val services = assertNotNull(manager.services)
        val radioID = assertNotNull(manager.radioId)
        val contact = assertNotNull(
            services.contactService.getContacts(radioID).firstOrNull { it.publicKey.contentEquals(repeaterKey) },
        )
        val nodeSession = services.remoteNodeService.createSession(radioID, contact)
        services.remoteNodeService.startSessionKeepAlive(nodeSession.id, repeaterKey)

        // The loop's first tick runs immediately and parks inside the send, so a live keep-alive exists.
        withTimeout(TIMEOUT) { session.entered.await() }

        viewModel.onEnterBackground()

        withTimeout(TIMEOUT) { session.cancelled.await() }
    }

    private fun repeaterContact(): MeshContact = MeshContact(
        id = repeaterKey.toLowercaseHex(),
        publicKey = repeaterKey,
        typeRawValue = ContactType.REPEATER.raw,
        flags = 0,
        // Zero means a direct out-path, so the keep-alive is actually sent (a flood-routed node skips it).
        outPathLength = 0,
        outPath = ByteArray(0),
        advertisedName = "Repeater",
        lastAdvertisement = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
    )

    /**
     * A session whose keep-alive send never completes, so the keep-alive loop stays parked in it until
     * something cancels the job. [entered] and [cancelled] make both halves observable.
     */
    private class KeepAliveProbeSession(private val delegate: FakeMeshSession) : MeshSession by delegate {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()

        override suspend fun sendKeepAlive(publicKey: ByteArray, syncSince: Long): MessageSentInfo {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
    }

    private companion object {
        const val DEVICE = "device-1"
        val TIMEOUT = 10.seconds
    }
}
