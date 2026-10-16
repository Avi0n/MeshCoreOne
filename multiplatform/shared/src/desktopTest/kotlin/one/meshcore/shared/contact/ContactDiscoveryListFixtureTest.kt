package one.meshcore.shared.contact

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.backup.BackupEnvelopeBuilder
import one.meshcore.shared.backup.BackupExportInput
import one.meshcore.shared.backup.DiscoveredNodeDto
import one.meshcore.shared.backup.RoomBackupImporter
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.DiscoveredNodeDao
import one.meshcore.shared.persistence.DiscoveredNodeEntity
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.RoomDiscoveredNodeStore
import one.meshcore.shared.persistence.meshDatabase
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Heard adverts reach the Discover list through [DiscoveredNodeEventCollector] and [RoomDiscoveredNodeStore].
 * The pushes are already decoded. This fixture builds no session, so it sends no advert and no discovery broadcast.
 */
class ContactDiscoveryListFixtureTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var pausing: RefreshPausingDao
    private lateinit var store: RoomDiscoveredNodeStore
    private lateinit var scope: CoroutineScope
    private lateinit var events: MutableSharedFlow<MeshEvent>
    private var heardOn: Uuid = RADIO_A
    private var contactServiceReads: Int = 0

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("discover-list", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        pausing = RefreshPausingDao(db.discoveredNodeDao())
        store = RoomDiscoveredNodeStore(pausing)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        events = MutableSharedFlow(extraBufferCapacity = 4)
        heardOn = RADIO_A
        contactServiceReads = 0
        scope.launch {
            DiscoveredNodeEventCollector(
                DiscoveredNodeCollectorInputs(
                    store = store,
                    radioId = { heardOn },
                    lookupLocalContact = { _, _ -> null },
                    nowEpochSeconds = { HEARD_AT },
                    discoveryNotifier = ContactDiscoveryNotifier.NoOp,
                ),
            ).collect(events)
        }
    }

    @AfterTest
    fun tearDown() {
        scope.cancelAndJoinBlocking()
        db.close()
        dbFile.delete()
    }

    @Test
    fun radioAAdvertStaysOffRadioBAndDoesNotPromote() = runBlocking {
        hear(HILLTOP, SHARED_KEY)
        val onA = listView(RADIO_A)
        onA.load()
        assertEquals(listOf(HILLTOP), onA.state.value.nodes.map { it.name })
        assertRadioBIsEmpty()

        val readsBeforeAdd = contactServiceReads
        onA.addNode(onA.state.value.nodes.single())
        assertEquals(readsBeforeAdd, contactServiceReads)
        assertNull(onA.state.value.addError)
        assertTrue(onA.state.value.addedPublicKeyHexes.isEmpty())

        onA.startObserving()
        heardOn = RADIO_B
        hear(OTHER_RIDGE, SHARED_KEY)
        assertEquals(listOf(HILLTOP), onA.state.value.nodes.map { it.name })
        assertEquals(listOf(HILLTOP), store.list(RADIO_A).map { it.name })
        assertEquals(listOf(OTHER_RIDGE), store.list(RADIO_B).map { it.name })
        assertRadioBShowsOnly(OTHER_RIDGE)
    }

    @Test
    fun repeatAdvertUpdatesTheSamePublicKey() = runBlocking {
        hear(HILLTOP, SHARED_KEY)
        val id = store.list(RADIO_A).single().id
        hear(HILLTOP_WEST, SHARED_KEY)

        val row = store.list(RADIO_A).single()
        assertEquals(id, row.id)
        assertEquals(HILLTOP_WEST, row.name)
        assertTrue(row.publicKey.contentEquals(SHARED_KEY))
        val onA = listView(RADIO_A)
        onA.load()
        assertEquals(listOf(HILLTOP_WEST), onA.state.value.nodes.map { it.name })
        assertRadioBIsEmpty()
    }

    @Test
    fun cancelledImportRefreshDoesNotPublishOrCrossRadios() = runBlocking {
        hear(HILLTOP, SHARED_KEY)
        assertEquals(setOf(HILLTOP), snapshotNames())
        val inserted = RoomBackupImporter.importEnvelope(
            db,
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    discoveredNodes = listOf(importedRidge()),
                ),
            ),
        ).discoveredNodesInserted
        assertEquals(1, inserted)
        // Import writes Room directly. A cancelled refresh must not replace the published list.
        assertEquals(setOf(HILLTOP), snapshotNames())

        val onB = listView(RADIO_B)
        onB.startObserving()
        assertTrue(onB.state.value.nodes.isEmpty())
        cancelRefresh()
        assertEquals(setOf(HILLTOP), snapshotNames())
        assertEquals(setOf(HILLTOP, IMPORTED_RIDGE), store.list(RADIO_A).map { it.name }.toSet())
        assertTrue(store.list(RADIO_B).isEmpty())
        assertTrue(onB.state.value.nodes.isEmpty())

        store.refresh()
        assertEquals(setOf(HILLTOP, IMPORTED_RIDGE), snapshotNames())
        assertTrue(store.snapshot.value.all { it.radioID == RADIO_A })
        val onA = listView(RADIO_A)
        onA.startObserving()
        assertEquals(setOf(HILLTOP, IMPORTED_RIDGE), onA.state.value.nodes.map { it.name }.toSet())
        assertTrue(onB.state.value.nodes.isEmpty())
    }

    private suspend fun hear(name: String, key: ByteArray) {
        val radio = heardOn
        events.emit(MeshEvent.NewContact(advert(key, name)))
        // The collector suspends inside Room, so emit returns before the snapshot is published.
        withTimeout(PUBLISH_WAIT_MS) {
            store.snapshot.first { rows -> rows.any { it.radioID == radio && it.name == name } }
        }
    }

    private fun listView(radio: Uuid): DiscoveryViewModel = DiscoveryViewModel(
        scope = scope,
        dependencies = DiscoveryViewModel.Dependencies(
            store = { store },
            contactService = {
                contactServiceReads += 1
                null
            },
            radioId = { radio },
            isRadioConnected = { false },
            loadFailedMessage = { LOAD_FAILED },
            errorText = { LOAD_FAILED },
        ),
        nowEpochSeconds = { HEARD_AT },
    )

    private suspend fun assertRadioBIsEmpty() {
        assertRadioBShowsOnly(null)
    }

    private suspend fun assertRadioBShowsOnly(name: String?) {
        val onB = listView(RADIO_B)
        onB.load()
        assertEquals(listOfNotNull(name), onB.state.value.nodes.map { it.name })
        onB.startObserving()
        assertEquals(listOfNotNull(name), onB.state.value.nodes.map { it.name })
    }

    private fun snapshotNames(): Set<String> = store.snapshot.value.map { it.name }.toSet()

    private suspend fun cancelRefresh() {
        pausing.pauseNextListAll = true
        val job = scope.launch { store.refresh() }
        assertTrue(pausing.entered.isCompleted)
        assertTrue(job.isActive)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
    }

    private fun importedRidge(): DiscoveredNodeDto = DiscoveredNodeDto(
        id = Uuid.normalized("E0000000-0000-0000-0000-000000000022"),
        radioID = RADIO_A,
        publicKey = IMPORTED_KEY,
        name = IMPORTED_RIDGE,
        typeRawValue = ContactType.REPEATER.raw,
        lastHeard = HEARD_AT.toDouble(),
        lastAdvertTimestamp = HEARD_AT,
        latitude = 0.0,
        longitude = 0.0,
        outPathLength = CONTACT_FLOOD_PATH_SENTINEL,
        outPath = ByteArray(0),
    )

    private fun advert(key: ByteArray, name: String): MeshContact = MeshContact(
        id = name,
        publicKey = key,
        typeRawValue = ContactType.REPEATER.raw,
        flags = 0,
        outPathLength = 0,
        outPath = ByteArray(0),
        advertisedName = name,
        lastAdvertisement = HEARD_AT,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = HEARD_AT,
    )

    private class RefreshPausingDao(private val real: DiscoveredNodeDao) : DiscoveredNodeDao by real {
        var pauseNextListAll: Boolean = false
        val entered = CompletableDeferred<Unit>()

        override suspend fun listAll(): List<DiscoveredNodeEntity> {
            if (pauseNextListAll) {
                pauseNextListAll = false
                entered.complete(Unit)
                suspendCancellableCoroutine<Unit> { }
            }
            return real.listAll()
        }
    }

    private companion object {
        const val PUBLIC_KEY_SIZE = 32
        const val HEARD_AT = 1_720_000_000L
        const val PUBLISH_WAIT_MS = 5_000L
        const val LOAD_FAILED = "Discover list failed"
        const val HILLTOP = "Hilltop"
        const val OTHER_RIDGE = "Other Ridge"
        const val HILLTOP_WEST = "Hilltop West"
        const val IMPORTED_RIDGE = "Imported Ridge"
        val RADIO_A: Uuid = Uuid.normalized("A0000000-0000-0000-0000-0000000000A1")
        val RADIO_B: Uuid = Uuid.normalized("B0000000-0000-0000-0000-0000000000B2")
        val SHARED_KEY: ByteArray = ByteArray(PUBLIC_KEY_SIZE) { 0x11 }
        val IMPORTED_KEY: ByteArray = ByteArray(PUBLIC_KEY_SIZE) { 0x22 }
    }
}
