package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.channel.ChannelListSink
import one.meshcore.shared.channel.ChannelSecret
import one.meshcore.shared.channel.ChannelSlotOccupantChanged
import one.meshcore.shared.channel.NoOpChannelListSink
import one.meshcore.shared.channel.NoOpChannelSlotOccupantChanged
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.chat.ChatErrorMessages
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.chat.Conversation
import one.meshcore.shared.chat.ConversationListViewModel
import one.meshcore.shared.chat.DraftStore
import one.meshcore.shared.chat.IngestNotifier
import one.meshcore.shared.chat.RecordingNotifier
import one.meshcore.shared.chat.ackCodeLeUInt32
import one.meshcore.shared.chat.deleteConversation
import one.meshcore.shared.chat.reload
import one.meshcore.shared.chat.statusEvents
import one.meshcore.shared.contact.DiscoveredNodeStore
import one.meshcore.shared.contact.InMemoryDiscoveredNodeStore
import one.meshcore.shared.contact.VContactIdentity
import one.meshcore.shared.contact.removeLocalContact
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.meshcore.CommandResult
import one.meshcore.shared.meshcore.GetMessageResult
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.ParsedRxLogData
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.DeviceEntity
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.NodeStatusSnapshotEntity
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.persistence.toEntity
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.testContact
import one.meshcore.shared.remotenode.testContactMessage
import one.meshcore.shared.remotenode.testSession
import one.meshcore.shared.settings.ConfigSections
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.DebugLogRetention
import one.meshcore.shared.settings.DebugLogSource
import one.meshcore.shared.settings.DebugLogStore
import one.meshcore.shared.settings.InMemoryDebugLogStore
import one.meshcore.shared.settings.MeshCoreNodeConfig
import one.meshcore.shared.tools.InMemoryRxLogStore
import one.meshcore.shared.tools.RxLogDecodeCache
import one.meshcore.shared.tools.RxLogEntry
import one.meshcore.shared.tools.RxLogStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Construction/teardown tests for the per-connection [ServiceContainer] graph, built against the
 * [FakeMeshSession] and a real Room database. Proves the graph starts (monitors, hydrate, backlog drain)
 * and tears down cleanly, the lifecycle [ConnectionManager] drives on every connect/disconnect.
 */
class ServiceContainerTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope

    private val radioID = Uuid.normalized("A0000000-0000-0000-0000-000000000009")

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("service-container", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancelAndJoinBlocking()
        db.close()
        dbFile.delete()
    }

    private fun makeContainer(
        session: FakeMeshSession = FakeMeshSession(),
        notifier: IngestNotifier = IngestNotifier.NoOp,
        lastConnectionStore: LastConnectionStore? = null,
        onContactsChanged: suspend () -> Unit = {},
        rxLogStore: RxLogStore? = null,
        discoveredNodes: DiscoveredNodeStore? = null,
        rxChannelCache: ChannelListSink = NoOpChannelListSink,
        nowEpochSeconds: () -> Double = { 1_000.0 },
        debugLogStore: DebugLogStore? = null,
        slotOccupantChanged: ChannelSlotOccupantChanged = NoOpChannelSlotOccupantChanged,
    ): ServiceContainer = ServiceContainer(
        session = session,
        database = db,
        identity = ServiceContainerIdentity(
            radioId = radioID,
            selfPublicKey = ByteArray(32) { 0x01 },
            selfNodeName = "Test",
            maxChannels = 8,
        ),
        runtime = ServiceContainerRuntime(
            scope = scope,
            nodePasswordStore = InMemoryNodePasswordStore(),
            lastConnectionStore = lastConnectionStore,
            nowEpochSeconds = nowEpochSeconds,
        ),
        edges = ServiceContainerEdges(
            notifiers = ServiceContainerNotifiers(notifier = notifier),
            sync = ServiceContainerSync(
                onContactsChanged = onContactsChanged,
                rxChannelCache = rxChannelCache,
                slotOccupantChanged = slotOccupantChanged,
                discoveredNodes = discoveredNodes,
                rxLogStore = rxLogStore,
                debugLogStore = debugLogStore,
            ),
        ),
    )

    @Test
    fun graphStartsDrainsBacklogAndTearsDownCleanly() = runBlocking {
        val session = FakeMeshSession()
        val container = makeContainer(session)

        container.start(
            connectionStates = flowOf(DeviceConnectionState.READY),
            initialState = DeviceConnectionState.CONNECTED,
        )
        // The session's own drain stays off until the backlog drain has run: it pops messages the live
        // monitor discards while that drain holds the gate.
        assertEquals(0, session.autoFetchStartCount)

        // The fake session's getMessage yields no backlog; the drain returns zero and does not hang.
        assertEquals(0, container.liveSync.pollBacklog())
        assertNotNull(container.liveSync.syncChannelsFromDevice())

        container.liveSync.startAutoMessageFetching()
        assertEquals(1, session.autoFetchStartCount)

        container.start(
            connectionStates = flowOf(DeviceConnectionState.READY),
            initialState = DeviceConnectionState.READY,
        )
        assertEquals(1, session.autoFetchStartCount, "a second start must not re-arm auto-fetch")
        container.liveSync.reconcileLiveListeners(
            connectionStates = flowOf(DeviceConnectionState.READY),
            initialState = DeviceConnectionState.READY,
            enableAutoFetch = true,
        )
        assertEquals(1, session.autoFetchStartCount, "healthy listeners must not restart auto-fetch")

        // Heard-repeats service is configured on start; non-matching RX rows are no-ops (no throw).
        assertNull(
            container.liveSync.processRxLogEntryForRepeats(
                one.meshcore.shared.tools.RxLogEntry(
                    id = Uuid.normalized("C0000000-0000-0000-0000-000000000003"),
                    radioID = radioID,
                    receivedAtEpochMs = 1L,
                    snr = null,
                    rssi = null,
                    routeType = 0,
                    payloadType = one.meshcore.shared.tools.RxLogPayloadType.TRACE,
                    pathLength = 0,
                    packetHash = "00",
                    packetPayloadSize = 0,
                ),
            ),
        )

        // Node-config is constructed per connection over the session + channel/contact stores.
        val exported = container.nodeConfigService.exportConfig(ConfigSections(nodeIdentity = true))
        assertEquals("Test", exported.name)
        assertNotNull(exported.publicKey)

        // Tear down releases every per-connection collector, drain, and coordinator without throwing.
        container.tearDown()
        assertEquals(1, session.autoFetchStopCount)
    }

    /**
     * iOS `startEventMonitoring` starts RxLogService and AdvertisementService (0x8A / hops) on connect,
     * before `runFullSync`. Kotlin used to wait for AppContainer to rebind a replay-0 SharedFlow, so a
     * packet between `services =` and that collect was dropped.
     */
    @Test
    fun startPersistsRxLogAndUpsertsANewAdvertOnConnect() = runBlocking {
        val session = FakeMeshSession()
        val rxLogStore = InMemoryRxLogStore()
        val discoveredNodes = InMemoryDiscoveredNodeStore()
        val container = makeContainer(
            session = session,
            rxLogStore = rxLogStore,
            discoveredNodes = discoveredNodes,
            rxChannelCache = RxLogDecodeCache(),
        )

        container.start(
            connectionStates = flowOf(DeviceConnectionState.READY),
            initialState = DeviceConnectionState.CONNECTED,
        )
        withTimeout(5_000) {
            while (session.eventSubscribers.value == 0) delay(5)
        }

        session.emit(
            MeshEvent.RxLogData(
                ParsedRxLogData(
                    snr = 1.5,
                    rssi = -80,
                    rawPayload = byteArrayOf(1),
                    routeType = 1,
                    payloadType = 3,
                    payloadVersion = 0,
                    payloadTypeBits = 0,
                    pathLength = 0,
                    pathNodes = emptyList(),
                    packetPayload = byteArrayOf(9),
                    packetHash = "CAFEBABE",
                ),
            ),
        )
        val publicKey = ByteArray(32) { 0x44 }
        session.emit(
            MeshEvent.NewContact(
                MeshContact(
                    id = "44".repeat(32),
                    publicKey = publicKey,
                    typeRawValue = ContactType.REPEATER.raw,
                    flags = 0,
                    outPathLength = 0,
                    outPath = ByteArray(0),
                    advertisedName = "Hilltop",
                    lastAdvertisement = 50,
                    latitude = 0.0,
                    longitude = 0.0,
                    lastModified = 50,
                ),
            ),
        )

        withTimeout(5_000) {
            while (rxLogStore.list(radioID).isEmpty() || discoveredNodes.list(radioID).isEmpty()) {
                delay(5)
            }
        }
        assertEquals("CAFEBABE", rxLogStore.list(radioID).single().packetHash)
        assertEquals("Hilltop", discoveredNodes.list(radioID).single().name)

        container.tearDown()
    }

    @Test
    fun tearDownCancelsTheRxLogCatchUpRead() = runBlocking {
        val store = BlockingListRxLogStore()
        val container = makeContainer(rxLogStore = store)

        container.start(
            connectionStates = flowOf(DeviceConnectionState.READY),
            initialState = DeviceConnectionState.CONNECTED,
        )
        withTimeout(TIMEOUT_MS) { store.listStarted.await() }

        container.tearDown()

        withTimeout(TIMEOUT_MS) { store.listCancelled.await() }
        assertEquals(radioID, store.requestedRadioID)
        assertNull(container.rxLogCatchUpJob)
    }

    /**
     * iOS `onConnectionEstablished` exports the radio private key after `startEventMonitoring` and
     * before `runFullSync`, so RX-log DMs that land in the sync window can ECDH-decrypt. Kotlin used
     * to wait for AppContainer to collect Connected, which can run after `start()` returns.
     */
    @Test
    fun startExportsTheRadioPrivateKeyForRxLogDecrypt() = runBlocking {
        val session = FakeMeshSession()
        val decodeCache = RxLogDecodeCache()
        val container = makeContainer(session = session, rxChannelCache = decodeCache)

        assertNull(decodeCache.snapshot().myPrivateKey)
        container.start(
            connectionStates = flowOf(DeviceConnectionState.READY),
            initialState = DeviceConnectionState.CONNECTED,
        )

        assertEquals(1, session.exportPrivateKeyCount)
        val expected = ByteArray(32)
        val actual = decodeCache.snapshot().myPrivateKey
        assertNotNull(actual)
        assertTrue(actual.contentEquals(expected))

        container.tearDown()
    }

    /**
     * iOS AppState installs `setSlotOccupantChangedHandler` on the new container before `runFullSync`.
     * Kotlin constructs with the handler so a connect-time prune cannot race a host rebind.
     */
    @Test
    fun deletingAChannelClearsItsDraftWithoutAHostRebind() = runBlocking {
        val drafts = DraftStore.create(InMemoryPreferencesStore())
        val slot = 3
        val container = makeContainer(
            slotOccupantChanged = ChannelSlotOccupantChanged { radio, slots ->
                drafts.clearChannelDrafts(radio, slots)
            },
        )
        container.channelService.setChannelWithSecret(
            radioID,
            slot,
            "Ops",
            ByteArray(ChannelSecret.SIZE_BYTES) { 0xAB.toByte() },
        )
        val conversation = ChatConversationID.channel(radioID, slot)
        drafts.setDraft("meet at the north ridge", conversation)

        container.channelService.clearChannel(radioID, slot)

        assertNull(drafts.draft(conversation))
        container.tearDown()
    }

    /**
     * iOS `startEventMonitoring` prunes node status snapshots older than one year on connect so
     * history charts and backup rows cannot grow without bound.
     */
    @Test
    fun startPrunesNodeStatusSnapshotsOlderThanOneYear() = runBlocking {
        val now = 100_000_000.0
        val nodeKey = ByteArray(32) { 0x42 }
        val oldId = Uuid.normalized("D0000000-0000-0000-0000-000000000001")
        val recentId = Uuid.normalized("D0000000-0000-0000-0000-000000000002")
        db.nodeStatusSnapshotDao().upsert(
            NodeStatusSnapshotEntity(
                id = oldId,
                timestamp = 1.0,
                nodePublicKey = nodeKey,
                batteryMillivolts = 3600,
            ),
        )
        db.nodeStatusSnapshotDao().upsert(
            NodeStatusSnapshotEntity(
                id = recentId,
                timestamp = now - 86_400.0,
                nodePublicKey = nodeKey,
                batteryMillivolts = 3800,
            ),
        )
        val container = makeContainer(nowEpochSeconds = { now })

        container.start(
            connectionStates = flowOf(DeviceConnectionState.READY),
            initialState = DeviceConnectionState.CONNECTED,
        )

        val remaining = container.nodeSnapshotService.fetchSnapshots(nodeKey).map { it.id }
        assertEquals(listOf(recentId), remaining)

        container.tearDown()
    }

    /**
     * iOS `startEventMonitoring` prunes debug logs (7-day window, 50k ceiling) on connect, not after
     * sync reaches Ready — a stuck sync would otherwise never age the log.
     */
    @Test
    fun startPrunesDebugLogEntriesOlderThanTheRetentionWindow() = runBlocking {
        val store = InMemoryDebugLogStore()
        val nowMs = 2_000_000_000L
        store.saveDebugLogEntries(
            listOf(
                DebugLogEntryDto.of(
                    id = Uuid.normalized("E0000000-0000-0000-0000-000000000001"),
                    timestamp = nowMs - DebugLogRetention.WINDOW_MS - 1,
                    level = DebugLogLevel.INFO,
                    source = DebugLogSource("test", "c"),
                    message = "old",
                ),
                DebugLogEntryDto.of(
                    id = Uuid.normalized("E0000000-0000-0000-0000-000000000002"),
                    timestamp = nowMs - 1_000,
                    level = DebugLogLevel.INFO,
                    source = DebugLogSource("test", "c"),
                    message = "recent",
                ),
            ),
        )
        val container = makeContainer(
            nowEpochSeconds = { nowMs / 1000.0 },
            debugLogStore = store,
        )

        container.start(
            connectionStates = flowOf(DeviceConnectionState.READY),
            initialState = DeviceConnectionState.CONNECTED,
        )

        val remaining = store.fetchDebugLogEntries(since = 0, limit = 10)
        assertEquals(listOf("recent"), remaining.map { it.message })

        container.tearDown()
    }

    @Test
    fun missingAutoFetchRestartsOnForegroundReconcile() = runBlocking {
        val session = FakeMeshSession()
        val container = makeContainer(session)
        container.start(
            connectionStates = flowOf(DeviceConnectionState.READY),
            initialState = DeviceConnectionState.CONNECTED,
        )
        assertTrue(container.isEventMonitoringActive)
        assertFalse(container.isAutoFetching)

        container.liveSync.reconcileLiveListeners(
            connectionStates = flowOf(DeviceConnectionState.READY),
            initialState = DeviceConnectionState.READY,
            enableAutoFetch = true,
        )
        assertEquals(1, session.autoFetchStartCount)
        assertTrue(container.isAutoFetching)
    }

    @Test
    fun nodeConfigImportNotifiesContactsChanged() = runBlocking {
        var notified = 0
        val container = makeContainer(onContactsChanged = { notified++ })

        container.nodeConfigService.importConfig(
            MeshCoreNodeConfig(),
            ConfigSections(contacts = true),
            radioID,
        )

        assertEquals(1, notified)
    }

    /**
     * The connect-path channel phase must retry the slots a retry can recover: a slot left unread has no
     * local row, so every inbound message on it is suppressed as an unconfigured channel.
     */
    @Test
    fun theConnectPathChannelSyncRetriesASlotThatTimedOut() = runBlocking {
        val session = FakeMeshSession()
        // The pipelined read answers nothing, so slot 1 is reconciled by a serial read that times out for
        // the whole first pass (one failure per fetchChannel attempt) and succeeds on the retry.
        session.channelBatch = CommandResult.ChannelsResult(received = emptyList(), missing = listOf(1))
        session.channelSlotFailures[1] = MutableList(FETCH_ATTEMPTS_PER_SLOT) {
            MeshBridgeException(BridgeErrorCode.TIMEOUT, "no reply")
        }
        session.channelSlots[1] = ChannelInfo(
            index = 1,
            name = "Recovered",
            secret = ByteArray(CHANNEL_SECRET_BYTES) { 0xAB.toByte() },
        )
        val container = makeContainer(session)

        val result = assertNotNull(container.liveSync.syncChannelsFromDevice())

        assertTrue(result.isComplete)
        assertEquals("Recovered", db.channelDao().getByRadioAndIndex(radioID, 1)?.name)
    }

    /**
     * Send-queue NOT_FOUND disambiguation must read the radio (iOS `fetchChannel(index:)`), not Room.
     * A leftover local row for a deleted slot would otherwise park the envelope forever as pool
     * exhaustion. The resolver calls [ChannelService.fetchChannel], which is this device read.
     */
    @Test
    fun channelDisambiguationReadsTheRadioEvenWhenRoomHasTheSlot() = runBlocking {
        db.channelDao().upsert(
            one.meshcore.shared.persistence.ChannelEntity(
                id = Uuid.normalized("C1111111-1111-1111-1111-111111111111"),
                radioID = radioID,
                index = 1,
                name = "Stale",
                secret = ByteArray(CHANNEL_SECRET_BYTES) { 0x11 },
                isEnabled = true,
                unreadCount = 0,
            ),
        )
        val session = FakeMeshSession()
        val container = makeContainer(session)

        assertNull(container.channelService.fetchChannel(1))
        assertTrue(session.getChannelCallCount >= 1)
        assertNotNull(db.channelDao().getByRadioAndIndex(radioID, 1))
        return@runBlocking
    }

    /**
     * iOS `stopEventMonitoring` only stops the ACK expiry checker — never failAllPending — so a BLE
     * reconnect does not mark end-to-end-waiting DMs `.failed`. ServiceContainer.tearDown must match.
     */
    @Test
    fun tearDownLeavesInFlightDmSent() = runBlocking {
        val container = makeContainer()
        container.start(
            connectionStates = flowOf(DeviceConnectionState.READY),
            initialState = DeviceConnectionState.CONNECTED,
        )

        val messageID = ChatFixtures.freshUuid()
        val contactID = ChatFixtures.CONTACT
        val ack = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        container.chatStore.saveMessage(
            ChatFixtures.message(
                id = messageID,
                contactID = contactID,
                status = MessageStatus.SENT,
            ).copy(radioID = radioID, ackCode = ack.ackCodeLeUInt32()),
        )
        container.messageService.trackPendingAck(
            messageID = messageID,
            contactID = contactID,
            ackCode = ack,
            timeoutSeconds = 30.0,
        )
        assertEquals(1, container.messageService.pendingAckCount())

        container.tearDown()

        assertFalse(container.messageService.isAckExpiryCheckingActive)
        // In-memory pending map dies with the container; Room must stay `.sent` (not `.failed`).
        assertEquals(MessageStatus.SENT, container.chatStore.fetchMessage(messageID)?.status)
    }

    @Test
    fun tearDownFinishesMessageStatusSubscribers() = runBlocking {
        val container = makeContainer()
        container.start(
            connectionStates = flowOf(DeviceConnectionState.READY),
            initialState = DeviceConnectionState.CONNECTED,
        )
        val events = container.messageService.statusEvents()
        container.tearDown()
        assertTrue(events.receiveCatching().isClosed)
    }

    /**
     * Connect `getMessage` backlog CLI frames never emit MeshEvent.ContactMessageReceived, so the
     * poller must fan them to admin handlers (iOS `handleIncomingCLIMessage`). Live pushes stay on
     * NodeCliCoordinator.
     */
    @Test
    fun backlogCliRepliesReachRepeaterAdminHandler() = runBlocking {
        val publicKey = ByteArray(32) { 0x42 }
        db.contactDao().upsert(
            testContact(radioID = radioID, publicKey = publicKey, type = ContactType.REPEATER).toEntity(),
        )
        val session = FakeMeshSession(
            backlog = mutableListOf(
                GetMessageResult.ContactMessageReceived(
                    testContactMessage("3A|> 22dBm", publicKey.copyOf(6)),
                ),
            ),
        )
        val container = makeContainer(session)
        val seen = mutableListOf<String>()
        container.repeaterAdminService.setCLIHandler { message, _ -> seen += message.text }

        val count = container.liveSync.pollBacklog()

        assertEquals(1, count)
        assertEquals(listOf("> 22dBm"), seen)
    }

    @Test
    fun backlogCliRepliesReachRoomAdminHandler() = runBlocking {
        val publicKey = ByteArray(32) { 0x43 }
        db.contactDao().upsert(
            testContact(radioID = radioID, publicKey = publicKey, type = ContactType.ROOM).toEntity(),
        )
        val session = FakeMeshSession(
            backlog = mutableListOf(
                GetMessageResult.ContactMessageReceived(
                    testContactMessage("0F|users: 3", publicKey.copyOf(6)),
                ),
            ),
        )
        val container = makeContainer(session)
        val seen = mutableListOf<String>()
        container.roomAdminService.setCLIHandler { _, text -> seen += text }

        val count = container.liveSync.pollBacklog()

        assertEquals(1, count)
        assertEquals(listOf("users: 3"), seen)
    }

    /**
     * Production ContactCleanupCoordinator must clear tray notifications, update the badge, and remove
     * any remote-node session when a contact is deleted (iOS ContactCleanupCoordinator parity).
     */
    @Test
    fun deleteContactClearsNotificationsAndRemoteNodeSession() = runBlocking {
        val notifier = RecordingNotifier()
        val container = makeContainer(notifier = notifier)
        val contactID = Uuid.normalized("B0000000-0000-0000-0000-000000000001")
        val publicKey = ByteArray(32) { 0x42 }
        db.contactDao().upsert(
            ContactDto(
                id = contactID,
                radioID = radioID,
                publicKey = publicKey,
                name = "Repeater",
                typeRawValue = ContactType.REPEATER.raw,
                flags = 0,
                outPathLength = 0,
                outPath = ByteArray(0),
                lastAdvertTimestamp = 0,
                latitude = 0.0,
                longitude = 0.0,
                lastModified = 0,
                isBlocked = false,
                isMuted = false,
                isFavorite = false,
                unreadCount = 0,
            ).toEntity(),
        )
        container.nodeStore.saveSession(
            testSession(
                id = Uuid.normalized("C0000000-0000-0000-0000-000000000002"),
                radioID = radioID,
                publicKey = publicKey,
            ),
        )
        assertNotNull(container.nodeStore.fetchSessionByPublicKey(publicKey))

        container.contactService.removeLocalContact(contactID, publicKey)

        assertNull(container.nodeStore.fetchSessionByPublicKey(publicKey))
        assertEquals(listOf(contactID), notifier.removedDeliveredForContactIDs)
        assertTrue(notifier.badgeUpdates >= 1)
    }

    /**
     * A room CLI reply that lands after its request timed out still reaches the room settings form,
     * because the container gives [one.meshcore.shared.room.RoomAdminService] the CLI coordinator to
     * observe every inbound reply (iOS `SyncCoordinator` CLI fan-out).
     */
    @Test
    fun aLateRoomCliReplyReachesTheRoomAdminHandler() = runBlocking {
        val session = FakeMeshSession()
        val container = makeContainer(session)
        val publicKey = ByteArray(32) { 0x55 }
        db.contactDao().upsert(
            testContact(radioID = radioID, publicKey = publicKey, type = ContactType.ROOM).toEntity(),
        )
        container.nodeStore.saveSession(
            testSession(
                id = Uuid.normalized("C0000000-0000-0000-0000-000000000004"),
                radioID = radioID,
                publicKey = publicKey,
                role = RemoteNodeRole.ROOM_SERVER,
            ),
        )
        val received = mutableListOf<String>()
        container.roomAdminService.setCLIHandler { _, text -> received += text }
        // Emit only once both node collectors are subscribed; an event pushed earlier reaches nobody.
        awaitCondition { session.eventSubscribers.value >= NODE_EVENT_COLLECTORS }

        // No request is pending: this is the reply that arrived after its section already timed out.
        session.emit(MeshEvent.ContactMessageReceived(testContactMessage(ROOM_CLI_REPLY, publicKey.copyOf(6))))

        awaitCondition { received.isNotEmpty() }
        assertEquals(listOf(ROOM_CLI_REPLY), received)
    }

    /**
     * Confirming the leave-room dialog must reach the room server: the container's conversation-list store
     * logs out, drops the session, and removes the backing contact from the radio (Swift
     * `ChatConversationActions.leaveRoom`). A local-only write would leave the user logged in to a room
     * that has vanished from their list.
     */
    @Test
    fun confirmingLeaveRoomLogsOutAndRemovesTheBackingContact() = runBlocking {
        val session = FakeMeshSession()
        val notifier = RecordingNotifier()
        val container = makeContainer(session, notifier)
        val publicKey = ByteArray(32) { 0x77 }
        db.contactDao().upsert(
            testContact(radioID = radioID, publicKey = publicKey, type = ContactType.ROOM).toEntity(),
        )
        container.nodeStore.saveSession(
            testSession(
                id = ROOM_SESSION_ID,
                radioID = radioID,
                publicKey = publicKey,
                role = RemoteNodeRole.ROOM_SERVER,
                isConnected = true,
            ),
        )
        val viewModel = ConversationListViewModel(
            ConversationListViewModel.Dependencies(
                errorMessages = { ChatErrorMessages.developerEnglish() },
                store = { container.conversationListStore },
                radioId = { radioID },
                isReady = { true },
            ),
        )
        viewModel.reload()
        val room = assertNotNull(container.conversationListStore.fetchRooms(radioID).firstOrNull())

        viewModel.deleteConversation(Conversation.Room(room))

        assertNull(viewModel.state.value.errorMessage)
        assertEquals(listOf(publicKey.toList()), session.logoutDestinations.map { it.toList() })
        assertEquals(listOf(publicKey.toList()), session.removedContactKeys.map { it.toList() })
        assertNull(container.nodeStore.fetchSessionByPublicKey(publicKey))
        assertTrue(container.conversationListStore.fetchRooms(radioID).isEmpty())
        assertTrue(container.conversationListStore.fetchConversationContacts(radioID).isEmpty())
        assertTrue(notifier.badgeUpdates >= 1)
    }

    /**
     * After import/regenerate, Device Info and later sends must show the radio's new key, not the
     * connect-time snapshot. iOS reads `connectedDevice.publicKey` after `importPrivateKey` refreshes
     * self-info.
     */
    @Test
    fun identityImportRefreshesTheLiveSelfPublicKey() = runBlocking {
        val oldKey = ByteArray(32) { 0x01 }
        val newKey = ByteArray(32) { 0x22 }
        val deviceId = Uuid.normalized("A0000000-0000-0000-0000-000000000011")
        db.deviceDao().upsert(identityDevice(id = deviceId, radioID = radioID, publicKey = oldKey))
        val session = FakeMeshSession(selfInfo = defaultSelfInfo(newKey))
        val container = makeContainer(session)
        kotlin.test.assertContentEquals(oldKey, container.selfPublicKey)

        container.reconcileAfterIdentityImport(currentDeviceId = deviceId)

        kotlin.test.assertContentEquals(newKey, container.selfPublicKey)
        kotlin.test.assertContentEquals(newKey, db.deviceDao().getById(deviceId)?.publicKey)
    }

    /**
     * Identity rotation must delete the local V-contact derived from the retired self key through
     * the store. Routing that drop through ContactService.removeLocalContact can no-op when the
     * Device row still holds the old key, and a persist fault must not block the live self-key refresh.
     */
    @Test
    fun identityImportDropsTheStaleVContact() = runBlocking {
        val oldKey = ByteArray(32) { 0x01 }
        val newKey = ByteArray(32) { 0x22 }
        val deviceId = Uuid.normalized("A0000000-0000-0000-0000-000000000011")
        val staleVContactId = Uuid.normalized("D0000000-0000-0000-0000-000000000021")
        val staleVKey = requireNotNull(VContactIdentity.publicKey(oldKey))
        db.deviceDao().upsert(identityDevice(id = deviceId, radioID = radioID, publicKey = oldKey))
        db.contactDao().upsert(
            ContactDto(
                id = staleVContactId,
                radioID = radioID,
                publicKey = staleVKey,
                name = "v.contact",
                typeRawValue = ContactType.CHAT.raw,
                flags = 0,
                outPathLength = 0,
                outPath = ByteArray(0),
                lastAdvertTimestamp = 0,
                latitude = 0.0,
                longitude = 0.0,
                lastModified = 0,
                isBlocked = false,
                isMuted = false,
                isFavorite = false,
                unreadCount = 0,
            ).toEntity(),
        )
        val session = FakeMeshSession(selfInfo = defaultSelfInfo(newKey))
        val container = makeContainer(session)

        container.reconcileAfterIdentityImport(currentDeviceId = deviceId)

        assertNull(db.contactDao().getById(staleVContactId))
        kotlin.test.assertContentEquals(newKey, container.selfPublicKey)
    }

    @Test
    fun identityImportAdoptsAGhostRadioPartition() = runBlocking {
        val oldKey = ByteArray(32) { 0x01 }
        val newKey = ByteArray(32) { 0x22 }
        val liveId = Uuid.normalized("A0000000-0000-0000-0000-000000000011")
        val ghostId = Uuid.normalized("B0000000-0000-0000-0000-000000000011")
        val ghostRadio = Uuid.normalized("C0000000-0000-0000-0000-000000000011")
        db.deviceDao().upsert(identityDevice(id = liveId, radioID = radioID, publicKey = oldKey))
        db.deviceDao().upsert(
            identityDevice(id = ghostId, radioID = ghostRadio, publicKey = newKey).copy(isActive = false),
        )
        val session = FakeMeshSession(selfInfo = defaultSelfInfo(newKey))
        val last = LastConnectionStore(InMemoryPreferencesStore())
        last.persist(liveId, radioID, "Test", handle = "ble-1")
        val container = makeContainer(session, lastConnectionStore = last)

        val adopted = container.reconcileAfterIdentityImport(currentDeviceId = liveId)

        assertEquals(ghostRadio, adopted)
        assertEquals(ghostRadio, container.radioId)
        assertEquals(ghostRadio, container.chatStore.radioID)
        assertEquals(ghostRadio, container.sendQueue.radioID)
        assertEquals(ghostRadio, last.radioId())
        assertNull(db.deviceDao().getById(ghostId))
        assertEquals(ghostRadio, db.deviceDao().getById(liveId)?.radioID)
    }

    private fun identityDevice(id: Uuid, radioID: Uuid, publicKey: ByteArray) = DeviceEntity(
        id = id,
        radioID = radioID,
        publicKey = publicKey,
        nodeName = "Test",
        firmwareVersion = 0,
        firmwareVersionString = "",
        manufacturerName = "",
        buildDate = "",
        maxContacts = 0,
        maxChannels = 0,
        frequency = 0,
        bandwidth = 0,
        spreadingFactor = 0,
        codingRate = 0,
        txPower = 0,
        maxTxPower = 0,
        latitude = 0.0,
        longitude = 0.0,
        blePin = 0,
        clientRepeat = false,
        pathHashMode = 0,
        manualAddContacts = false,
        autoAddConfig = 0,
        autoAddMaxHops = 0,
        multiAcks = 0,
        telemetryModeBase = 0,
        telemetryModeLoc = 0,
        telemetryModeEnv = 0,
        advertLocationPolicy = 0,
        lastConnected = 0.0,
        lastContactSync = 0,
        isActive = true,
    )

    @Test
    fun aDebugLogPruneFaultStillStartsMonitors() = runBlocking {
        val container = makeContainer(debugLogStore = ThrowingDebugLogStore(IllegalStateException("db locked")))
        container.start(
            connectionStates = flowOf(DeviceConnectionState.READY),
            initialState = DeviceConnectionState.CONNECTED,
        )
        assertTrue(container.isEventMonitoringActive)
    }

    @Test
    fun aCancelledDebugLogPruneDoesNotStartMonitors() = runBlocking {
        val container = makeContainer(debugLogStore = ThrowingDebugLogStore(CancellationException("stopped")))
        val thrown = assertFailsWith<CancellationException> {
            container.start(
                connectionStates = flowOf(DeviceConnectionState.READY),
                initialState = DeviceConnectionState.CONNECTED,
            )
        }
        assertEquals("stopped", thrown.message)
        assertFalse(container.isEventMonitoringActive)
    }

    @Test
    fun aDebugLogPruneErrorDoesNotStartMonitors() = runBlocking {
        val container = makeContainer(debugLogStore = ThrowingDebugLogStore(ConnectPruneProbeError()))
        assertFailsWith<ConnectPruneProbeError> {
            container.start(
                connectionStates = flowOf(DeviceConnectionState.READY),
                initialState = DeviceConnectionState.CONNECTED,
            )
        }
        assertFalse(container.isEventMonitoringActive)
    }

    private class ThrowingDebugLogStore(private val failure: Throwable) : DebugLogStore {
        override suspend fun saveDebugLogEntries(entries: List<DebugLogEntryDto>) = Unit

        override suspend fun fetchDebugLogEntries(since: Long, limit: Int): List<DebugLogEntryDto> = emptyList()

        override suspend fun countDebugLogEntries(): Int = 0

        override suspend fun pruneDebugLogEntries(olderThanMillis: Long, keepCount: Int): Unit = throw failure

        override suspend fun clearDebugLogEntries() = Unit
    }

    private class BlockingListRxLogStore : RxLogStore {
        override val snapshot: StateFlow<List<RxLogEntry>> = MutableStateFlow(emptyList())
        val listStarted = CompletableDeferred<Unit>()
        val listCancelled = CompletableDeferred<Unit>()
        var requestedRadioID: Uuid? = null

        override suspend fun list(radioID: Uuid): List<RxLogEntry> {
            requestedRadioID = radioID
            listStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                listCancelled.complete(Unit)
            }
        }

        override suspend fun clear(radioID: Uuid) = Unit

        override suspend fun append(entry: RxLogEntry) = Unit

        override suspend fun redecrypt(decode: RxLogDecodeCache.DecodeSnapshot): List<RxLogEntry> = emptyList()
    }

    private class ConnectPruneProbeError : Error("probe")

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(TIMEOUT_MS) {
            while (!condition()) delay(POLL_MS)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
        const val ROOM_CLI_REPLY = "guest password: hunter2"
        val ROOM_SESSION_ID = Uuid.normalized("C0000000-0000-0000-0000-000000000005")

        // RemoteNodeService and NodeCliCoordinator each collect the session's event stream from init.
        const val NODE_EVENT_COLLECTORS = 2

        // ChannelService reads one slot up to three times before reporting it as a timeout.
        const val FETCH_ATTEMPTS_PER_SLOT = 3
        const val CHANNEL_SECRET_BYTES = 16
    }
}
