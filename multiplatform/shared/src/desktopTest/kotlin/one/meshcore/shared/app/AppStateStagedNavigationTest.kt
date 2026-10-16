package one.meshcore.shared.app

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
import one.meshcore.shared.connection.FakeMeshLink
import one.meshcore.shared.connection.FakeMeshSession
import one.meshcore.shared.connection.LastConnectionStore
import one.meshcore.shared.connection.ReconnectPolicy
import one.meshcore.shared.connection.connect
import one.meshcore.shared.connection.defaultSelfInfo
import one.meshcore.shared.connection.disconnect
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.navigation.MeshCoreDeepLink
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.navigateToChat
import one.meshcore.shared.navigation.stageContactLink
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.transport.LinkDropReason
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Staged navigation is torn down with the connection, mirroring the iOS `wireServicesIfConnected`
 * branches: the services-gone path runs `clearPendingLinks`, and a live container on a different radio
 * runs `clearPerRadioSelection`. Both matter because `radioID` is the data partition key — replaying a
 * destination staged against radio A once radio B is connected aims a detail pane at another radio's data.
 */
class AppStateStagedNavigationTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var prefs: InMemoryPreferencesStore

    // Which radio the fake session claims to be; bumped between connects to model a second radio.
    private var selfKeyByte: Byte = RADIO_A_KEY_BYTE

    private val fastPolicy = ReconnectPolicy(
        ReconnectPolicy.Config(baseBackoff = 20.milliseconds, maxBackoff = 100.milliseconds),
    )

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-stagednav", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        prefs = InMemoryPreferencesStore()
        selfKeyByte = RADIO_A_KEY_BYTE
    }

    @AfterTest
    fun tearDown() {
        scope.cancelAndJoinBlocking()
        db.close()
        dbFile.delete()
    }

    @Test
    fun disconnectClearsAStagedDeepLinkAndTheOpenChatRoute() = runBlocking {
        val nav = NavigationCoordinator(scope)
        val manager = manager()
        viewModel(manager, nav)

        manager.connect(DEVICE_A)
        nav.stageContactLink(contactLink())
        nav.navigateToChat(CONTACT_ID)
        assertNotNull(nav.chatsSelectedRoute.value)

        manager.disconnect()

        awaitCondition { nav.pendingContactLink.value == null && nav.chatsSelectedRoute.value == null }
    }

    @Test
    fun connectingADifferentRadioClearsTheChatRouteStagedOnThePreviousOne() = runBlocking {
        val nav = NavigationCoordinator(scope)
        val manager = manager()
        viewModel(manager, nav)

        manager.connect(DEVICE_A)
        nav.navigateToChat(CONTACT_ID)
        assertNotNull(nav.chatsSelectedRoute.value)

        // A different radio: its own self key, so it resolves to its own radioID rather than
        // reusing radio A's partition.
        selfKeyByte = RADIO_B_KEY_BYTE
        manager.connect(DEVICE_B)

        awaitCondition { nav.chatsSelectedRoute.value == null }
    }

    @Test
    fun anAutoReconnectToTheSameRadioKeepsTheOpenConversation() = runBlocking {
        val nav = NavigationCoordinator(scope)
        val links = mutableListOf<FakeMeshLink>()
        val manager = manager(policy = fastPolicy, link = { FakeMeshLink().also { links += it } })
        viewModel(manager, nav)

        manager.connect(DEVICE_A)
        nav.navigateToChat(CONTACT_ID)

        links.first().drop(LinkDropReason.CONNECTION_LOST)
        awaitCondition { manager.state.value == ConnectionState.Ready(DEVICE_A) }

        // The drop tore the container down and the loop rebuilt it on the same radio, so nothing was
        // staged against a partition that went away.
        assertTrue(nav.chatsSelectedRoute.value != null, "an auto-reconnect must not eject an open chat")
    }

    private fun manager(
        policy: ReconnectPolicy = ReconnectPolicy(),
        link: (String) -> FakeMeshLink = { FakeMeshLink() },
    ): ConnectionManager = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = link,
        sessionFactory = { FakeMeshSession(selfInfo = defaultSelfInfo(ByteArray(SELF_KEY_BYTES) { selfKeyByte })) },
        nowEpochSeconds = { FIXED_CLOCK_SECONDS },
        ports = ConnectionManagerPorts(
            policy = policy,
            lastConnectionStore = LastConnectionStore(prefs),
            intentStore = ConnectionIntentStore(prefs),
        ),
    )

    private fun viewModel(manager: ConnectionManager, nav: NavigationCoordinator) = AppStateViewModel(
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
            navigation = nav,
        ),
    )

    private fun contactLink(): MeshCoreDeepLink.Contact =
        MeshCoreDeepLink.Contact(name = "Bob", publicKey = ByteArray(32) { 0x0B }, type = ContactType.CHAT)

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(TIMEOUT_MS) {
            while (!condition()) delay(POLL_MS.milliseconds)
        }
    }

    private companion object {
        const val DEVICE_A = "device-a"
        const val DEVICE_B = "device-b"
        const val CONTACT_ID = "contact-1"
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 10L
        const val FIXED_CLOCK_SECONDS = 1_000.0
        const val SELF_KEY_BYTES = 32
        const val RADIO_A_KEY_BYTE: Byte = 1
        const val RADIO_B_KEY_BYTE: Byte = 2
    }
}
