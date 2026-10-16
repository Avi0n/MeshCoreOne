package one.meshcore.shared.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.connection.FakeMeshSession
import one.meshcore.shared.connection.LastConnectionStore
import one.meshcore.shared.connection.ServiceContainer
import one.meshcore.shared.connection.ServiceContainerIdentity
import one.meshcore.shared.connection.ServiceContainerRuntime
import one.meshcore.shared.contact.getContacts
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.persistence.toEntity
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.testContact
import one.meshcore.shared.ui.shouldAnnounceCachedData
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The disconnected data path: with no radio connected, the app's own providers still resolve the
 * last-connected partition and read its stored rows, and the cached-data announcement becomes reachable.
 * Drives [OfflineDataAccess] exactly as `AppContainer` holds it, so a provider that resolves here is the
 * one the Nodes list, the map and the chats list read.
 */
class OfflineDataAccessTest {
    private val radio = Uuid.normalized("A0000000-0000-0000-0000-0000000000AA")
    private val otherRadio = Uuid.normalized("B0000000-0000-0000-0000-0000000000BB")
    private val deviceRow = Uuid.normalized("C0000000-0000-0000-0000-0000000000CC")

    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var lastConnection: LastConnectionStore

    /** The live per-connection container, mirroring `connectionManager.services`. */
    private var live: ServiceContainer? = null

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("offline-data-access", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lastConnection = LastConnectionStore(InMemoryPreferencesStore())
        live = null
    }

    @AfterTest
    fun tearDown() {
        scope.cancelAndJoinBlocking()
        db.close()
        dbFile.delete()
    }

    private fun access(fixtureRadioId: () -> Uuid? = { null }): OfflineDataAccess = OfflineDataAccess(
        database = db,
        scope = scope,
        lastConnectionStore = lastConnection,
        liveServices = { live },
        nowEpochSeconds = { NOW_EPOCH_SECONDS },
        fixtureRadioId = fixtureRadioId,
    )

    private fun liveContainer(radioId: Uuid): ServiceContainer = ServiceContainer(
        session = FakeMeshSession(),
        database = db,
        identity = ServiceContainerIdentity(
            radioId = radioId,
            selfPublicKey = ByteArray(PUBLIC_KEY_BYTES) { 0x01 },
            selfNodeName = "Test",
            maxChannels = MAX_CHANNELS,
            maxContacts = MAX_CONTACTS,
        ),
        runtime = ServiceContainerRuntime(
            scope = scope,
            nodePasswordStore = InMemoryNodePasswordStore(),
            nowEpochSeconds = { NOW_EPOCH_SECONDS },
        ),
    )

    /** Rows the last connection left behind: one plain chat contact, one with coordinates for the map. */
    private suspend fun seedStoredRows(radioId: Uuid) {
        db.contactDao().upsert(
            testContact(
                radioID = radioId,
                publicKey = ByteArray(PUBLIC_KEY_BYTES) { 0x0A },
                type = ContactType.CHAT,
                name = CHAT_CONTACT_NAME,
            ).toEntity().copy(id = Uuid.normalized("D0000000-0000-0000-0000-000000000001")),
        )
        db.contactDao().upsert(
            testContact(
                radioID = radioId,
                publicKey = ByteArray(PUBLIC_KEY_BYTES) { 0x0B },
                type = ContactType.REPEATER,
                name = MAPPED_CONTACT_NAME,
                latitude = MAPPED_LATITUDE,
                longitude = MAPPED_LONGITUDE,
            ).toEntity().copy(
                id = Uuid.normalized("D0000000-0000-0000-0000-000000000002"),
                lastMessageDate = NOW_EPOCH_SECONDS,
            ),
        )
    }

    private suspend fun persistLastConnection(radioId: Uuid) {
        lastConnection.persist(deviceId = deviceRow, radioId = radioId, deviceName = "Radio", handle = "handle")
    }

    @Test
    fun disconnectedProvidersReadTheLastConnectedRadiosRows() = runBlocking<Unit> {
        seedStoredRows(radio)
        persistLastConnection(radio)

        val offline = access()
        offline.refresh()

        // The partition is the persisted radioID verbatim; minting a fresh one would orphan every row.
        assertEquals(radio, offline.currentRadioId())

        val contacts = assertNotNull(offline.contactService()).getContacts(radio)
        assertEquals(2, contacts.size)
        assertTrue(contacts.any { it.name == CHAT_CONTACT_NAME })

        // The map builds its pins from the same provider, so a contact with coordinates has to survive.
        val mapped = assertNotNull(contacts.firstOrNull { it.name == MAPPED_CONTACT_NAME })
        assertEquals(MAPPED_LATITUDE, mapped.latitude)
        assertEquals(MAPPED_LONGITUDE, mapped.longitude)

        val conversations = assertNotNull(offline.conversationListStore()).fetchConversationContacts(radio)
        assertEquals(1, conversations.size)
        assertEquals(MAPPED_CONTACT_NAME, conversations.single().name)

        // Chat detail reads stored messages through these while disconnected.
        assertNotNull(offline.chatStore())
        assertNotNull(offline.coordinatorRegistry())
        assertNotNull(offline.roomServerService())
    }

    @Test
    fun cachedDataAnnouncementIsReachableWhileDisconnected() = runBlocking<Unit> {
        seedStoredRows(radio)
        persistLastConnection(radio)

        val offline = access()
        offline.refresh()

        // The Nodes and Chats roots both pass `radioId() != null` as the known-radio half of this guard.
        assertTrue(shouldAnnounceCachedData(DeviceConnectionState.DISCONNECTED, offline.currentRadioId() != null))
    }

    @Test
    fun firstRunWithNoPairedRadioStaysEmptyAndSilent() = runBlocking<Unit> {
        val offline = access()
        offline.refresh()

        assertNull(offline.currentRadioId())
        assertNull(offline.contactService())
        assertNull(offline.conversationListStore())
        assertFalse(shouldAnnounceCachedData(DeviceConnectionState.DISCONNECTED, offline.currentRadioId() != null))
    }

    @Test
    fun resetAfterDataRestoreDropsTheCoordinatorRegistry() = runBlocking<Unit> {
        persistLastConnection(radio)
        val offline = access()
        offline.refresh()
        val first = assertNotNull(offline.coordinatorRegistry())
        val sameAfterRefresh = assertNotNull(offline.coordinatorRegistry())
        assertSame(first, sameAfterRefresh)

        assertEquals(0, offline.dataRestoredGeneration.value)
        offline.resetAfterDataRestore()
        val afterRestore = assertNotNull(offline.coordinatorRegistry())
        assertNotSame(first, afterRestore)
        assertEquals(radio, offline.currentRadioId())
        assertEquals(1, offline.dataRestoredGeneration.value)
    }

    @Test
    fun dropOfflinePartitionClearsTheCacheEvenIfALateRefreshStartedEarlier() = runBlocking<Unit> {
        persistLastConnection(radio)
        val offline = access()
        offline.refresh()
        assertEquals(radio, offline.currentRadioId())

        lastConnection.clear()
        offline.dropOfflinePartition()
        assertNull(offline.currentRadioId())
        assertNull(offline.contactService())

        // A refresh after the store is empty must stay empty; it cannot restore the forgotten radio.
        offline.refresh()
        assertNull(offline.currentRadioId())
    }

    @Test
    fun graphForReadsAPartitionAfterTheLastRadioIsGone() = runBlocking<Unit> {
        seedStoredRows(radio)
        persistLastConnection(radio)

        val offline = access()
        lastConnection.clear()
        offline.refresh()

        assertNull(offline.currentRadioId())
        assertNull(offline.contactService())

        val contacts = offline.graphFor(radio).contactService.getContacts(radio)
        assertEquals(2, contacts.size)
        assertTrue(contacts.any { it.name == CHAT_CONTACT_NAME })
        assertNotNull(offline.graphFor(radio).chatStore)
    }

    @Test
    fun threadCoordinatorRegistrySurvivesALiveReconnect() = runBlocking<Unit> {
        persistLastConnection(radio)
        val offline = access()
        offline.refresh()
        val first = offline.threadCoordinatorRegistry(radio)
        assertSame(first, offline.threadCoordinatorRegistry(radio))

        live = liveContainer(radio)
        assertSame(first, offline.threadCoordinatorRegistry(radio))
        live = null
        assertSame(first, offline.threadCoordinatorRegistry(radio))
    }

    @Test
    fun liveServicesWinAndRetireTheStandaloneGraph() = runBlocking<Unit> {
        seedStoredRows(radio)
        persistLastConnection(radio)

        val offline = access()
        offline.refresh()
        val offlineService = assertNotNull(offline.contactService())
        assertSame(offlineService, offline.contactService())

        val container = liveContainer(radio)
        live = container
        assertSame(container.contactService, offline.contactService())
        assertEquals(radio, offline.currentRadioId())

        // Mirrors iOS clearing `cachedOfflineStore` the moment services reappear: the graph the live
        // connection superseded must not come back on the next disconnect.
        live = null
        assertNotSame(offlineService, offline.contactService())
    }

    @Test
    fun switchingRadiosRebuildsThePartition() = runBlocking<Unit> {
        seedStoredRows(radio)
        persistLastConnection(radio)

        val offline = access()
        offline.refresh()
        val first = assertNotNull(offline.conversationListStore())
        assertEquals(1, first.fetchConversationContacts(radio).size)

        persistLastConnection(otherRadio)
        offline.refresh()
        assertEquals(otherRadio, offline.currentRadioId())
        // A different partition gets a different graph, so a store scoped to the old radioID cannot serve
        // the new one's screens.
        assertNotSame(first, offline.conversationListStore())
        assertTrue(assertNotNull(offline.contactService()).getContacts(otherRadio).isEmpty())
    }

    @Test
    fun demoFixturePartitionStillWinsOverTheLastConnectedOne() = runBlocking<Unit> {
        seedStoredRows(radio)
        persistLastConnection(radio)
        ScreenshotChatFixtures.seed(db)

        var fixturesActive = false
        val offline = access(fixtureRadioId = { ScreenshotChatFixtures.RADIO_ID.takeIf { fixturesActive } })
        offline.refresh()
        assertEquals(radio, offline.currentRadioId())

        fixturesActive = true
        assertEquals(ScreenshotChatFixtures.RADIO_ID, offline.currentRadioId())
        val demoContacts = assertNotNull(offline.contactService()).getContacts(ScreenshotChatFixtures.RADIO_ID)
        assertEquals(SCREENSHOT_CONTACT_COUNT, demoContacts.size)
    }

    private companion object {
        const val NOW_EPOCH_SECONDS: Double = 1_000.0
        const val PUBLIC_KEY_BYTES: Int = 32
        const val MAX_CHANNELS: Int = 8
        const val MAX_CONTACTS: Int = 100
        const val CHAT_CONTACT_NAME: String = "Alpha"
        const val MAPPED_CONTACT_NAME: String = "Bravo Ridge"
        const val MAPPED_LATITUDE: Double = 47.6205
        const val MAPPED_LONGITUDE: Double = -122.3493
        const val SCREENSHOT_CONTACT_COUNT: Int = 5
    }
}
