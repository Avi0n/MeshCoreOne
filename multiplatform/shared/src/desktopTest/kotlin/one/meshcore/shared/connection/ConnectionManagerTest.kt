package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.backup.ConnectionMethod
import one.meshcore.shared.chat.ChannelMessageNotice
import one.meshcore.shared.chat.IngestNotifier
import one.meshcore.shared.chat.ResolvedContact
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.DeviceEntity
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.persistence.toEntity
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.NodePasswordStore
import one.meshcore.shared.transport.BluetoothPoweredOffException
import one.meshcore.shared.transport.BondLostException
import one.meshcore.shared.transport.CharacteristicNotFoundException
import one.meshcore.shared.transport.EncryptionTimeoutException
import one.meshcore.shared.transport.LinkDropReason
import one.meshcore.shared.transport.MeshLink
import one.meshcore.shared.transport.MeshLinkConnectException
import one.meshcore.shared.transport.NotificationsNotEnabledException
import one.meshcore.shared.transport.shouldHoldAutoReconnectGatt
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * State-machine transition tests for [ConnectionManager] over a fake link and fake session (no
 * Bluetooth, no Swift bridge), mirroring the intent of the Swift `ConnectionManager`/`BLEStateMachine`
 * tests: the connect ceremony, explicit-disconnect vs connection-lost, bond-loss surfacing, and the
 * reconnect backoff schedule. Persistence is exercised against a real desktop Room database.
 *
 * A real background scope is used (rather than a virtual-time test dispatcher) because Room dispatches
 * its queries to `Dispatchers.IO`, which virtual time cannot control; assertions poll the observable
 * state instead. The exact backoff *values* are proven separately in [ReconnectPolicyTest]; here a
 * millisecond-scaled policy keeps the retry behaviour fast and deterministic.
 */
class ConnectionManagerTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope

    private val fixedRadioId = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
    private val fastPolicy = ReconnectPolicy(
        ReconnectPolicy.Config(
            baseBackoff = 20.milliseconds,
            maxBackoff = 100.milliseconds,
            sessionRebuildRetryDelay = 20.milliseconds,
        ),
    )

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-connection", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancelAndJoinBlocking()
        db.close()
        dbFile.delete()
    }

    @Test
    fun emptyGattWithoutErrorIsHeldOnAutoReconnect() {
        assertTrue(shouldHoldAutoReconnectGatt(CharacteristicNotFoundException()))
        assertTrue(shouldHoldAutoReconnectGatt(NotificationsNotEnabledException()))
        assertFalse(shouldHoldAutoReconnectGatt(MeshLinkConnectException("boom")))
    }

    @Test
    fun handshakeTimeoutMatchesIosInitializeSession() {
        assertEquals(10.seconds, HANDSHAKE_COMMAND_TIMEOUT)
    }

    @Test
    fun aHangingSessionStartFailsAtTheHandshakeTimeout() = runBlocking {
        val session = FakeMeshSession().apply { startGate = CompletableDeferred() }
        val manager = manager(
            link = { FakeMeshLink() },
            session = { session },
            handshakeTimeout = 80.milliseconds,
            connectRetryPolicy = ConnectRetryPolicy(
                maxAttempts = 1,
                unverifiedAttempts = 1,
                baseBackoff = 1.milliseconds,
            ),
        )

        val error = assertFailsWith<MeshLinkConnectException> {
            withTimeout(1.seconds) { manager.connect(DEVICE) }
        }
        assertTrue(error.message!!.contains("session.start"))
    }

    @Test
    fun aHangingQueryDeviceFailsAtTheHandshakeTimeout() = runBlocking {
        val session = FakeMeshSession().apply { queryDeviceGate = CompletableDeferred() }
        val manager = manager(
            link = { FakeMeshLink() },
            session = { session },
            handshakeTimeout = 80.milliseconds,
            connectRetryPolicy = ConnectRetryPolicy(
                maxAttempts = 1,
                unverifiedAttempts = 1,
                baseBackoff = 1.milliseconds,
            ),
        )

        val error = assertFailsWith<MeshLinkConnectException> {
            withTimeout(1.seconds) { manager.connect(DEVICE) }
        }
        assertTrue(error.message!!.contains("queryDevice"))
    }

    @Test
    fun connectReachesReadyAndPersistsDeviceAndContacts() = runBlocking {
        val publicKey = ByteArray(32) { 0x01 }
        val session = FakeMeshSession(
            selfInfo = defaultSelfInfo(publicKey),
            contacts = listOf(sampleContact(ByteArray(32) { 0x09 }, "Alice")),
        )
        val manager = manager(link = { FakeMeshLink() }, session = { session })

        manager.connect(DEVICE)

        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
        assertTrue(session.started)

        val devices = db.deviceDao().listAll()
        assertEquals(1, devices.size)
        assertEquals(fixedRadioId, devices.single().radioID)
        assertTrue(devices.single().publicKey.contentEquals(publicKey))
        // BLE transport handle is stored on the Bluetooth method for multi-device reconnect.
        val ble = devices.single().connectionMethods
            .filterIsInstance<one.meshcore.shared.backup.ConnectionMethod.Bluetooth>()
            .single()
        assertEquals(DEVICE, ble.displayName)

        val contacts = db.contactDao().listByRadio(fixedRadioId)
        assertEquals(1, contacts.size)
        assertEquals("Alice", contacts.single().name)
    }

    @Test
    fun connectReachesReadyNotifiesConversationsAfterChannels() = runBlocking {
        var conversationReloads = 0
        val manager = manager(
            link = { FakeMeshLink() },
            session = { FakeMeshSession() },
            onConversationsChanged = { conversationReloads += 1 },
        )

        manager.connect(DEVICE)

        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
        assertTrue(
            conversationReloads >= 1,
            "iOS runFullSync notifies conversations after channels; got $conversationReloads",
        )
    }

    @Test
    fun connectedAndLaterStatesHaveServicesForCollectorRebind() = runBlocking {
        val manager = manager(link = { FakeMeshLink() }, session = { FakeMeshSession() })
        val observedWithoutServices = mutableListOf<String>()
        val watcher = launch {
            manager.state.collect { state ->
                val live = state is ConnectionState.Connected ||
                    state is ConnectionState.Syncing ||
                    state is ConnectionState.Ready
                if (live && manager.services == null) {
                    observedWithoutServices += state::class.simpleName.orEmpty()
                }
            }
        }
        manager.connect(DEVICE)
        watcher.cancel()
        assertTrue(
            observedWithoutServices.isEmpty(),
            "Discover/RX-log rebind sees a session on Connected: $observedWithoutServices",
        )
    }

    @Test
    fun connectRejectsWhileTheCircuitBreakerIsOpen() = runBlocking {
        val manager = manager(
            link = { FakeMeshLink().apply { failConnectWith = MeshLinkConnectException("out of range") } },
            session = { FakeMeshSession() },
        )

        assertFailsWith<MeshLinkConnectException> { manager.connect(DEVICE) }
        val blocked = assertFailsWith<MeshLinkConnectException> { manager.connect(DEVICE) }
        assertEquals(ConnectionCircuitBreaker.BLOCKED_MESSAGE, blocked.message)

        val forced = assertFailsWith<MeshLinkConnectException> {
            manager.connect(DEVICE, forceReconnect = true)
        }
        assertEquals("out of range", forced.message)
    }

    @Test
    fun connectRetriesTransientGattFailuresBeforeTrippingTheBreaker() = runBlocking {
        val link = FakeMeshLink().apply { remainingConnectFailures = 2 }
        val manager = manager(
            link = { link },
            session = { FakeMeshSession(selfInfo = defaultSelfInfo(ByteArray(32) { 0x01 })) },
        )

        manager.connect(DEVICE)

        assertEquals(3, link.connectCount)
        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
        manager.disconnect()
        manager.connect(DEVICE)
        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
    }

    @Test
    fun aDefinitiveBondOnFirstConnectGoesToBondLostWithoutRetry() = runBlocking {
        val link = FakeMeshLink().apply { failConnectWith = BondLostException("bond invalidated") }
        val manager = manager(link = { link }, session = { FakeMeshSession() })

        val error = assertFailsWith<BondLostException> { manager.connect(DEVICE) }
        assertEquals("bond invalidated", error.message)
        assertEquals(1, link.connectCount)
        assertEquals(ConnectionState.BondLost(DEVICE), manager.state.value)

        val again = assertFailsWith<BondLostException> { manager.connect(DEVICE) }
        assertEquals("bond invalidated", again.message)
        assertEquals(2, link.connectCount)
    }

    @Test
    fun connectTripsTheBreakerOnlyAfterTheInnerRetryBudgetIsSpent() = runBlocking {
        val link = FakeMeshLink().apply { remainingConnectFailures = ConnectRetryPolicy.DEFAULT_MAX_ATTEMPTS }
        val manager = manager(link = { link }, session = { FakeMeshSession() })

        assertFailsWith<MeshLinkConnectException> { manager.connect(DEVICE) }
        assertEquals(ConnectRetryPolicy.DEFAULT_MAX_ATTEMPTS, link.connectCount)

        val blocked = assertFailsWith<MeshLinkConnectException> { manager.connect(DEVICE) }
        assertEquals(ConnectionCircuitBreaker.BLOCKED_MESSAGE, blocked.message)
    }

    @Test
    fun anOpenBreakerHoldsTheAutoReconnectLoopUntilCooldown() = runBlocking {
        val link = FakeMeshLink().apply { remainingConnectFailures = ConnectRetryPolicy.DEFAULT_MAX_ATTEMPTS }
        val manager = manager(
            link = { link },
            session = { FakeMeshSession() },
            policy = fastPolicy,
        )

        assertFailsWith<MeshLinkConnectException> { manager.connect(DEVICE) }
        assertEquals(ConnectRetryPolicy.DEFAULT_MAX_ATTEMPTS, link.connectCount)
        assertTrue(manager.armBleReconnectIfWanted())
        delay(50)

        assertEquals(
            ConnectRetryPolicy.DEFAULT_MAX_ATTEMPTS,
            link.connectCount,
            "auto-reconnect must not fire a fifth GATT while the breaker is open",
        )
        assertTrue(manager.state.value is ConnectionState.Reconnecting)
    }

    @Test
    fun aUserInitiatedConnectUsesTheBoundedUnverifiedBudget() = runBlocking {
        val link = FakeMeshLink().apply {
            remainingConnectFailures = ConnectRetryPolicy.UNVERIFIED_MAX_ATTEMPTS
        }
        val manager = manager(link = { link }, session = { FakeMeshSession() })

        assertFailsWith<MeshLinkConnectException> { manager.connect(DEVICE, forceReconnect = true) }
        assertEquals(ConnectRetryPolicy.UNVERIFIED_MAX_ATTEMPTS, link.connectCount)
    }

    @Test
    fun aUserInitiatedConnectWithAPairingRegistryUsesTheFullBudget() = runBlocking {
        val link = FakeMeshLink().apply {
            remainingConnectFailures = ConnectRetryPolicy.DEFAULT_MAX_ATTEMPTS
        }
        val manager = manager(
            link = { link },
            session = { FakeMeshSession() },
            hasSystemPairingRegistry = { true },
        )

        assertFailsWith<MeshLinkConnectException> { manager.connect(DEVICE, forceReconnect = true) }
        assertEquals(ConnectRetryPolicy.DEFAULT_MAX_ATTEMPTS, link.connectCount)
    }

    @Test
    fun connectWithBluetoothOffThrowsWithoutTrippingTheBreaker() = runBlocking {
        var adapterOn = false
        val link = FakeMeshLink()
        val manager = manager(
            link = { link },
            session = { FakeMeshSession(selfInfo = defaultSelfInfo(ByteArray(32) { 0x01 })) },
            bluetoothAdapterEnabled = { adapterOn },
        )

        assertFailsWith<BluetoothPoweredOffException> { manager.connect(DEVICE) }
        assertEquals(0, link.connectCount)

        adapterOn = true
        manager.connect(DEVICE)
        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
    }

    @Test
    fun reconnectLoopYieldsDisconnectedWhenBluetoothIsOffSoPowerOnCanArm() = runBlocking {
        var adapterOn = true
        val links = mutableListOf<FakeMeshLink>()
        val manager = manager(
            link = { FakeMeshLink().also(links::add) },
            session = { FakeMeshSession(selfInfo = defaultSelfInfo(ByteArray(32) { 0x01 })) },
            policy = fastPolicy,
            bluetoothAdapterEnabled = { adapterOn },
        )
        manager.connect(DEVICE)
        adapterOn = false
        links.first().drop(LinkDropReason.CONNECTION_LOST)
        // The state can publish Disconnected just before the reconnect job completes.
        awaitCondition {
            manager.state.value == ConnectionState.Disconnected && !manager.isBleReconnectInProgress()
        }
        assertFalse(manager.isBleReconnectInProgress())

        adapterOn = true
        assertTrue(manager.armBleReconnectIfWanted())
        awaitCondition { manager.state.value == ConnectionState.Ready(DEVICE) }
    }

    @Test
    fun pairingInProgressCancelsReconnectAndBlocksRearm() = runBlocking {
        var connects = 0
        val links = mutableListOf<FakeMeshLink>()
        val manager = manager(
            link = {
                FakeMeshLink().also { link ->
                    links += link
                    connects++
                    if (connects > 1) link.failConnectWith = MeshLinkConnectException("out of range")
                }
            },
            session = { FakeMeshSession(selfInfo = defaultSelfInfo(ByteArray(32) { 0x01 })) },
            policy = fastPolicy,
        )
        manager.connect(DEVICE)
        links.first().drop(LinkDropReason.CONNECTION_LOST)
        awaitCondition { manager.isBleReconnectInProgress() }

        manager.setPairingInProgress(true)
        awaitCondition {
            !manager.isBleReconnectInProgress() && manager.state.value == ConnectionState.Disconnected
        }
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertFalse(manager.armBleReconnectIfWanted())

        manager.setPairingInProgress(false)
        awaitCondition { manager.isBleReconnectInProgress() }
    }

    @Test
    fun pairingCancelsAnInFlightConnectAndDisconnectsTheLiveLink() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val links = mutableListOf<FakeMeshLink>()
        val manager = manager(
            link = {
                FakeMeshLink().also {
                    it.connectGate = gate
                    links += it
                }
            },
            session = { FakeMeshSession(selfInfo = defaultSelfInfo(ByteArray(32) { 0x01 })) },
        )
        val ceremony = scope.launch { runCatching { manager.connect(DEVICE) } }
        awaitCondition { manager.state.value == ConnectionState.Connecting(DEVICE) && links.size == 1 }

        manager.setPairingInProgress(true)
        awaitCondition { links.single().disconnected }
        assertEquals(ConnectionState.Disconnected, manager.state.value)

        gate.complete(Unit)
        withTimeout(TIMEOUT_MS) { ceremony.join() }
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        manager.setPairingInProgress(false)
    }

    @Test
    fun pairingCancelsInFlightReconnectAndDisconnectsTheLiveLink() = runBlocking {
        val startGate = CompletableDeferred<Unit>()
        var sessions = 0
        val links = mutableListOf<FakeMeshLink>()
        val manager = manager(
            link = { FakeMeshLink().also(links::add) },
            session = {
                FakeMeshSession(selfInfo = defaultSelfInfo(ByteArray(32) { 0x01 })).also { session ->
                    sessions++
                    if (sessions > 1) session.startGate = startGate
                }
            },
            policy = fastPolicy,
        )
        manager.connect(DEVICE)
        links.first().drop(LinkDropReason.CONNECTION_LOST)
        awaitCondition { manager.isBleReconnectInProgress() }
        awaitCondition { links.size >= 2 && links[1].connectCount >= 1 && links[1].listener != null }

        manager.setPairingInProgress(true)
        awaitCondition { links[1].disconnected }
        awaitCondition { !manager.isBleReconnectInProgress() }
        assertEquals(ConnectionState.Disconnected, manager.state.value)

        startGate.cancel()
        manager.setPairingInProgress(false)
    }

    @Test
    fun endingPairingWaitsForItsTeardownBeforeRearmingReconnect() = runBlocking {
        val links = mutableListOf<FakeMeshLink>()
        val manager = manager(
            link = { FakeMeshLink().also(links::add) },
            session = { FakeMeshSession(selfInfo = defaultSelfInfo(ByteArray(32) { 0x01 })) },
            policy = fastPolicy,
        )
        manager.connect(DEVICE)
        val cancellationReached = CompletableDeferred<Unit>()
        val allowTeardownToFinish = CompletableDeferred<Unit>()
        manager.services!!.rxLogCatchUpJob = scope.launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cancellationReached.complete(Unit)
                    allowTeardownToFinish.await()
                }
            }
        }
        manager.mutableState.value = ConnectionState.Reconnecting(DEVICE, attempt = 1)

        manager.setPairingInProgress(true)
        withTimeout(TIMEOUT_MS) { cancellationReached.await() }
        manager.setPairingInProgress(false)
        delay(SETTLE_MS)

        assertEquals(1, links.size, "reconnect must wait until pairing teardown releases the old link")
        allowTeardownToFinish.complete(Unit)
        awaitCondition { links.size > 1 }
    }

    @Test
    fun aDropWhilePairingParksDisconnectedInsteadOfReconnecting() = runBlocking {
        val links = mutableListOf<FakeMeshLink>()
        val manager = manager(
            link = { FakeMeshLink().also { links += it } },
            session = { FakeMeshSession(selfInfo = defaultSelfInfo(ByteArray(32) { 0x01 })) },
            policy = fastPolicy,
        )
        manager.connect(DEVICE)
        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)

        manager.setPairingInProgress(true)
        links.first().drop(LinkDropReason.CONNECTION_LOST)

        awaitCondition { manager.state.value == ConnectionState.Disconnected }
        delay(SETTLE_MS)
        assertFalse(manager.isBleReconnectInProgress())
        assertEquals(1, links.size, "the picker must not occupy GATT with a reconnect")

        manager.setPairingInProgress(false)
        awaitCondition { manager.isBleReconnectInProgress() || manager.state.value is ConnectionState.Ready }
    }

    @Test
    fun autoReconnectStandsTheUiDownAfterTheConnectingWindow() = runBlocking {
        var connects = 0
        val links = mutableListOf<FakeMeshLink>()
        val manager = manager(
            link = {
                FakeMeshLink().also { link ->
                    links += link
                    connects++
                    if (connects > 1) link.failConnectWith = MeshLinkConnectException("out of range")
                }
            },
            session = { FakeMeshSession(selfInfo = defaultSelfInfo(ByteArray(32) { 0x01 })) },
            policy = ReconnectPolicy(
                ReconnectPolicy.Config(
                    baseBackoff = 20.milliseconds,
                    maxBackoff = 100.milliseconds,
                    maxConnectingUiWindow = 80.milliseconds,
                ),
            ),
        )
        manager.connect(DEVICE)
        links.first().drop(LinkDropReason.CONNECTION_LOST)
        awaitCondition { manager.isBleReconnectInProgress() }
        awaitCondition { manager.state.value == ConnectionState.Disconnected }
        assertTrue(manager.isBleReconnectInProgress())
        assertFalse(manager.state.value.isConnected)
    }

    @Test
    fun aUserTapRestartsTheConnectingUiWindowOnAnInFlightReconnect() = runBlocking {
        var connects = 0
        val links = mutableListOf<FakeMeshLink>()
        val manager = manager(
            link = {
                FakeMeshLink().also { link ->
                    links += link
                    connects++
                    if (connects > 1) link.failConnectWith = MeshLinkConnectException("out of range")
                }
            },
            session = { FakeMeshSession(selfInfo = defaultSelfInfo(ByteArray(32) { 0x01 })) },
            policy = ReconnectPolicy(
                ReconnectPolicy.Config(
                    baseBackoff = 20.milliseconds,
                    maxBackoff = 100.milliseconds,
                    maxConnectingUiWindow = 80.milliseconds,
                ),
            ),
        )
        manager.connect(DEVICE)
        links.first().drop(LinkDropReason.CONNECTION_LOST)
        awaitCondition { manager.state.value is ConnectionState.Reconnecting }
        delay(50)
        manager.connect(DEVICE, forceReconnect = true)
        delay(50)
        assertTrue(
            manager.state.value is ConnectionState.Reconnecting,
            "a tap must extend the Connecting pill instead of letting the original window drop it",
        )
        assertTrue(manager.isBleReconnectInProgress())
    }

    @Test
    fun connectDeletesThePriorPublicKeyDeviceRowWhenTheHandleChanges() = runBlocking {
        val publicKey = ByteArray(32) { 0x11 }
        val oldId = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")
        val oldRadioId = Uuid.normalized("CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC")
        db.deviceDao().upsert(
            DeviceEntity(
                id = oldId,
                radioID = oldRadioId,
                publicKey = publicKey,
                nodeName = "old-handle",
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
                lastContactSync = KEPT_CONTACT_SYNC,
                isActive = true,
                knownRegions = listOf(KEPT_REGION),
                defaultFloodScopeName = KEPT_FLOOD_SCOPE,
                ocvPreset = KEPT_OCV_PRESET,
                connectionMethods = listOf(
                    ConnectionMethod.WiFi(host = KEPT_WIFI_HOST, port = KEPT_WIFI_PORT, displayName = null),
                ),
            ),
        )
        val manager = manager(
            link = { FakeMeshLink() },
            session = { FakeMeshSession(selfInfo = defaultSelfInfo(publicKey)) },
        )

        manager.connect(DEVICE)

        val devices = db.deviceDao().listAll()
        assertEquals(1, devices.size)
        assertNull(db.deviceDao().getById(oldId))
        assertTrue(devices.single().publicKey.contentEquals(publicKey))
        assertEquals(oldRadioId, devices.single().radioID)
        assertEquals(KEPT_CONTACT_SYNC, devices.single().lastContactSync)
        assertEquals(listOf(KEPT_REGION), devices.single().knownRegions)
        assertEquals(KEPT_FLOOD_SCOPE, devices.single().defaultFloodScopeName)
        assertEquals(KEPT_OCV_PRESET, devices.single().ocvPreset)
        assertTrue(
            devices.single().connectionMethods.any {
                it is ConnectionMethod.WiFi && it.host == KEPT_WIFI_HOST && it.port == KEPT_WIFI_PORT
            },
        )
        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
    }

    @Test
    fun connectKeepsABackupShadowWhenTheHandleRowAlreadyExists() = runBlocking {
        val oldKey = ByteArray(32) { 0x21 }
        val newKey = ByteArray(32) { 0x22 }
        val handleId = StableIds.uuid(DEVICE.encodeToByteArray())
        val shadowId = Uuid.normalized("DDDDDDDD-DDDD-DDDD-DDDD-DDDDDDDDDDDD")
        val handleRadioId = Uuid.normalized("EEEEEEEE-EEEE-EEEE-EEEE-EEEEEEEEEEEE")
        val importedRadioId = Uuid.normalized("FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF")
        db.deviceDao().upsert(
            DeviceEntity(
                id = handleId,
                radioID = handleRadioId,
                publicKey = oldKey,
                nodeName = "handle",
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
                knownRegions = emptyList(),
                defaultFloodScopeName = null,
                ocvPreset = null,
                connectionMethods = emptyList(),
            ),
        )
        db.deviceDao().upsert(
            DeviceEntity(
                id = shadowId,
                radioID = importedRadioId,
                publicKey = newKey,
                nodeName = "imported",
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
                knownRegions = emptyList(),
                defaultFloodScopeName = null,
                ocvPreset = null,
                connectionMethods = emptyList(),
            ),
        )
        val manager = manager(
            link = { FakeMeshLink() },
            session = { FakeMeshSession(selfInfo = defaultSelfInfo(newKey)) },
        )

        manager.connect(DEVICE)

        val handle = db.deviceDao().getById(handleId)
        val shadow = db.deviceDao().getById(shadowId)
        assertTrue(handle != null)
        assertTrue(shadow != null)
        assertTrue(handle!!.publicKey.contentEquals(newKey))
        assertEquals(handleRadioId, handle.radioID)
        assertTrue(shadow!!.publicKey.contentEquals(newKey))
        assertEquals(importedRadioId, shadow.radioID)
    }

    @Test
    fun connectRunsChannelSyncBacklogPollAndAutoFetch() = runBlocking {
        val publicKey = ByteArray(32) { 0x03 }
        val session = FakeMeshSession(selfInfo = defaultSelfInfo(publicKey))
        val notifier = RecordingSuppressNotifier()
        val manager = manager(link = { FakeMeshLink() }, session = { session }, notifier = notifier)

        manager.connect(DEVICE)

        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
        // Post-connect full sync tail (iOS SyncCoordinator parity).
        assertTrue(session.autoFetchStartCount >= 1, "startAutoMessageFetching must run on connect")
        assertTrue(session.getChannelsCallCount >= 1, "channel sync must issue getChannels")
        assertTrue(session.getMessageCount >= 1, "backlog poll must issue getMessage")
        // Sync-window notification suppress (iOS isSuppressingNotifications).
        assertEquals(listOf(true, false), notifier.suppressHistory)

        manager.disconnect()
        assertTrue(session.autoFetchStopCount >= 1, "stopAutoMessageFetching must run on teardown")
    }

    @Test
    fun aHealthyReadyRadioDoesNotRestartAutoFetchOnListenerReconcile() = runBlocking {
        val session = FakeMeshSession()
        val manager = manager(link = { FakeMeshLink() }, session = { session })
        manager.connect(DEVICE)
        val starts = session.autoFetchStartCount
        manager.reconcileConnectedListeners()
        assertEquals(starts, session.autoFetchStartCount, "healthy listeners must not re-arm auto-fetch")
    }

    @Test
    fun wifiForegroundHealthProbeDropsADeadCompanion() = runBlocking {
        val session = FakeMeshSession()
        val wifiLink = FakeMeshLink()
        val manager = manager(
            link = { FakeMeshLink() },
            session = { session },
            wifiLinkFactory = { _, _, _ -> wifiLink },
        )
        manager.connectViaWiFi("10.0.0.1", 4000)
        assertTrue(manager.state.value is ConnectionState.Ready)
        session.getTimeFailures += IllegalStateException("dead")
        manager.checkWiFiConnectionHealth()
        assertTrue(wifiLink.disconnected)
    }

    @Test
    fun aFailedWifiConnectClearsTransportSoBleHealthCheckCanAdopt() = runBlocking {
        val store = LastConnectionStore(InMemoryPreferencesStore())
        store.persist(fixedRadioId, fixedRadioId, "Radio", handle = DEVICE)
        val manager = manager(
            link = { FakeMeshLink() },
            session = { FakeMeshSession() },
            wifiLinkFactory = { _, _, _ ->
                FakeMeshLink().apply { failConnectWith = MeshLinkConnectException("refused") }
            },
            lastConnectionStore = store,
            systemConnectedHandles = { setOf(DEVICE) },
        )
        assertFailsWith<MeshLinkConnectException> { manager.connectViaWiFi("10.0.0.1", 4000) }
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertNull(manager.currentTransportType)
        manager.checkBLEConnectionHealth()
        awaitCondition { manager.state.value is ConnectionState.Ready }
        manager.disconnect()
    }

    @Test
    fun bleForegroundHealthDropsASilentlyDeadCompanion() = runBlocking {
        val live = FakeMeshLink()
        var connects = 0
        val manager = manager(
            link = {
                connects++
                if (connects == 1) {
                    live
                } else {
                    FakeMeshLink().apply {
                        failConnectWith = MeshLinkConnectException("gatt dead")
                    }
                }
            },
            session = { FakeMeshSession() },
            policy = fastPolicy,
        )
        manager.connect(DEVICE)
        assertTrue(manager.state.value is ConnectionState.Ready)
        live.silentlyDead = true
        manager.checkBLEConnectionHealth()
        awaitCondition { manager.state.value !is ConnectionState.Ready }
    }

    @Test
    fun bleForegroundHealthLeavesALiveCompanionReady() = runBlocking {
        val manager = manager(
            link = { FakeMeshLink() },
            session = { FakeMeshSession() },
        )
        manager.connect(DEVICE)
        manager.checkBLEConnectionHealth()
        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
    }

    @Test
    fun bleForegroundHealthSkipsAWifiCompanion() = runBlocking {
        val manager = manager(
            link = { FakeMeshLink() },
            session = { FakeMeshSession() },
            wifiLinkFactory = { _, _, _ -> FakeMeshLink() },
        )
        manager.connectViaWiFi("10.0.0.1", 4000)
        manager.checkBLEConnectionHealth()
        assertTrue(manager.state.value is ConnectionState.Ready)
    }

    @Test
    fun bleForegroundHealthDoesNotDropAnAlreadyDisconnectedRadio() = runBlocking {
        val manager = manager(
            link = { FakeMeshLink() },
            session = { FakeMeshSession() },
        )
        manager.connect(DEVICE)
        manager.disconnect(DisconnectReason.USER_INITIATED)
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        manager.checkBLEConnectionHealth()
        assertEquals(ConnectionState.Disconnected, manager.state.value)
    }

    @Test
    fun bleForegroundHealthAdoptsASystemConnectedIdleRadio() = runBlocking {
        val links = mutableListOf<FakeMeshLink>()
        val store = LastConnectionStore(one.meshcore.shared.prefs.InMemoryPreferencesStore())
        val manager = manager(
            link = { FakeMeshLink().also { links += it } },
            session = { FakeMeshSession() },
            lastConnectionStore = store,
            systemConnectedHandles = { setOf(DEVICE) },
        )
        manager.connect(DEVICE)
        manager.disconnect(DisconnectReason.RESYNC_FAILED)
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertEquals(1, links.size)
        manager.checkBLEConnectionHealth()
        awaitCondition { manager.state.value is ConnectionState.Ready }
        assertEquals(2, links.size)
        val diagnostic = manager.lastDisconnectDiagnostic()
        assertTrue(diagnostic?.contains("checkBLEConnectionHealth.adoptSystemConnectedPeripheral") == true)
    }

    @Test
    fun connectJoinsAnInFlightReconnectInsteadOfTearingItDown() = runBlocking {
        val first = FakeMeshLink()
        val reconnectGate = CompletableDeferred<Unit>()
        val reconnectLinks = mutableListOf<FakeMeshLink>()
        val manager = manager(
            link = { first },
            reconnectLink = {
                FakeMeshLink().also {
                    it.connectGate = reconnectGate
                    reconnectLinks += it
                }
            },
            session = { FakeMeshSession() },
            policy = fastPolicy,
        )
        manager.connect(DEVICE)
        manager.disconnect(DisconnectReason.RESYNC_FAILED)
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertEquals(1, first.connectCount)
        manager.armBleReconnectIfWanted()
        awaitCondition { reconnectLinks.isNotEmpty() && manager.isBleReconnectInProgress() }
        assertEquals(1, reconnectLinks.size)
        manager.connect(DEVICE)
        delay(SETTLE_MS)
        assertEquals(1, reconnectLinks.size, "a second connect must not start a competing GATT")
        assertEquals(1, first.connectCount)
        assertTrue(manager.isBleReconnectInProgress())
        reconnectGate.complete(Unit)
        awaitCondition { manager.state.value is ConnectionState.Ready }
        assertEquals(1, reconnectLinks.size)
        assertEquals(1, reconnectLinks.first().connectCount)
    }

    @Test
    fun aUserForceReconnectJoinsAnInFlightSameDeviceReconnect() = runBlocking {
        val first = FakeMeshLink()
        val reconnectGate = CompletableDeferred<Unit>()
        val reconnectLinks = mutableListOf<FakeMeshLink>()
        val manager = manager(
            link = { first },
            reconnectLink = {
                FakeMeshLink().also {
                    it.connectGate = reconnectGate
                    reconnectLinks += it
                }
            },
            session = { FakeMeshSession() },
            policy = fastPolicy,
        )
        manager.connect(DEVICE)
        manager.disconnect(DisconnectReason.RESYNC_FAILED)
        manager.armBleReconnectIfWanted()
        awaitCondition { reconnectLinks.isNotEmpty() && manager.isBleReconnectInProgress() }
        assertEquals(1, reconnectLinks.size)
        manager.connect(DEVICE, forceReconnect = true)
        delay(SETTLE_MS)
        assertEquals(1, reconnectLinks.size, "a picker tap must not tear down the same-device watchdog")
        assertTrue(manager.isBleReconnectInProgress())
        reconnectGate.complete(Unit)
        awaitCondition { manager.state.value is ConnectionState.Ready }
        assertEquals(1, reconnectLinks.size)
        assertEquals(1, reconnectLinks.first().connectCount)
    }

    @Test
    fun activateSkipsAnotherAppThenArmsTheWatchdog() = runBlocking {
        val store = LastConnectionStore(one.meshcore.shared.prefs.InMemoryPreferencesStore())
        store.persist(fixedRadioId, fixedRadioId, "Radio", handle = "other-handle")
        val reconnectGate = CompletableDeferred<Unit>()
        val reconnectLinks = mutableListOf<FakeMeshLink>()
        var heldHandles = setOf(DEVICE)
        val manager = manager(
            link = { FakeMeshLink() },
            reconnectLink = {
                FakeMeshLink().also {
                    it.connectGate = reconnectGate
                    reconnectLinks += it
                }
            },
            session = { FakeMeshSession() },
            policy = fastPolicy,
            lastConnectionStore = store,
            systemConnectedHandles = { heldHandles },
        )
        manager.activateLastBle(DEVICE)
        awaitCondition { manager.isBleReconnectInProgress() }
        delay(SETTLE_MS)
        assertTrue(reconnectLinks.isEmpty(), "the watchdog must not fight the other app for GATT")
        heldHandles = emptySet()
        awaitCondition { reconnectLinks.isNotEmpty() }
        reconnectGate.complete(Unit)
        awaitCondition { manager.state.value is ConnectionState.Ready }
    }

    @Test
    fun bleForegroundHealthReconnectsWhenIdleAndNotSystemConnected() = runBlocking {
        val links = mutableListOf<FakeMeshLink>()
        val manager = manager(
            link = { FakeMeshLink().also { links += it } },
            session = { FakeMeshSession() },
        )
        manager.connect(DEVICE)
        manager.disconnect(DisconnectReason.RESYNC_FAILED)
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertEquals(1, links.size)
        manager.checkBLEConnectionHealth()
        awaitCondition { manager.state.value is ConnectionState.Ready }
        assertEquals(2, links.size)
    }

    @Test
    fun bleForegroundHealthSkipsReconnectWhenAnotherAppHoldsTheRadio() = runBlocking {
        val links = mutableListOf<FakeMeshLink>()
        val store = LastConnectionStore(one.meshcore.shared.prefs.InMemoryPreferencesStore())
        val manager = manager(
            link = { FakeMeshLink().also { links += it } },
            session = { FakeMeshSession() },
            lastConnectionStore = store,
            systemConnectedHandles = { setOf(DEVICE) },
        )
        manager.connect(DEVICE)
        manager.disconnect(DisconnectReason.RESYNC_FAILED)
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertEquals(1, links.size)
        // Last-store handle disagrees with the live target so adopt refuses; the OS GATT
        // set still lists this radio, matching iOS `isDeviceConnectedToOtherApp` after a
        // failed adopt.
        store.persist(
            deviceId = store.deviceId()!!,
            radioId = store.radioId()!!,
            deviceName = store.deviceName() ?: DEVICE,
            handle = "other-handle",
        )
        manager.checkBLEConnectionHealth()
        delay(SETTLE_MS)
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertEquals(1, links.size)
        val diagnostic = manager.lastDisconnectDiagnostic()
        assertTrue(diagnostic?.contains("checkBLEConnectionHealth.otherAppConnected") == true)
        manager.armBleReconnectIfWanted()
        delay(SETTLE_MS)
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertEquals(1, links.size)
    }

    @Test
    fun bleForegroundHealthRebuildsALiveGattWithAMissingAppStack() = runBlocking {
        val link = FakeMeshLink()
        val sessions = mutableListOf<FakeMeshSession>()
        val manager = manager(
            link = { link },
            session = { FakeMeshSession().also(sessions::add) },
        )
        manager.connect(DEVICE)
        assertTrue(manager.state.value is ConnectionState.Ready)
        assertEquals(1, sessions.size)
        assertEquals(1, link.connectCount)
        manager.dropSessionKeepingLink()
        assertNull(manager.services)
        manager.checkBLEConnectionHealth()
        awaitCondition { manager.state.value is ConnectionState.Ready && manager.services != null }
        assertEquals(2, sessions.size)
        assertEquals(1, link.connectCount)
        assertTrue(link.isConnected)
    }

    @Test
    fun aCancelledSessionStopStillClosesAndDropsTheLink() = runBlocking {
        val radio = connectRadio()
        val cancelled = CancellationException("stop")
        radio.session.stopFailure = cancelled
        val thrown = assertFailsWith<CancellationException> { radio.manager.disconnect() }
        assertSame(cancelled, thrown)
        assertTrue(radio.session.stopped)
        assertTrue(radio.session.closed)
        assertTrue(radio.link.disconnected)
    }

    @Test
    fun aCancelledAutoFetchStopStillClosesAndDropsTheLink() = runBlocking {
        val radio = connectRadio()
        val cancelled = CancellationException("auto-fetch")
        radio.session.stopAutoFetchFailure = cancelled
        val thrown = assertFailsWith<CancellationException> { radio.manager.disconnect() }
        assertSame(cancelled, thrown)
        assertTrue(radio.session.stopped)
        assertTrue(radio.session.closed)
        assertTrue(radio.link.disconnected)
        assertTrue(radio.session.autoFetchStopCount >= 1)
    }

    @Test
    fun disconnectKeepsTheAutoFetchCancellationWhenSessionStopAlsoCancels() = runBlocking {
        val radio = connectRadio()
        val autoFetch = CancellationException("auto-fetch")
        val stop = CancellationException("stop")
        radio.session.stopAutoFetchFailure = autoFetch
        radio.session.stopFailure = stop
        val thrown = assertFailsWith<CancellationException> { radio.manager.disconnect() }
        assertSame(autoFetch, thrown)
        assertTrue(radio.session.stopped)
        assertTrue(radio.session.closed)
        assertTrue(radio.link.disconnected)
    }

    @Test
    fun aFailedSessionStopStillDisconnects() = runBlocking {
        val radio = connectRadio()
        radio.session.stopFailure = IllegalStateException("refused")
        radio.manager.disconnect()
        assertTrue(radio.session.stopped)
        assertTrue(radio.session.closed)
        assertTrue(radio.link.disconnected)
        assertEquals(ConnectionState.Disconnected, radio.manager.state.value)
    }

    @Test
    fun aSessionStopErrorStillDisconnects() = runBlocking {
        val radio = connectRadio()
        radio.session.stopFailure = LiveSessionStopProbeError()
        radio.manager.disconnect()
        assertTrue(radio.session.stopped)
        assertTrue(radio.session.closed)
        assertTrue(radio.link.disconnected)
        assertEquals(ConnectionState.Disconnected, radio.manager.state.value)
    }

    @Test
    fun anAutoFetchStopErrorStillDisconnects() = runBlocking {
        val radio = connectRadio()
        radio.session.stopAutoFetchFailure = LiveSessionStopProbeError("auto-fetch")
        radio.manager.disconnect()
        assertTrue(radio.session.stopped)
        assertTrue(radio.session.closed)
        assertTrue(radio.link.disconnected)
        assertEquals(ConnectionState.Disconnected, radio.manager.state.value)
    }

    @Test
    fun aCancelledSessionStopKeepsTheLinkWhenRebuilding() = runBlocking {
        val radio = connectRadio()
        val cancelled = CancellationException("stop")
        radio.session.stopFailure = cancelled
        val thrown = assertFailsWith<CancellationException> { radio.manager.dropSessionKeepingLink() }
        assertSame(cancelled, thrown)
        assertNull(radio.manager.services)
        assertTrue(radio.session.stopped)
        assertTrue(radio.session.closed)
        assertFalse(radio.link.disconnected)
        assertTrue(radio.link.isConnected)
    }

    @Test
    fun aCancelledAutoFetchStopKeepsTheLinkWhenRebuilding() = runBlocking {
        val radio = connectRadio()
        val cancelled = CancellationException("auto-fetch")
        radio.session.stopAutoFetchFailure = cancelled
        val thrown = assertFailsWith<CancellationException> { radio.manager.dropSessionKeepingLink() }
        assertSame(cancelled, thrown)
        assertNull(radio.manager.services)
        assertTrue(radio.session.stopped)
        assertTrue(radio.session.closed)
        assertFalse(radio.link.disconnected)
    }

    @Test
    fun tearDownWithoutSessionStopStillClosesAfterAutoFetchCancellation() = runBlocking {
        val radio = connectRadio()
        val cancelled = CancellationException("auto-fetch")
        radio.session.stopAutoFetchFailure = cancelled
        val thrown = assertFailsWith<CancellationException> {
            radio.manager.tearDownCurrent(stopSession = false)
        }
        assertSame(cancelled, thrown)
        assertFalse(radio.session.stopped)
        assertTrue(radio.session.closed)
        assertTrue(radio.link.disconnected)
        assertTrue(radio.session.autoFetchStopCount >= 1)
    }

    @Test
    fun aDeadLinkDisconnectErrorStillRethrowsHandshakeCancellation() = runBlocking {
        val handshake = CancellationException("handshake")
        val session = FakeMeshSession().apply { failStartWith = handshake }
        val link = FakeMeshLink().apply {
            silentlyDead = true
            disconnectFailure = LiveLinkDisconnectProbeError()
        }
        val manager = manager(link = { link }, session = { session })
        manager.link = link
        val thrown = assertFailsWith<CancellationException> {
            manager.handshakeOverExistingLink(DEVICE, link, manager.connectGeneration)
        }
        assertEquals(handshake.message, thrown.message)
        assertTrue(link.disconnected)
    }

    @Test
    fun aCancelledDeadLinkDisconnectKeepsTheHandshakeCancellation() = runBlocking {
        val handshake = CancellationException("handshake")
        val session = FakeMeshSession().apply { failStartWith = handshake }
        val link = FakeMeshLink().apply {
            silentlyDead = true
            disconnectFailure = CancellationException("link")
        }
        val manager = manager(link = { link }, session = { session })
        manager.link = link
        val thrown = assertFailsWith<CancellationException> {
            manager.handshakeOverExistingLink(DEVICE, link, manager.connectGeneration)
        }
        assertEquals(handshake.message, thrown.message)
        assertTrue(link.disconnected)
    }

    @Test
    fun aLiveLinkSkipsDisconnectWhenTheHandshakeIsCancelled() = runBlocking {
        val handshake = CancellationException("handshake")
        val session = FakeMeshSession().apply { failStartWith = handshake }
        val link = FakeMeshLink().apply { disconnectFailure = LiveLinkDisconnectProbeError() }
        val manager = manager(link = { link }, session = { session })
        manager.link = link
        val thrown = assertFailsWith<CancellationException> {
            manager.handshakeOverExistingLink(DEVICE, link, manager.connectGeneration)
        }
        assertEquals(handshake.message, thrown.message)
        assertFalse(link.disconnected)
    }

    @Test
    fun fetchSavedConnectableDevicesListsBleHandleAfterConnect() = runBlocking {
        val store = LastConnectionStore(one.meshcore.shared.prefs.InMemoryPreferencesStore())
        val manager = manager(
            link = { FakeMeshLink() },
            session = { FakeMeshSession(selfInfo = defaultSelfInfo(ByteArray(32) { 0x02 })) },
            lastConnectionStore = store,
        )
        manager.connect(DEVICE)
        val saved = manager.fetchSavedConnectableDevices()
        assertEquals(1, saved.size)
        assertEquals(DEVICE, saved.single().bleHandle)
        assertEquals(false, saved.single().connectsViaWiFi)
    }

    @Test
    fun deleteSavedDeviceRemintsDeviceIdAndDropsThePickerRow() = runBlocking {
        val store = LastConnectionStore(one.meshcore.shared.prefs.InMemoryPreferencesStore())
        val manager = manager(
            link = { FakeMeshLink() },
            session = { FakeMeshSession(selfInfo = defaultSelfInfo(ByteArray(32) { 0x02 })) },
            lastConnectionStore = store,
        )
        manager.connect(DEVICE)
        val oldId = db.deviceDao().listAll().single().id
        val radioId = db.deviceDao().listAll().single().radioID
        val publicKey = db.deviceDao().listAll().single().publicKey

        manager.deleteSavedDevice(oldId)

        val remaining = db.deviceDao().listAll()
        assertEquals(1, remaining.size)
        assertTrue(remaining.single().id != oldId)
        assertEquals(radioId, remaining.single().radioID)
        assertTrue(remaining.single().publicKey.contentEquals(publicKey))
        assertEquals(false, remaining.single().isActive)
        assertTrue(remaining.single().connectionMethods.isEmpty())
        assertTrue(manager.fetchSavedConnectableDevices().isEmpty())
        assertNull(store.deviceId())
    }

    @Test
    fun deleteSavedDeviceStopsReconnectOfTheForgottenLastRadio() = runBlocking {
        val prefs = one.meshcore.shared.prefs.InMemoryPreferencesStore()
        val store = LastConnectionStore(prefs)
        val intent = ConnectionIntentStore(prefs)
        val links = mutableListOf<FakeMeshLink>()
        val manager = manager(
            link = { FakeMeshLink().also { links += it } },
            session = { FakeMeshSession() },
            lastConnectionStore = store,
            intentStore = intent,
            policy = fastPolicy,
        )
        manager.connect(DEVICE)
        val oldId = db.deviceDao().listAll().single().id
        links.first().drop(LinkDropReason.CONNECTION_LOST)
        // A warm JVM can leave Reconnecting before the poll sees it and already be Connecting/Syncing
        // (or Ready on a second link). Forgetting the last radio must stop any of those live episodes.
        awaitCondition {
            val state = manager.state.value
            state is ConnectionState.Reconnecting ||
                state is ConnectionState.Connecting ||
                state is ConnectionState.Connected ||
                state is ConnectionState.Syncing ||
                (state is ConnectionState.Ready && links.size > 1)
        }

        manager.deleteSavedDevice(oldId)

        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertNull(store.deviceId())
        assertTrue(intent.restore().isUserDisconnected)
        delay(SETTLE_MS)
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertFalse(manager.armBleReconnectIfWanted())
    }

    @Test
    fun deleteSavedDeviceLatchesPairingBeforeRegistryRemoveSoALinkDropDoesNotReconnect() = runBlocking {
        val prefs = InMemoryPreferencesStore()
        val store = LastConnectionStore(prefs)
        val intent = ConnectionIntentStore(prefs)
        val links = mutableListOf<FakeMeshLink>()
        val manager = manager(
            link = { FakeMeshLink().also { links += it } },
            session = { FakeMeshSession() },
            lastConnectionStore = store,
            intentStore = intent,
            policy = fastPolicy,
        )
        manager.connect(DEVICE)
        val oldId = db.deviceDao().listAll().single().id
        var latchedDuringRemove = false
        var reconnectingDuringRemove = false

        manager.deleteSavedDevice(oldId) {
            latchedDuringRemove = manager.isPairingInProgress()
            links.first().drop(LinkDropReason.CONNECTION_LOST)
            delay(SETTLE_MS)
            reconnectingDuringRemove = manager.isBleReconnectInProgress() ||
                manager.state.value is ConnectionState.Reconnecting ||
                manager.state.value is ConnectionState.Connecting
        }

        assertTrue(latchedDuringRemove)
        assertFalse(reconnectingDuringRemove)
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertFalse(manager.isPairingInProgress())
        assertTrue(intent.restore().isUserDisconnected)
        delay(SETTLE_MS)
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertFalse(manager.armBleReconnectIfWanted())
    }

    @Test
    fun aCancelledRegistryRemoveLeavesThePickerRowAndLiveRadio() = runBlocking {
        val store = LastConnectionStore(InMemoryPreferencesStore())
        val manager = manager(
            link = { FakeMeshLink() },
            session = { FakeMeshSession() },
            lastConnectionStore = store,
        )
        manager.connect(DEVICE)
        val oldId = db.deviceDao().listAll().single().id

        assertFailsWith<DeviceForgetCancelledException> {
            manager.deleteSavedDevice(oldId) { throw DeviceForgetCancelledException() }
        }

        assertEquals(oldId, db.deviceDao().listAll().single().id)
        assertEquals(1, manager.fetchSavedConnectableDevices().size)
        assertTrue(manager.state.value is ConnectionState.Ready)
        assertFalse(manager.isPairingInProgress())
        assertEquals(oldId, store.deviceId())
    }

    @Test
    fun deleteSavedDeviceRemovesThePairingRegistryEntryBeforeDemote() = runBlocking {
        val store = LastConnectionStore(InMemoryPreferencesStore())
        val manager = manager(
            link = { FakeMeshLink() },
            session = { FakeMeshSession() },
            lastConnectionStore = store,
        )
        manager.connect(DEVICE)
        val oldId = db.deviceDao().listAll().single().id
        var listedDuringRemove = false
        var removedHandle: String? = null

        manager.deleteSavedDevice(oldId) { handle ->
            removedHandle = handle
            listedDuringRemove = manager.fetchSavedConnectableDevices().any { it.id == oldId }
        }

        assertEquals(DEVICE, removedHandle)
        assertTrue(listedDuringRemove)
        assertTrue(manager.fetchSavedConnectableDevices().isEmpty())
    }

    @Test
    fun explicitDisconnectClearsIntentAndDoesNotReconnect() = runBlocking {
        val link = FakeMeshLink()
        val manager = manager(link = { link }, session = { FakeMeshSession() })

        manager.connect(DEVICE)
        manager.disconnect()

        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertNull(manager.services)

        // A late drop from the severed link must not resurrect a connection.
        link.drop(LinkDropReason.CONNECTION_LOST)
        delay(SETTLE_MS)
        assertEquals(ConnectionState.Disconnected, manager.state.value)
    }

    @Test
    fun connectionLossArmsReconnectAndReachesReadyOnAFreshLink() = runBlocking {
        val links = mutableListOf<FakeMeshLink>()
        val manager = manager(link = { FakeMeshLink().also { links += it } }, session = { FakeMeshSession() })

        manager.connect(DEVICE)
        links.first().drop(LinkDropReason.CONNECTION_LOST)

        // The reconnect must build a fresh link (never reusing the dropped handle) and reach Ready.
        awaitCondition { links.size == 2 && manager.state.value == ConnectionState.Ready(DEVICE) }
    }

    @Test
    fun autoReconnectBuildsTheLinkFromTheReconnectFactory() = runBlocking {
        val userLinks = mutableListOf<FakeMeshLink>()
        val reconnectLinks = mutableListOf<FakeMeshLink>()
        val manager = manager(
            link = { FakeMeshLink().also { userLinks += it } },
            reconnectLink = { FakeMeshLink().also { reconnectLinks += it } },
            session = { FakeMeshSession() },
        )

        manager.connect(DEVICE)
        assertEquals(1, userLinks.size)
        assertEquals(0, reconnectLinks.size)
        userLinks.first().drop(LinkDropReason.CONNECTION_LOST)

        awaitCondition { reconnectLinks.size == 1 && manager.state.value == ConnectionState.Ready(DEVICE) }
        assertEquals(1, userLinks.size)
    }

    @Test
    fun bondLossParksUiAndKeepsTheWatchdog() = runBlocking {
        val userLinks = mutableListOf<FakeMeshLink>()
        val reconnectLinks = mutableListOf<FakeMeshLink>()
        var allowReconnectSuccess = false
        val manager = manager(
            link = { FakeMeshLink().also(userLinks::add) },
            reconnectLink = {
                FakeMeshLink().also { link ->
                    if (!allowReconnectSuccess) {
                        link.failConnectWith = BondLostException("still invalid")
                    }
                    reconnectLinks += link
                }
            },
            session = { FakeMeshSession() },
            policy = fastPolicy,
        )

        manager.connect(DEVICE)
        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
        userLinks.single().drop(LinkDropReason.BOND_LOST)

        awaitCondition {
            manager.state.value == ConnectionState.BondLost(DEVICE) && manager.isBleReconnectInProgress()
        }
        assertTrue(manager.isBleReconnectInProgress(), "iOS still starts the watchdog after auth failure")
        awaitCondition { reconnectLinks.isNotEmpty() }
        delay(SETTLE_MS)
        assertEquals(
            ConnectionState.BondLost(DEVICE),
            manager.state.value,
            "guided recovery stays up while the watchdog ticks",
        )

        allowReconnectSuccess = true
        awaitCondition { reconnectLinks.size >= 2 && manager.state.value == ConnectionState.Ready(DEVICE) }
    }

    @Test
    fun aForceReconnectDuringBondLostStartsAFreshUserConnection() = runBlocking {
        val userLinks = mutableListOf<FakeMeshLink>()
        val manager = manager(
            link = { FakeMeshLink().also(userLinks::add) },
            reconnectLink = {
                FakeMeshLink().apply { failConnectWith = BondLostException("still invalid") }
            },
            session = { FakeMeshSession() },
            policy = ReconnectPolicy(
                ReconnectPolicy.Config(
                    baseBackoff = 2.seconds,
                    maxBackoff = 2.seconds,
                ),
            ),
        )

        manager.connect(DEVICE)
        userLinks.single().drop(LinkDropReason.BOND_LOST)
        awaitCondition {
            manager.state.value == ConnectionState.BondLost(DEVICE) && manager.isBleReconnectInProgress()
        }
        assertTrue(manager.isBleReconnectInProgress())

        manager.connect(DEVICE, forceReconnect = true)
        awaitCondition { manager.state.value is ConnectionState.Ready }
        assertEquals(2, userLinks.size, "retry must start a fresh connect instead of waiting out the BondLost backoff")
    }

    @Test
    fun emptyGattOnAutoReconnectHoldsTheLinkThroughTheBackoff() = runBlocking {
        val userLinks = mutableListOf<FakeMeshLink>()
        val reconnectLinks = mutableListOf<FakeMeshLink>()
        val manager = manager(
            link = { FakeMeshLink().also(userLinks::add) },
            reconnectLink = {
                FakeMeshLink().also { link ->
                    if (reconnectLinks.isEmpty()) {
                        link.failConnectWith = CharacteristicNotFoundException()
                    }
                    reconnectLinks += link
                }
            },
            session = { FakeMeshSession() },
            policy = fastPolicy,
        )
        manager.connect(DEVICE)
        userLinks.single().drop(LinkDropReason.CONNECTION_LOST)
        awaitCondition { reconnectLinks.isNotEmpty() }
        assertFalse(
            reconnectLinks.first().disconnected,
            "empty GATT must not cancel the pending autoConnect before the backoff",
        )
        awaitCondition { reconnectLinks.size >= 2 && manager.state.value == ConnectionState.Ready(DEVICE) }
    }

    @Test
    fun aSilentNotifyMissOnAutoReconnectHoldsTheLinkThroughTheBackoff() = runBlocking {
        val userLinks = mutableListOf<FakeMeshLink>()
        val reconnectLinks = mutableListOf<FakeMeshLink>()
        val manager = manager(
            link = { FakeMeshLink().also(userLinks::add) },
            reconnectLink = {
                FakeMeshLink().also { link ->
                    if (reconnectLinks.isEmpty()) {
                        link.failConnectWith = NotificationsNotEnabledException()
                    }
                    reconnectLinks += link
                }
            },
            session = { FakeMeshSession() },
            policy = fastPolicy,
        )
        manager.connect(DEVICE)
        userLinks.single().drop(LinkDropReason.CONNECTION_LOST)
        awaitCondition { reconnectLinks.isNotEmpty() }
        assertFalse(
            reconnectLinks.first().disconnected,
            "a silent notify miss must not cancel the pending autoConnect before the backoff",
        )
        awaitCondition { reconnectLinks.size >= 2 && manager.state.value == ConnectionState.Ready(DEVICE) }
    }

    @Test
    fun reconnectRetriesWithBackoffAcrossFailedAttemptsUntilItReconnects() = runBlocking {
        var attempt = 0
        val links = mutableListOf<FakeMeshLink>()
        val manager = manager(
            link = {
                attempt++
                FakeMeshLink().apply {
                    // The initial connect succeeds; the next two reconnect attempts fail. Transient
                    // connect failures re-issue autoConnect immediately (iOS retryPendingConnect).
                    if (attempt == 2 || attempt == 3) failConnectWith = MeshLinkConnectException("boom")
                    links += this
                }
            },
            session = { FakeMeshSession() },
            policy = fastPolicy,
        )

        manager.connect(DEVICE)
        links.first().drop(LinkDropReason.CONNECTION_LOST)

        awaitCondition { attempt == 4 && manager.state.value == ConnectionState.Ready(DEVICE) }
    }

    @Test
    fun aTransientReconnectConnectFailureReissuesWithoutTheWatchdogSleep() = runBlocking {
        val userLinks = mutableListOf<FakeMeshLink>()
        val reconnectLinks = mutableListOf<FakeMeshLink>()
        val manager = manager(
            link = { FakeMeshLink().also(userLinks::add) },
            reconnectLink = {
                FakeMeshLink().also { link ->
                    if (reconnectLinks.isEmpty()) {
                        link.failConnectWith = MeshLinkConnectException("133")
                    }
                    reconnectLinks += link
                }
            },
            session = { FakeMeshSession() },
            policy = ReconnectPolicy(
                ReconnectPolicy.Config(
                    baseBackoff = 30.seconds,
                    maxBackoff = 120.seconds,
                    sessionRebuildRetryDelay = 20.milliseconds,
                ),
            ),
        )

        manager.connect(DEVICE)
        userLinks.single().drop(LinkDropReason.CONNECTION_LOST)

        awaitCondition { reconnectLinks.size >= 2 && manager.state.value == ConnectionState.Ready(DEVICE) }
    }

    @Test
    fun transientFailuresNeverGiveUpEvenPastTheRetryBudget() = runBlocking {
        var attempt = 0
        val links = mutableListOf<FakeMeshLink>()
        val manager = manager(
            link = {
                attempt++
                FakeMeshLink().apply {
                    // The initial connect succeeds; every reconnect attempt fails transiently (an empty
                    // discovery / out-of-range connect). The loop must keep re-arming past the retry
                    // budget rather than dropping to Disconnected, and never escalate to BondLost.
                    if (attempt >= 2) failConnectWith = MeshLinkConnectException("no device found")
                    links += this
                }
            },
            session = { FakeMeshSession() },
            policy = fastPolicy,
        )

        manager.connect(DEVICE)
        links.first().drop(LinkDropReason.CONNECTION_LOST)

        awaitCondition { attempt >= PAST_BUDGET_ATTEMPTS }
        val state = manager.state.value
        assertTrue(state is ConnectionState.Reconnecting, "expected still Reconnecting, got $state")
    }

    @Test
    fun encryptionTimeoutsAreGracedWhileTheBondStampIsFresh() = runBlocking {
        var attempt = 0
        val links = mutableListOf<FakeMeshLink>()
        val manager = manager(
            link = {
                attempt++
                FakeMeshLink().apply {
                    if (attempt >= 2) failConnectWith = EncryptionTimeoutException("encryption timed out near range")
                    links += this
                }
            },
            session = { FakeMeshSession() },
            policy = fastPolicy,
        )

        // The initial connect stamps a verified encrypted session at the fixed clock.
        manager.connect(DEVICE)
        links.first().drop(LinkDropReason.CONNECTION_LOST)

        // Encryption timeouts dominate the budget, but the just-verified bond graces them: the loop keeps
        // reconnecting past the budget instead of routing a healthy radio to guided re-pair.
        awaitCondition { attempt >= PAST_BUDGET_ATTEMPTS }
        assertTrue(
            manager.state.value is ConnectionState.Reconnecting,
            "a fresh bond stamp must grace encryption timeouts",
        )
    }

    @Test
    fun encryptionTimeoutsEscalateToBondLostOnceTheGraceExpires() = runBlocking {
        var clock = FIXED_CLOCK_SECONDS
        var attempt = 0
        val links = mutableListOf<FakeMeshLink>()
        val manager = manager(
            link = {
                attempt++
                FakeMeshLink().apply {
                    if (attempt >= 2) failConnectWith = EncryptionTimeoutException("encryption timed out, dead bond")
                    links += this
                }
            },
            session = { FakeMeshSession() },
            policy = fastPolicy,
            nowEpochSeconds = { clock },
        )

        manager.connect(DEVICE) // stamps the bond at FIXED_CLOCK_SECONDS
        manager.setAppActive(true)
        // Age the stamp past the grace so recurring encryption timeouts read as a suspect bond.
        clock = FIXED_CLOCK_SECONDS + ReconnectPolicy().bondVerificationGrace.inWholeSeconds + 1
        links.first().drop(LinkDropReason.CONNECTION_LOST)

        awaitCondition { manager.state.value == ConnectionState.BondLost(DEVICE) }
        delay(SETTLE_MS)
        assertEquals(ConnectionState.BondLost(DEVICE), manager.state.value)
    }

    @Test
    fun encryptionTimeoutsKeepRetryingWhileTheAppIsInactive() = runBlocking {
        var attempt = 0
        val links = mutableListOf<FakeMeshLink>()
        val manager = manager(
            link = {
                attempt++
                FakeMeshLink().apply {
                    if (attempt >= 2) failConnectWith = EncryptionTimeoutException("encryption timed out, background")
                    links += this
                }
            },
            session = { FakeMeshSession() },
            policy = fastPolicy,
        )

        manager.connect(DEVICE)
        links.first().drop(LinkDropReason.CONNECTION_LOST)

        awaitCondition { attempt >= PAST_BUDGET_ATTEMPTS }
        val state = manager.state.value
        assertTrue(state is ConnectionState.Reconnecting, "inactive app must hold, got $state")
    }

    @Test
    fun aColdRebuildSeedsBondGraceFromPersistenceAndKeepsReconnecting() = runBlocking {
        val store = LastConnectionStore(one.meshcore.shared.prefs.InMemoryPreferencesStore())

        // Process 1: a full connect stamps and persists the bond at the fixed clock, then disconnects.
        val managerA = manager(
            link = { FakeMeshLink() },
            session = { FakeMeshSession() },
            lastConnectionStore = store,
        )
        managerA.connect(DEVICE)
        assertEquals(ConnectionState.Ready(DEVICE), managerA.state.value)
        managerA.disconnect()

        // Process 2 (cold rebuild): a fresh manager over the SAME store has an empty in-memory grace map.
        // Its first ceremony is held mid-sync (contactsGate), so it drops before stamping in-process; the
        // reconnect must then seed the grace from the persisted stamp and shield the encryption timeouts.
        val links = mutableListOf<FakeMeshLink>()
        var attempt = 0
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val managerB = manager(
            link = {
                attempt++
                FakeMeshLink().apply {
                    if (attempt >= 2) failConnectWith = EncryptionTimeoutException("encryption timed out near range")
                    links += this
                }
            },
            session = { FakeMeshSession().apply { if (attempt == 1) contactsGate = gate } },
            policy = fastPolicy,
            lastConnectionStore = store,
        )

        scope.launch { runCatching { managerB.connect(DEVICE) } }
        awaitCondition { links.isNotEmpty() && managerB.state.value == ConnectionState.Syncing(DEVICE) }
        links.first().drop(LinkDropReason.CONNECTION_LOST)

        awaitCondition { attempt >= PAST_BUDGET_ATTEMPTS }
        assertTrue(
            managerB.state.value is ConnectionState.Reconnecting,
            "a persisted bond stamp must seed the grace after a cold rebuild",
        )
    }

    @Test
    fun withoutAPersistedStampAColdRebuildEscalatesToBondLost() = runBlocking {
        // The store holds no prior-process bond stamp, so nothing seeds the grace: the contrast that proves
        // the persisted stamp is the deciding input in the passing case above.
        val store = LastConnectionStore(one.meshcore.shared.prefs.InMemoryPreferencesStore())
        val links = mutableListOf<FakeMeshLink>()
        var attempt = 0
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val manager = manager(
            link = {
                attempt++
                FakeMeshLink().apply {
                    if (attempt >= 2) failConnectWith = EncryptionTimeoutException("encryption timed out, no shield")
                    links += this
                }
            },
            session = { FakeMeshSession().apply { if (attempt == 1) contactsGate = gate } },
            policy = fastPolicy,
            lastConnectionStore = store,
        )
        manager.setAppActive(true)

        scope.launch { runCatching { manager.connect(DEVICE) } }
        awaitCondition { links.isNotEmpty() && manager.state.value == ConnectionState.Syncing(DEVICE) }
        links.first().drop(LinkDropReason.CONNECTION_LOST)

        awaitCondition { manager.state.value == ConnectionState.BondLost(DEVICE) }
        assertEquals(ConnectionState.BondLost(DEVICE), manager.state.value)
    }

    @Test
    fun forgettingTheRadioClearsTheBondShieldAndAColdRebuildEscalates() = runBlocking {
        val store = LastConnectionStore(one.meshcore.shared.prefs.InMemoryPreferencesStore())

        // A full connect stamps and persists the bond; forgetting the radio must clear the persisted shield.
        val managerA = manager(
            link = { FakeMeshLink() },
            session = { FakeMeshSession() },
            lastConnectionStore = store,
        )
        managerA.connect(DEVICE)
        assertEquals(DEVICE, store.bondVerifiedHandle())
        managerA.deleteSavedDevice(StableIds.uuid(DEVICE.encodeToByteArray()))
        assertNull(store.bondVerifiedHandle(), "forgetting the radio must clear its persisted bond shield")
        managerA.disconnect()

        // With the shield gone, a cold rebuild over the same store has no grace to seed: the same
        // encryption-timeout run that would be graced with a stamp now escalates to BondLost.
        val links = mutableListOf<FakeMeshLink>()
        var attempt = 0
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val managerB = manager(
            link = {
                attempt++
                FakeMeshLink().apply {
                    if (attempt >= 2) failConnectWith = EncryptionTimeoutException("encryption timed out")
                    links += this
                }
            },
            session = { FakeMeshSession().apply { if (attempt == 1) contactsGate = gate } },
            policy = fastPolicy,
            lastConnectionStore = store,
        )
        managerB.setAppActive(true)

        scope.launch { runCatching { managerB.connect(DEVICE) } }
        awaitCondition { links.isNotEmpty() && managerB.state.value == ConnectionState.Syncing(DEVICE) }
        links.first().drop(LinkDropReason.CONNECTION_LOST)

        awaitCondition { managerB.state.value == ConnectionState.BondLost(DEVICE) }
        assertEquals(ConnectionState.BondLost(DEVICE), managerB.state.value)
    }

    @Test
    fun forgettingADifferentRadioLeavesTheHoldersBondShieldIntact() = runBlocking {
        val store = LastConnectionStore(one.meshcore.shared.prefs.InMemoryPreferencesStore())
        val managerA = manager(
            link = { FakeMeshLink() },
            session = { FakeMeshSession() },
            lastConnectionStore = store,
        )
        managerA.connect(DEVICE)
        assertEquals(DEVICE, store.bondVerifiedHandle())

        // A second, non-holder radio row (its own BLE handle) — copied off the holder row so every column
        // is valid — is then forgotten. The clear is holder-matched by handle, so the holder's shield stays.
        val holderRow = db.deviceDao().listAll().single()
        val otherId = StableIds.uuid("other-radio".encodeToByteArray())
        db.deviceDao().upsert(
            holderRow.copy(
                id = otherId,
                connectionMethods = listOf(
                    one.meshcore.shared.backup.ConnectionMethod.Bluetooth(
                        peripheralUuid = otherId,
                        displayName = "other-handle",
                    ),
                ),
            ),
        )

        managerA.deleteSavedDevice(otherId)

        assertEquals(DEVICE, store.bondVerifiedHandle(), "forgetting a non-holder must not clear the holder's shield")
        assertEquals(FIXED_CLOCK_SECONDS, store.bondVerificationEpochSeconds(DEVICE))
    }

    @Test
    fun clearBondStampDropsThePersistedShield() = runBlocking {
        val store = LastConnectionStore(one.meshcore.shared.prefs.InMemoryPreferencesStore())
        val manager = manager(
            link = { FakeMeshLink() },
            session = { FakeMeshSession() },
            lastConnectionStore = store,
        )
        manager.connect(DEVICE)
        assertEquals(DEVICE, store.bondVerifiedHandle())
        manager.clearBondStamp(DEVICE)
        assertNull(store.bondVerifiedHandle())
        manager.persistBondRefreshIfStillValid(DEVICE)
        assertNull(store.bondVerifiedHandle(), "a keepalive tick must not recreate a forgotten stamp")
    }

    @Test
    fun aLiveBleSessionRefreshesTheBondStampOnTheKeepaliveCadence() = runBlocking {
        val store = LastConnectionStore(one.meshcore.shared.prefs.InMemoryPreferencesStore())
        var clock = FIXED_CLOCK_SECONDS
        val manager = manager(
            link = { FakeMeshLink() },
            session = { FakeMeshSession() },
            policy = ReconnectPolicy(ReconnectPolicy.Config(bondRefreshInterval = 20.milliseconds)),
            lastConnectionStore = store,
            nowEpochSeconds = { clock },
        )

        manager.connect(DEVICE)
        assertEquals(FIXED_CLOCK_SECONDS, store.bondVerificationEpochSeconds(DEVICE))
        clock = REFRESHED_CLOCK_SECONDS
        awaitCondition { store.bondVerificationEpochSeconds(DEVICE) == REFRESHED_CLOCK_SECONDS }
    }

    @Test
    fun aLiveSessionBondRefreshUpdatesThePersistedStampAndNeverCreatesAfterDisconnect() = runBlocking {
        val store = LastConnectionStore(one.meshcore.shared.prefs.InMemoryPreferencesStore())
        var clock = FIXED_CLOCK_SECONDS
        val manager = manager(
            link = { FakeMeshLink() },
            session = { FakeMeshSession() },
            lastConnectionStore = store,
            nowEpochSeconds = { clock },
        )

        manager.connect(DEVICE)
        assertEquals(FIXED_CLOCK_SECONDS, store.bondVerificationEpochSeconds(DEVICE))

        clock = REFRESHED_CLOCK_SECONDS
        manager.persistBondRefreshIfStillValid(DEVICE)
        assertEquals(REFRESHED_CLOCK_SECONDS, store.bondVerificationEpochSeconds(DEVICE))

        manager.disconnect()
        clock = AFTER_DISCONNECT_CLOCK_SECONDS
        manager.persistBondRefreshIfStillValid(DEVICE)
        assertEquals(
            REFRESHED_CLOCK_SECONDS,
            store.bondVerificationEpochSeconds(DEVICE),
            "a tick after disconnect must not refresh or recreate the stamp",
        )
    }

    @Test
    fun forgettingTheHolderStopsALaterRefreshFromResurrectingTheShield() = runBlocking {
        val store = LastConnectionStore(one.meshcore.shared.prefs.InMemoryPreferencesStore())
        var clock = FIXED_CLOCK_SECONDS
        val manager = manager(
            link = { FakeMeshLink() },
            session = { FakeMeshSession() },
            lastConnectionStore = store,
            nowEpochSeconds = { clock },
        )

        manager.connect(DEVICE)
        val holderRow = db.deviceDao().listAll().single()
        manager.deleteSavedDevice(holderRow.id)
        assertNull(store.bondVerifiedHandle())

        clock = REFRESHED_CLOCK_SECONDS
        manager.persistBondRefreshIfStillValid(DEVICE)
        assertNull(store.bondVerifiedHandle(), "a keepalive tick must never recreate a forgotten stamp")
    }

    @Test
    fun theLazySeedNeverOverwritesAFresherLiveStamp() = runBlocking {
        val store = LastConnectionStore(one.meshcore.shared.prefs.InMemoryPreferencesStore())
        val grace = ReconnectPolicy().bondVerificationGrace.inWholeSeconds.toDouble()
        val freshEpoch = grace * FRESH_STAMP_GRACE_MULTIPLE
        var clock = freshEpoch
        val links = mutableListOf<FakeMeshLink>()
        var attempt = 0
        val manager = manager(
            link = {
                attempt++
                FakeMeshLink().apply {
                    if (attempt >= 2) failConnectWith = EncryptionTimeoutException("encryption timed out near range")
                    links += this
                }
            },
            session = { FakeMeshSession() },
            policy = fastPolicy,
            lastConnectionStore = store,
            nowEpochSeconds = { clock },
        )

        // A completed handshake stamps a FRESH live session (in-memory + store) at freshEpoch.
        manager.connect(DEVICE)
        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)

        // Rewrite only the persisted slot to a stamp older than the grace at the reconnect clock; the live
        // in-memory stamp stays fresh. Advance the clock so the fresh stamp is still inside the grace but the
        // persisted one is well past it — so if the seed clobbered the live value, the loop would escalate.
        store.persistBondVerification(DEVICE, freshEpoch - grace - GRACE_MARGIN_SECONDS)
        clock = freshEpoch + grace - GRACE_MARGIN_SECONDS

        links.first().drop(LinkDropReason.CONNECTION_LOST)

        // Encryption timeouts dominate the budget; the fresher live stamp graces them. The lazy seed must not
        // overwrite it with the older persisted value, which would route this healthy radio to BondLost.
        awaitCondition { attempt >= PAST_BUDGET_ATTEMPTS }
        assertTrue(
            manager.state.value is ConnectionState.Reconnecting,
            "the lazy seed must not overwrite a fresher live stamp with an older persisted one",
        )
    }

    @Test
    fun aSessionRebuildFailureDoesNotEscalateAndRecoveryContinues() = runBlocking {
        var sessionAttempt = 0
        val userLinks = mutableListOf<FakeMeshLink>()
        val reconnectLinks = mutableListOf<FakeMeshLink>()
        val manager = manager(
            link = { FakeMeshLink().also(userLinks::add) },
            reconnectLink = { FakeMeshLink().also(reconnectLinks::add) },
            session = {
                sessionAttempt++
                // The link connects fine; the first reconnect session fails to start — a handshake
                // failure over live GATT. Recovery must reuse that link, never tear it down toward
                // BondLost or a fresh autoConnect.
                FakeMeshSession().apply {
                    if (sessionAttempt == 2) failStartWith = RuntimeException("session rebuild boom")
                }
            },
            policy = fastPolicy,
        )

        manager.connect(DEVICE)
        userLinks.single().drop(LinkDropReason.CONNECTION_LOST)

        awaitCondition { reconnectLinks.isNotEmpty() && manager.state.value == ConnectionState.Ready(DEVICE) }
        assertEquals(1, reconnectLinks.size, "handshake retry must reuse the live autoConnect GATT")
        assertFalse(reconnectLinks.single().disconnected, "in-place rebuild must not cancel autoConnect")
        assertTrue(sessionAttempt >= 3, "failed handshake must be retried on a fresh session")
    }

    @Test
    fun aQueryDeviceFailureRebuildsTheSessionOnTheLiveGatt() = runBlocking {
        var sessionAttempt = 0
        val userLinks = mutableListOf<FakeMeshLink>()
        val reconnectLinks = mutableListOf<FakeMeshLink>()
        val manager = manager(
            link = { FakeMeshLink().also(userLinks::add) },
            reconnectLink = { FakeMeshLink().also(reconnectLinks::add) },
            session = {
                sessionAttempt++
                FakeMeshSession().apply {
                    if (sessionAttempt == 2) {
                        failQueryWith = RuntimeException("queryDevice boom")
                    }
                }
            },
            policy = fastPolicy,
        )

        manager.connect(DEVICE)
        userLinks.single().drop(LinkDropReason.CONNECTION_LOST)

        awaitCondition { reconnectLinks.isNotEmpty() && manager.state.value == ConnectionState.Ready(DEVICE) }
        assertEquals(1, reconnectLinks.size)
        assertFalse(reconnectLinks.single().disconnected)
        assertTrue(sessionAttempt >= 3)
    }

    @Test
    fun exhaustingTheRebuildPreserveBudgetSeversTheLiveGatt() = runBlocking {
        val userLinks = mutableListOf<FakeMeshLink>()
        val reconnectLinks = mutableListOf<FakeMeshLink>()
        val manager = manager(
            link = { FakeMeshLink().also(userLinks::add) },
            reconnectLink = { FakeMeshLink().also(reconnectLinks::add) },
            session = {
                FakeMeshSession().apply {
                    if (reconnectLinks.isNotEmpty() && reconnectLinks.size == 1) {
                        failStartWith = RuntimeException("session rebuild boom")
                    }
                }
            },
            policy = fastPolicy,
        )

        manager.connect(DEVICE)
        userLinks.single().drop(LinkDropReason.CONNECTION_LOST)

        awaitCondition {
            reconnectLinks.size >= 2 &&
                reconnectLinks.first().disconnected &&
                manager.state.value == ConnectionState.Ready(DEVICE)
        }
    }

    @Test
    fun aLinkDropReauthenticatesTheRemoteSessionsThatWereOpen() = runBlocking {
        val session = FakeMeshSession()
        val passwords = InMemoryNodePasswordStore().also { it.storePassword(NODE_KEY, NODE_PASSWORD) }
        val links = mutableListOf<FakeMeshLink>()
        val manager = manager(
            link = { FakeMeshLink().also { links += it } },
            session = { session },
            nodePasswordStore = passwords,
        )
        seedConnectedRemoteSession()

        manager.connect(DEVICE)
        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
        // Nothing re-authenticates on a fresh connect: the queue is only filled by a drop.
        assertTrue(session.loginDestinations.isEmpty(), "a fresh connect must not re-authenticate")

        links.first().drop(LinkDropReason.CONNECTION_LOST)

        awaitCondition { manager.state.value == ConnectionState.Ready(DEVICE) && links.size == 2 }
        assertEquals(1, session.loginDestinations.size, "the open session must be re-authenticated once")
        assertTrue(session.loginDestinations.single().contentEquals(NODE_KEY))
        assertTrue(remoteSessionRow().isConnected, "a successful re-auth restores the session")
    }

    @Test
    fun anExplicitDisconnectDropsTheReauthQueueAndMarksTheSessionDisconnected() = runBlocking {
        val session = FakeMeshSession()
        val passwords = InMemoryNodePasswordStore().also { it.storePassword(NODE_KEY, NODE_PASSWORD) }
        val manager = manager(
            link = { FakeMeshLink() },
            session = { session },
            nodePasswordStore = passwords,
        )
        seedConnectedRemoteSession()

        manager.connect(DEVICE)
        manager.disconnect()

        assertFalse(remoteSessionRow().isConnected, "an explicit disconnect closes open remote sessions")

        // Reconnecting by hand is a user action, not a recovery: the node stays logged out until asked.
        manager.connect(DEVICE)
        delay(SETTLE_MS)
        assertTrue(session.loginDestinations.isEmpty(), "a user-initiated connect must not re-authenticate")
    }

    /** A remote-node session logged in on the connected radio's partition, as a live login would leave it. */
    private suspend fun seedConnectedRemoteSession() {
        db.remoteNodeSessionDao().upsert(
            one.meshcore.shared.remotenode.testSession(
                radioID = fixedRadioId,
                publicKey = NODE_KEY,
                isConnected = true,
                permissionLevel = one.meshcore.shared.model.RoomPermissionLevel.ADMIN,
            ).toEntity(),
        )
    }

    private suspend fun remoteSessionRow() = db.remoteNodeSessionDao().getAll().single()

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(TIMEOUT_MS) {
            while (!condition()) delay(POLL_MS)
        }
    }

    private suspend fun connectRadio(): ConnectedRadio {
        val session = FakeMeshSession()
        val link = FakeMeshLink()
        val manager = manager(link = { link }, session = { session })
        manager.connect(DEVICE)
        return ConnectedRadio(manager, session, link)
    }

    private fun manager(
        link: (String) -> MeshLink,
        reconnectLink: ((String) -> MeshLink)? = null,
        session: (MeshLink) -> FakeMeshSession,
        policy: ReconnectPolicy = ReconnectPolicy(),
        connectRetryPolicy: ConnectRetryPolicy = FAST_CONNECT_RETRY,
        handshakeTimeout: Duration = HANDSHAKE_COMMAND_TIMEOUT,
        bluetoothAdapterEnabled: () -> Boolean = { true },
        hasSystemPairingRegistry: () -> Boolean = { false },
        systemConnectedHandles: () -> Set<String> = { emptySet() },
        lastConnectionStore: LastConnectionStore? = null,
        intentStore: ConnectionIntentStore? = null,
        notifier: IngestNotifier = IngestNotifier.NoOp,
        nowEpochSeconds: () -> Double = FIXED_CLOCK,
        nodePasswordStore: NodePasswordStore = InMemoryNodePasswordStore(),
        wifiLinkFactory: (String, Int, suspend () -> Unit) -> MeshLink = { _, _, _ -> FakeMeshLink() },
        onConversationsChanged: suspend () -> Unit = {},
    ): ConnectionManager = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = link,
        sessionFactory = { session(it) },
        nowEpochSeconds = nowEpochSeconds,
        ports = ConnectionManagerPorts(
            reconnectLinkFactory = reconnectLink,
            wifiLinkFactory = wifiLinkFactory,
            policy = policy,
            connectRetryPolicy = connectRetryPolicy,
            handshakeTimeout = handshakeTimeout,
            bluetoothAdapterEnabled = bluetoothAdapterEnabled,
            hasSystemPairingRegistry = hasSystemPairingRegistry,
            systemConnectedHandles = systemConnectedHandles,
            newRadioId = { fixedRadioId },
            lastConnectionStore = lastConnectionStore,
            intentStore = intentStore,
            notifier = notifier,
            nodePasswordStore = nodePasswordStore,
            onConversationsChanged = onConversationsChanged,
        ),
    )

    /** Tracks [IngestNotifier.setSyncNotificationSuppression] for connect-sync. */
    private class RecordingSuppressNotifier : IngestNotifier {
        val suppressHistory = mutableListOf<Boolean>()

        override fun setSyncNotificationSuppression(suppress: Boolean) {
            suppressHistory += suppress
        }

        override suspend fun postDirectMessage(
            contact: ResolvedContact,
            text: String,
            messageID: Uuid,
            hasSelfMention: Boolean,
        ) {
        }

        override suspend fun postChannelMessage(notice: ChannelMessageNotice) {
        }

        override suspend fun recordUnresolvedChannel(channelIndex: Int, radioID: Uuid, senderTimestamp: Long) {
        }

        override suspend fun updateBadge() {
        }

        override suspend fun notifyConversationsChanged() {
        }
    }

    private companion object {
        const val DEVICE = "device-1"
        const val KEPT_CONTACT_SYNC = 1_700_000_000L
        const val KEPT_REGION = "NA"
        const val KEPT_FLOOD_SCOPE = "NA"
        const val KEPT_OCV_PRESET = "liion"
        const val KEPT_WIFI_HOST = "10.0.0.8"
        const val KEPT_WIFI_PORT = 5000
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
        const val SETTLE_MS = 150L
        const val FIXED_CLOCK_SECONDS = 1_000.0
        const val REFRESHED_CLOCK_SECONDS = 2_000.0
        const val AFTER_DISCONNECT_CLOCK_SECONDS = 3_000.0

        // Well past the 5-failure escalation budget, so a still-Reconnecting sample proves the loop never
        // gave up and never reset into a terminal state.
        const val PAST_BUDGET_ATTEMPTS = 8

        // A fresh stamp is placed at 3x the grace so a stamp one grace older than it stays comfortably
        // positive (a synthetic epoch), and a slack margin keeps the fresh stamp inside the grace and the
        // older one past it at the reconnect clock.
        const val FRESH_STAMP_GRACE_MULTIPLE = 3.0
        const val GRACE_MARGIN_SECONDS = 100.0
        val FIXED_CLOCK: () -> Double = { FIXED_CLOCK_SECONDS }
        val FAST_CONNECT_RETRY = ConnectRetryPolicy(baseBackoff = 1.milliseconds)

        // The remote node an admin session is open on across a link drop.
        const val NODE_PASSWORD = "admin-pw"
        val NODE_KEY = ByteArray(32) { 0x55 }
    }
}

private class ConnectedRadio(val manager: ConnectionManager, val session: FakeMeshSession, val link: FakeMeshLink)

private class LiveSessionStopProbeError(message: String = "probe") : Error(message)

private class LiveLinkDisconnectProbeError : Error("probe")
