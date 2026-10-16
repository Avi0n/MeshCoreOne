package one.meshcore.shared.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.contact.ContactDeviceException
import one.meshcore.shared.contact.handleAdvertisement
import one.meshcore.shared.contact.runAdvertDeltaSync
import one.meshcore.shared.contact.syncContacts
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.meshcore.ContactMessage
import one.meshcore.shared.meshcore.GetMessageResult
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.transport.MeshLink
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The post-connect sync contract, driven through the real [ConnectionManager] ceremony over the fake link
 * and fake session against a real desktop Room database.
 *
 * Each test pins one behaviour a responder's data depends on: contacts sync from a watermark so a reconnect
 * cannot prune, the data partition survives a rotated public key, the backlog drain finishes before the
 * session takes over draining, the radio clock is corrected, and the channel read path is derived from the
 * radio rather than assumed.
 */
class ConnectSyncParityTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope

    // Every mint returns a distinct partition key, so a test that expects the connect path to *reuse* a
    // partition cannot pass by minting the one it was going to assert on.
    private var mintedRadioIds = 0
    private val fixedRadioId = radioId(1)

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-connect-sync", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancelAndJoinBlocking()
        db.close()
        dbFile.delete()
    }

    // MARK: - Contact sync watermark

    @Test
    fun aReconnectSyncsFromTheWatermarkAndKeepsAnEvictedContactsHistory() = runBlocking {
        val session = FakeMeshSession(
            contacts = listOf(alice()),
            backlog = mutableListOf(GetMessageResult.ContactMessageReceived(messageFromAlice())),
        )
        val manager = manager { session }

        manager.connect(DEVICE)
        assertEquals(1, db.contactDao().listByRadio(fixedRadioId).size, "the first connect stores the contact")
        assertEquals(1, db.messageDao().listByRadio(fixedRadioId).size, "the backlog message is stored")
        assertEquals(listOf<Long?>(null), session.getContactsSince, "a first-ever pairing has no watermark")

        // The radio rebuilds its contact table and reports it empty: the case that used to delete the
        // responder's contacts and cascade their whole message history.
        session.contacts = emptyList()
        manager.disconnect()
        manager.connect(DEVICE)

        assertEquals(
            1,
            db.contactDao().listByRadio(fixedRadioId).size,
            "an incremental sync must never prune a contact the radio stopped reporting",
        )
        assertEquals(
            1,
            db.messageDao().listByRadio(fixedRadioId).size,
            "the contact's message history must survive a radio that reports a short table",
        )
        assertEquals(
            incrementalContactSince(ALICE_LAST_MODIFIED),
            session.getContactsSince.last(),
            "a reconnect must sync from the watermark the previous pull recorded, rewound one second",
        )
    }

    @Test
    fun aSupersededConnectDoesNotOverwriteLastConnection() = runBlocking {
        val last = LastConnectionStore(InMemoryPreferencesStore())
        val gate = CompletableDeferred<Unit>()
        val slow = FakeMeshSession().apply { startGate = gate }
        val fast = FakeMeshSession()
        var next: FakeMeshSession = slow
        val manager = ConnectionManager(
            database = db,
            scope = scope,
            linkFactory = { FakeMeshLink() },
            sessionFactory = { next },
            nowEpochSeconds = { FIXED_CLOCK_SECONDS },
            ports = ConnectionManagerPorts(
                newRadioId = { radioId(++mintedRadioIds) },
                lastConnectionStore = last,
            ),
        )

        val slowJob = launch { manager.connect(DEVICE_A) }
        while (!slow.startEntered) delay(HANDSHAKE_HOLD_MS)
        next = fast
        manager.connect(DEVICE)
        assertEquals(DEVICE, last.handle())

        gate.complete(Unit)
        runCatching { slowJob.join() }
        assertEquals(DEVICE, last.handle(), "the abandoned handshake must not rewrite last-connection")
    }

    @Test
    fun aReconnectAtCapacityPrunesAnEvictedContact() = runBlocking {
        val session = FakeMeshSession(
            contacts = listOf(alice()),
            capabilities = defaultCapabilities().copy(maxContacts = 1),
        )
        val manager = manager { session }

        manager.connect(DEVICE)
        assertEquals(1, db.contactDao().listByRadio(fixedRadioId).size)
        session.contacts = emptyList()
        manager.disconnect()
        session.getContactsSince.clear()

        manager.connect(DEVICE)

        assertEquals(
            listOf<Long?>(null),
            session.getContactsSince,
            "a full local table must ignore lastContactSync so prune can drop offline evictions",
        )
        assertTrue(
            db.contactDao().listByRadio(fixedRadioId).isEmpty(),
            "at-capacity reconnect must prune a contact the radio no longer holds",
        )
    }

    @Test
    fun aVContactInTheLastSlotKeepsReconnectIncremental() = runBlocking {
        val selfKey = ByteArray(32) { 0x01 }
        val vKey = requireNotNull(one.meshcore.shared.contact.VContactIdentity.publicKey(selfKey))
        val bobKey = ByteArray(32) { 0x0A }
        val session = FakeMeshSession(
            contacts = listOf(alice(), sampleContact(bobKey, "Bob"), sampleContact(vKey, "V-Contact")),
            capabilities = defaultCapabilities().copy(maxContacts = 3),
        )
        val manager = manager { session }

        manager.connect(DEVICE)
        assertEquals(3, db.contactDao().listByRadio(fixedRadioId).size)
        manager.disconnect()
        session.getContactsSince.clear()

        manager.connect(DEVICE)

        assertEquals(1, session.getContactsSince.size)
        assertTrue(
            session.getContactsSince.single() != null,
            "V-contact in the last slot must not trip at-capacity; sync stays incremental",
        )
    }

    @Test
    fun aMidChannelAdvertDoesNotGetContactUntilTheConnectWindowEnds() = runBlocking {
        val advertKey = ByteArray(32) { 0xAB.toByte() }
        lateinit var manager: ConnectionManager
        val session = FakeMeshSession(contacts = listOf(alice())).apply {
            onGetChannels = {
                val contactService = requireNotNull(manager.services).contactService
                assertTrue(
                    contactService.isSyncingContacts,
                    "setSyncingContacts must stay held through the channel phase",
                )
                contactService.handleAdvertisement(fixedRadioId, advertKey)
                assertTrue(getContactKeys.isEmpty(), "per-key getContact must not run mid-connect")
                assertEquals(1, getContactsSince.size, "delta GET_CONTACTS must not interleave with channel sync")
            }
        }
        manager = manager { session }

        manager.connect(DEVICE)

        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
        assertTrue(session.getChannelsCallCount >= 1, "nRF52 Bluetooth connect must pipeline channel reads")
        assertTrue(session.getContactKeys.isEmpty())
        val contactsAfterConnect = session.getContactsSince.size
        requireNotNull(manager.services).contactService.runAdvertDeltaSync()
        assertTrue(
            session.getContactsSince.size > contactsAfterConnect,
            "queued 0x80 keys drain via GET_CONTACTS after the connect window",
        )
        assertTrue(session.getContactKeys.isEmpty(), "post-window drain uses GET_CONTACTS, not getContact")
    }

    @Test
    fun aUserConnectWithForceFullSyncIgnoresTheWatermarkAndPrunes() = runBlocking {
        val session = FakeMeshSession(contacts = listOf(alice()))
        val manager = manager { session }

        manager.connect(DEVICE)
        assertEquals(1, db.contactDao().listByRadio(fixedRadioId).size)
        session.contacts = emptyList()
        manager.disconnect()
        session.getContactsSince.clear()

        manager.connect(DEVICE, forceFullSync = true)

        assertEquals(
            listOf<Long?>(null),
            session.getContactsSince,
            "pair and saved-radio reconnect must ignore lastContactSync so prune can run",
        )
        assertTrue(
            db.contactDao().listByRadio(fixedRadioId).isEmpty(),
            "a forced full sync on connect must prune a contact the radio no longer holds",
        )
    }

    @Test
    fun aForcedFullSyncStillPrunesAnEvictedContact() = runBlocking {
        val session = FakeMeshSession(
            contacts = listOf(alice()),
            backlog = mutableListOf(GetMessageResult.ContactMessageReceived(messageFromAlice())),
        )
        val manager = manager { session }

        manager.connect(DEVICE)
        session.contacts = emptyList()

        // The user-driven refresh is the forced full sync, and it still reconciles a genuine eviction.
        val services = assertNotNull(manager.services)
        services.contactService.syncContacts(fixedRadioId, since = null)

        assertTrue(
            db.contactDao().listByRadio(fixedRadioId).isEmpty(),
            "a forced full sync must still prune a contact the radio no longer holds",
        )
        assertTrue(
            db.messageDao().listByRadio(fixedRadioId).isEmpty(),
            "the prune must cascade the pruned contact's messages",
        )
    }

    // MARK: - Three-axis identity

    @Test
    fun aRotatedPublicKeyKeepsTheRadioPartitionAndItsRows() = runBlocking {
        val originalKey = ByteArray(32) { 0x01 }
        val rotatedKey = ByteArray(32) { 0x77 }
        var key = originalKey
        val manager = manager {
            FakeMeshSession(selfInfo = defaultSelfInfo(key), contacts = listOf(alice()))
        }

        manager.connect(DEVICE)
        val partition = db.deviceDao().listAll().single().radioID
        assertEquals(1, db.contactDao().listByRadio(partition).size)

        // The radio's key rotated outside the app (an identity imported from another client, or a re-key).
        // The connection handle is unchanged, so the row — and its partition — must be reused.
        key = rotatedKey
        manager.disconnect()
        manager.connect(DEVICE)

        val row = db.deviceDao().listAll().single()
        assertEquals(partition, row.radioID, "a rotated public key must not mint a second data partition")
        assertTrue(row.publicKey.contentEquals(rotatedKey), "the row's reconciliation key follows the radio")
        assertEquals(
            1,
            db.contactDao().listByRadio(partition).size,
            "the partition's contacts must survive a key rotation",
        )
    }

    // MARK: - Backlog drain versus the session's own drain

    @Test
    fun theSessionDrainIsHandedOverOnlyAfterTheBacklogDrainAndTheMessageLandsOnce() = runBlocking {
        val session = FakeMeshSession(
            contacts = listOf(alice()),
            backlog = mutableListOf(GetMessageResult.ContactMessageReceived(messageFromAlice())),
        )
        val manager = manager { session }

        manager.connect(DEVICE)

        // The session's drain pops messages off the radio itself and delivers each only as an event, which
        // MessagePoller discards while its own drain holds the gate. Enabling it before the drain is what
        // loses a backlog message, so the hand-over must come last.
        assertEquals(
            FakeMeshSession.START_AUTO_FETCH,
            session.drainCallOrder.last(),
            "the session's drain must be enabled only after the backlog drain returned: " +
                "order was ${session.drainCallOrder}",
        )
        assertEquals(
            1,
            session.drainCallOrder.count { it == FakeMeshSession.START_AUTO_FETCH },
            "the hand-over runs once per connection",
        )
        assertEquals(
            1,
            db.messageDao().listByRadio(fixedRadioId).size,
            "a message queued on the radio at connect time reaches the store exactly once",
        )
    }

    // MARK: - Radio clock

    @Test
    fun aDriftedRadioClockIsCorrectedOnPromotion() = runBlocking {
        val session = FakeMeshSession().apply {
            deviceTimeEpochSeconds = FIXED_CLOCK_SECONDS.toLong() + DRIFT_BEYOND_TOLERANCE_SECONDS
        }
        val manager = manager { session }

        manager.connect(DEVICE)

        assertEquals(
            FIXED_CLOCK_SECONDS.toLong(),
            session.lastSetTimeEpoch,
            "a radio whose clock has drifted must be corrected to the phone's clock",
        )
    }

    @Test
    fun aRadioClockInsideToleranceIsLeftAlone() = runBlocking {
        val session = FakeMeshSession().apply {
            deviceTimeEpochSeconds = FIXED_CLOCK_SECONDS.toLong() + DRIFT_INSIDE_TOLERANCE_SECONDS
        }
        val manager = manager { session }

        manager.connect(DEVICE)

        assertTrue(session.getTimeCount >= 1, "the clock is read on every promotion")
        assertNull(session.lastSetTimeEpoch, "a clock inside tolerance must not be rewritten")
    }

    @Test
    fun aFailedWifiContactSyncStillCorrectsADriftedClock() = runBlocking {
        val session = driftedSessionThatFailsContacts()
        val manager = wifiManager { session }

        manager.connectViaWiFi(WIFI_HOST, WIFI_PORT)

        assertEquals(
            FIXED_CLOCK_SECONDS.toLong(),
            session.lastSetTimeEpoch,
            "WiFi still corrects the clock when contact sync failed, matching iOS promoteToReady",
        )
        manager.disconnect()
    }

    @Test
    fun aFailedBleContactSyncDoesNotCorrectTheClock() = runBlocking {
        val session = driftedSessionThatFailsContacts()
        val manager = manager { session }

        manager.connect(DEVICE)

        assertEquals(0, session.getTimeCount, "BLE does not read the clock after a failed contact sync")
        assertNull(session.lastSetTimeEpoch, "BLE does not rewrite the clock until resync promotes")
        manager.disconnect()
    }

    // MARK: - Derived channel read path

    @Test
    fun anNrf52OverBluetoothTakesThePipelinedChannelRead() = runBlocking {
        val session = FakeMeshSession(capabilities = defaultCapabilities().copy(model = NRF52_MODEL))
        val manager = manager { session }

        manager.connect(DEVICE)

        assertTrue(session.getChannelsCallCount >= 1, "nRF52 over Bluetooth reads channel slots pipelined")
    }

    @Test
    fun anEsp32ReconnectInsideTheSkipWindowDoesNotReReadChannels() = runBlocking {
        val session = FakeMeshSession(capabilities = defaultCapabilities().copy(model = ESP32_MODEL))
        val manager = manager { session }

        manager.connect(DEVICE)
        val firstReads = session.getChannelCallCount
        assertTrue(firstReads > 0, "the first ESP32 pass must read slots")

        manager.disconnect(DisconnectReason.RESYNC_FAILED)
        manager.connect(DEVICE)

        assertEquals(
            firstReads,
            session.getChannelCallCount,
            "an ESP32 reconnect inside the 30s window must skip the channel phase",
        )
    }

    @Test
    fun anEsp32OverBluetoothStaysSerialAndStillPersistsItsChannels() = runBlocking {
        // The ESP32 write characteristic carries no Write Commands, so a pipelined burst can go unanswered
        // and the radio's channels never reach the store. The serial path is what makes the team channel
        // appear on a first sync.
        val session = FakeMeshSession(capabilities = defaultCapabilities().copy(model = ESP32_MODEL)).apply {
            channelSlots[TEAM_CHANNEL_SLOT] = ChannelInfo(
                index = TEAM_CHANNEL_SLOT,
                name = TEAM_CHANNEL_NAME,
                secret = ByteArray(CHANNEL_SECRET_BYTES) { 0x42 },
            )
        }
        val manager = manager { session }

        manager.connect(DEVICE)

        assertEquals(0, session.getChannelsCallCount, "ESP32 over Bluetooth must not pipeline channel reads")
        val stored = db.channelDao().listByRadio(fixedRadioId).map { it.name }
        assertTrue(TEAM_CHANNEL_NAME in stored, "the serial read must persist the radio's channels: got $stored")
    }

    @Test
    fun aBackgroundedConnectSkipsTheChannelPhase() = runBlocking {
        val session = FakeMeshSession(contacts = listOf(alice())).apply {
            channelSlots[TEAM_CHANNEL_SLOT] = ChannelInfo(
                index = TEAM_CHANNEL_SLOT,
                name = TEAM_CHANNEL_NAME,
                secret = ByteArray(CHANNEL_SECRET_BYTES) { 0x42 },
            )
        }
        val manager = manager { session }
        manager.setAppActive(false)

        manager.connect(DEVICE)

        assertEquals(0, session.getChannelCallCount, "background connect must skip channel slot reads")
        assertEquals(0, session.getChannelsCallCount, "background connect must skip pipelined channel reads")
    }

    @Test
    fun theChannelReadPolicyMatchesThePlatformAndTransportPairs() {
        assertTrue(usePipelinedChannelRead(DevicePlatform.NRF52, TransportType.BLUETOOTH))
        assertEquals(false, usePipelinedChannelRead(DevicePlatform.NRF52, TransportType.WIFI))
        assertTrue(usePipelinedChannelRead(DevicePlatform.ESP32, TransportType.WIFI))
        assertEquals(false, usePipelinedChannelRead(DevicePlatform.ESP32, TransportType.BLUETOOTH))
        assertEquals(false, usePipelinedChannelRead(DevicePlatform.UNKNOWN, TransportType.BLUETOOTH))
        assertEquals(false, usePipelinedChannelRead(DevicePlatform.UNKNOWN, TransportType.WIFI))
    }

    @Test
    fun anUnrecognizedModelIsEsp32OverWifiAndUnknownOverBluetooth() {
        assertEquals(DevicePlatform.ESP32, resolveDevicePlatform("no such radio", TransportType.WIFI))
        assertEquals(DevicePlatform.UNKNOWN, resolveDevicePlatform("no such radio", TransportType.BLUETOOTH))
        assertEquals(DevicePlatform.NRF52, detectDevicePlatform("RAK 4631 Board"))
        assertEquals(DevicePlatform.ESP32, detectDevicePlatform("heltec v3"))
    }

    // MARK: - Fixtures

    private fun alice(): MeshContact = sampleContact(ALICE_KEY, "Alice").copy(lastModified = ALICE_LAST_MODIFIED)

    private fun messageFromAlice(): ContactMessage = ContactMessage(
        senderPublicKeyPrefix = ALICE_KEY.copyOf(SENDER_PREFIX_BYTES),
        pathLength = 0,
        textType = 0,
        senderTimestamp = MESSAGE_TIMESTAMP,
        text = "meet at the north gate",
    )

    private fun driftedSessionThatFailsContacts(): FakeMeshSession = FakeMeshSession().apply {
        deviceTimeEpochSeconds = FIXED_CLOCK_SECONDS.toLong() + DRIFT_BEYOND_TOLERANCE_SECONDS
        contactsFailures += ContactDeviceException(DEVICE_ERROR_CODE)
    }

    private fun manager(session: (MeshLink) -> FakeMeshSession): ConnectionManager = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = { FakeMeshLink() },
        sessionFactory = session,
        nowEpochSeconds = { FIXED_CLOCK_SECONDS },
        ports = ConnectionManagerPorts(
            newRadioId = { radioId(++mintedRadioIds) },
            resyncPolicy = delayedResync,
        ),
    )

    private fun wifiManager(session: (MeshLink) -> FakeMeshSession): ConnectionManager = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = { FakeMeshLink() },
        sessionFactory = session,
        nowEpochSeconds = { FIXED_CLOCK_SECONDS },
        ports = ConnectionManagerPorts(
            newRadioId = { radioId(++mintedRadioIds) },
            resyncPolicy = delayedResync,
            wifiLinkFactory = { host, port, heartbeat -> FakeWiFiLink(host, port, heartbeat) },
        ),
    )

    private companion object {
        const val DEVICE = "device-1"
        const val DEVICE_A = "device-a"
        const val HANDSHAKE_HOLD_MS = 50L
        const val WIFI_HOST = "10.0.0.5"
        const val WIFI_PORT = 4403
        const val DEVICE_ERROR_CODE = 0x03
        val delayedResync = ResyncPolicy(interval = 60.seconds, maxAttempts = 3)

        /** The [ordinal]-th distinct partition key a test mints, 1-based. */
        fun radioId(ordinal: Int): Uuid = Uuid.normalized("A0000000-0000-0000-0000-00000000000$ordinal")
        const val FIXED_CLOCK_SECONDS = 1_700_000_000.0

        val ALICE_KEY = ByteArray(32) { 0x09 }
        const val ALICE_LAST_MODIFIED = 1_699_000_000L
        const val MESSAGE_TIMESTAMP = 1_699_500_000L

        /** The prefix width an inbound message carries instead of the sender's full key. */
        const val SENDER_PREFIX_BYTES = 6

        // Wider than the manager's 5s tolerance, and comfortably inside it.
        const val DRIFT_BEYOND_TOLERANCE_SECONDS = 3_600L
        const val DRIFT_INSIDE_TOLERANCE_SECONDS = 2L

        const val TEAM_CHANNEL_SLOT = 1
        const val TEAM_CHANNEL_NAME = "Team"
        const val CHANNEL_SECRET_BYTES = 16
    }
}
