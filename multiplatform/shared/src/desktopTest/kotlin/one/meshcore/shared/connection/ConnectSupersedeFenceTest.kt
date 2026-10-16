package one.meshcore.shared.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.chat.ChannelMessageNotice
import one.meshcore.shared.chat.IngestNotifier
import one.meshcore.shared.chat.ResolvedContact
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferencesStore
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.transport.LinkDropReason
import one.meshcore.shared.transport.MeshLink
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Neither [ConnectionManager.disconnect] nor a second connect cancels an in-flight connect ceremony, so
 * the ceremony must re-check at every resume point that it still owns the connection it is building —
 * the fence the resync loop and the channel retry already apply through `isSyncLoopValid`. Without it a
 * ceremony superseded mid-sync promotes a torn-down container to Ready.
 *
 * Each test holds the ceremony inside its contacts pull ([FakeMeshSession.contactsGate]), supersedes it,
 * then releases the gate so the orphaned ceremony runs its remaining phases.
 */
class ConnectSupersedeFenceTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope

    private val fixedRadioId = Uuid.normalized("A0000000-0000-0000-0000-000000000041")

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-supersede", ".db").also { it.delete() }
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
    fun aDisconnectLandingMidSyncLeavesTheManagerDisconnected() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val manager = manager(sessions = { FakeMeshSession().apply { contactsGate = gate } })

        val ceremony = scope.launch { runCatching { manager.connect(DEVICE) } }
        awaitCondition { manager.state.value == ConnectionState.Syncing(DEVICE) }

        manager.disconnect()
        assertEquals(ConnectionState.Disconnected, manager.state.value)

        gate.complete(Unit)
        joinAndSettle(ceremony)

        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertNull(manager.services, "a superseded ceremony must not reinstall its container")
    }

    @Test
    fun aSecondConnectIsNotDemotedByTheCeremonyItSuperseded() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var built = 0
        val manager = manager(
            sessions = {
                built++
                FakeMeshSession().apply { if (built == 1) contactsGate = gate }
            },
        )

        val first = scope.launch { runCatching { manager.connect(DEVICE) } }
        awaitCondition { manager.state.value == ConnectionState.Syncing(DEVICE) }

        // A second connect (the Quick Settings tile, or a reconnect-last-device) retargets the manager.
        manager.connect(OTHER_DEVICE)
        assertEquals(ConnectionState.Ready(OTHER_DEVICE), manager.state.value)
        val live = manager.services

        gate.complete(Unit)
        joinAndSettle(first)

        assertEquals(ConnectionState.Ready(OTHER_DEVICE), manager.state.value)
        assertEquals(live, manager.services, "the superseded ceremony must not replace the live container")
    }

    @Test
    fun aSupersededCeremonyDoesNotClearTheLiveSyncSuppression() = runBlocking {
        val gateA = CompletableDeferred<Unit>()
        val gateB = CompletableDeferred<Unit>()
        val notifier = SuppressionSpy()
        var built = 0
        val manager = manager(
            sessions = {
                built++
                FakeMeshSession().apply { contactsGate = if (built == 1) gateA else gateB }
            },
            notifier = notifier,
        )

        val first = scope.launch { runCatching { manager.connect(DEVICE) } }
        awaitCondition { manager.state.value == ConnectionState.Syncing(DEVICE) }
        val second = scope.launch { runCatching { manager.connect(OTHER_DEVICE) } }
        awaitCondition { manager.state.value == ConnectionState.Syncing(OTHER_DEVICE) }
        assertTrue(notifier.isSuppressing, "the live ceremony must be suppressing while it syncs")

        // The superseded ceremony finishes and unwinds while the live one is still mid-sync.
        gateA.complete(Unit)
        joinAndSettle(first)

        assertTrue(
            notifier.isSuppressing,
            "a superseded ceremony must not clear the sync suppression a live ceremony owns",
        )

        // The owner still releases it at the end of its own window.
        gateB.complete(Unit)
        joinAndSettle(second)
        assertFalse(notifier.isSuppressing, "the owning ceremony must release the suppression when it ends")
    }

    @Test
    fun aSupersededCeremonyWithNoReplacementStillReleasesTheSuppression() = runBlocking {
        // The other half of the ownership rule: skipping the release when superseded must not strand the
        // flag on. A disconnect leaves nobody to raise a new sync window, so this ceremony has to clear it
        // or every later notification would be silently swallowed.
        val gate = CompletableDeferred<Unit>()
        val notifier = SuppressionSpy()
        val manager = manager(
            sessions = { FakeMeshSession().apply { contactsGate = gate } },
            notifier = notifier,
        )

        val ceremony = scope.launch { runCatching { manager.connect(DEVICE) } }
        awaitCondition { manager.state.value == ConnectionState.Syncing(DEVICE) }
        assertTrue(notifier.isSuppressing)

        manager.disconnect()
        gate.complete(Unit)
        joinAndSettle(ceremony)

        assertFalse(notifier.isSuppressing, "a superseded ceremony that nobody replaced must release the flag")
    }

    @Test
    fun aDropDuringConnectDoesNotLetTheOriginalCeremonyDemoteTheRecoveredConnection() = runBlocking {
        // The ceremony is held in `lastConnectionStore.persist`, the last await before it writes the rung
        // and installs its container — the window a drop-then-auto-reconnect actually lands in.
        val gate = CompletableDeferred<Unit>()
        val prefs = GateOnFirstWrite(PersistenceKeys.LAST_CONNECTED_DEVICE_HANDLE, gate)
        val links = mutableListOf<FakeMeshLink>()
        val manager = manager(
            sessions = { FakeMeshSession() },
            links = { FakeMeshLink().also { links += it } },
            lastConnectionStore = LastConnectionStore(prefs),
        )

        val ceremony = scope.launch { runCatching { manager.connect(DEVICE) } }
        awaitCondition { prefs.reachedGate }

        // The radio drops mid-connect. Auto-reconnect recovers it on a fresh link, to the same handle:
        // nothing about `wantsConnection` or `currentDeviceId` changed, and nothing cancelled the
        // original ceremony, which is still parked in its persist.
        links.first().drop(LinkDropReason.CONNECTION_LOST)
        awaitCondition { manager.state.value == ConnectionState.Ready(DEVICE) && links.size >= 2 }
        val recovered = manager.services

        gate.complete(Unit)
        joinAndSettle(ceremony)

        assertEquals(
            ConnectionState.Ready(DEVICE),
            manager.state.value,
            "the superseded ceremony must not demote the connection auto-reconnect recovered",
        )
        assertEquals(recovered, manager.services, "the superseded ceremony must not install its dead container")
    }

    @Test
    fun twoConnectsLandingInTheSameBeginWindowDoNotShareAGeneration() = runBlocking {
        // The launch activate and a sticky foreground-service restart both connect the last radio within
        // milliseconds. The first parks in `beginConnect`'s intent persist, the second supersedes it there.
        // Reading the generation after that await would hand both the same token, and the first would
        // then run a full ceremony of its own and tear the live link down as its failed retry.
        val gate = CompletableDeferred<Unit>()
        val prefs = GateOnFirstWrite(PersistenceKeys.USER_EXPLICITLY_DISCONNECTED, gate)
        val links = mutableListOf<FakeMeshLink>()
        val manager = manager(
            sessions = { FakeMeshSession() },
            links = { FakeMeshLink().also { links += it } },
            intentStore = ConnectionIntentStore(prefs),
        )

        val first = scope.launch { runCatching { manager.connect(DEVICE) } }
        awaitCondition { prefs.reachedGate }

        manager.connect(DEVICE)
        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
        val live = manager.services

        gate.complete(Unit)
        joinAndSettle(first)

        assertEquals(1, links.size, "the superseded connect must not open a link of its own")
        assertFalse(links.single().disconnected, "the superseded connect must not tear the live link down")
        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
        assertEquals(live, manager.services)
    }

    @Test
    fun anAutoConnectJoinsTheAttemptAlreadyConnectingToTheSameRadio() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val links = mutableListOf<FakeMeshLink>()
        val manager = manager(
            sessions = { FakeMeshSession() },
            links = {
                FakeMeshLink().also {
                    it.connectGate = gate
                    links += it
                }
            },
        )

        val first = scope.launch { runCatching { manager.connect(DEVICE) } }
        awaitCondition { manager.state.value == ConnectionState.Connecting(DEVICE) && links.size == 1 }

        manager.connect(DEVICE)
        assertEquals(1, links.size, "a second auto-connect must not open a link while one is mid-GATT")
        assertFalse(links.single().disconnected)

        gate.complete(Unit)
        joinAndSettle(first)
        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
    }

    @Test
    fun aStaleDropFromThePreviousSameHandleLinkDoesNotTearDownTheLiveSession() = runBlocking {
        // CoreBluetooth (and Kable) can deliver the previous GATT's disconnect after the successor
        // to the same handle is already Ready. Handle identity is not a fence: reconnect never
        // changes currentDeviceId.
        val links = mutableListOf<FakeMeshLink>()
        val manager = manager(
            sessions = { FakeMeshSession() },
            links = { FakeMeshLink().also { links += it } },
        )

        manager.connect(DEVICE)
        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
        val staleListener = checkNotNull(links.single().listener)

        manager.disconnect()
        manager.connect(DEVICE)
        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
        assertEquals(2, links.size)
        val live = manager.services

        staleListener.onDisconnected(LinkDropReason.CONNECTION_LOST)
        delay(SETTLE_MS)

        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
        assertEquals(live, manager.services, "a delayed drop from the previous GATT must not tear down the successor")
        assertEquals(2, links.size, "a stale drop must not start a reconnect of its own")
        assertFalse(links.last().disconnected)
    }

    @Test
    fun aUserConnectJoinsAnAttemptAlreadyConnectingToTheSameRadio() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val links = mutableListOf<FakeMeshLink>()
        var built = 0
        val manager = manager(
            sessions = { FakeMeshSession() },
            links = {
                FakeMeshLink().also {
                    built++
                    if (built == 1) it.connectGate = gate
                    links += it
                }
            },
        )

        val first = scope.launch { runCatching { manager.connect(DEVICE) } }
        awaitCondition { manager.state.value == ConnectionState.Connecting(DEVICE) && links.size == 1 }

        val second = scope.launch { runCatching { manager.connect(DEVICE, forceReconnect = true) } }
        delay(SETTLE_MS)
        assertEquals(ConnectionState.Connecting(DEVICE), manager.state.value)
        assertEquals(1, links.size, "forceReconnect joins the in-flight same-device connect")
        assertFalse(links.first().disconnected)

        gate.complete(Unit)
        joinAndSettle(first)
        joinAndSettle(second)
        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
        assertEquals(1, links.size)
    }

    @Test
    fun aCancelledConnectTearsItsLinkDownAndLeavesTheManagerDisconnected() = runBlocking {
        // The connect sheet's scope dies mid-GATT (dismissed, or the activity recreated). Nothing else
        // owns the half-built link, so the manager must not stay Connecting on it.
        val gate = CompletableDeferred<Unit>()
        val links = mutableListOf<FakeMeshLink>()
        val manager = manager(
            sessions = { FakeMeshSession() },
            links = {
                FakeMeshLink().also {
                    it.connectGate = gate
                    links += it
                }
            },
        )

        val ceremony = scope.launch { runCatching { manager.connect(DEVICE) } }
        awaitCondition { links.size == 1 && links.first().connectCount == 1 }
        assertEquals(ConnectionState.Connecting(DEVICE), manager.state.value)

        ceremony.cancel()
        joinAndSettle(ceremony)

        assertTrue(links.single().disconnected, "the in-flight link must be torn down with its ceremony")
        assertEquals(ConnectionState.Disconnected, manager.state.value)
    }

    @Test
    fun aCancelledConnectAfterReadyLeavesTheLiveSession() = runBlocking {
        // The connect sheet dismisses on Ready while connect() is still in the clock-sync tail.
        // Ready already has a drop monitor; tearing GATT down is the "synced then immediately dropped" race.
        val gate = CompletableDeferred<Unit>()
        val links = mutableListOf<FakeMeshLink>()
        val manager = manager(
            sessions = { FakeMeshSession().also { it.getTimeGate = gate } },
            links = { FakeMeshLink().also { links += it } },
        )

        val ceremony = scope.launch { runCatching { manager.connect(DEVICE) } }
        awaitCondition { manager.state.value == ConnectionState.Ready(DEVICE) && links.size == 1 }

        ceremony.cancel()
        joinAndSettle(ceremony)

        assertFalse(links.single().disconnected, "Ready is a live session; a cancelled caller must not drop it")
        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
    }

    @Test
    fun aCancelledSupersededConnectLeavesTheReplacementAlone() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var built = 0
        val manager = manager(
            sessions = { FakeMeshSession() },
            links = {
                FakeMeshLink().also {
                    built++
                    if (built == 1) it.connectGate = gate
                }
            },
        )

        val first = scope.launch { runCatching { manager.connect(DEVICE) } }
        awaitCondition { built == 1 && manager.state.value == ConnectionState.Connecting(DEVICE) }

        manager.connect(OTHER_DEVICE)
        assertEquals(ConnectionState.Ready(OTHER_DEVICE), manager.state.value)
        val live = manager.services

        first.cancel()
        joinAndSettle(first)

        assertEquals(ConnectionState.Ready(OTHER_DEVICE), manager.state.value)
        assertEquals(live, manager.services, "a cancelled superseded ceremony must not tear down the live connection")
    }

    @Test
    fun aCancelledWifiConnectTearsItsLinkDownAndLeavesTheManagerDisconnected() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val links = mutableListOf<FakeMeshLink>()
        val manager = manager(
            sessions = { FakeMeshSession() },
            wifiLinks = {
                FakeMeshLink().also {
                    it.connectGate = gate
                    links += it
                }
            },
        )
        val handle = "$WIFI_HOST:$WIFI_PORT"

        val ceremony = scope.launch { runCatching { manager.connectViaWiFi(WIFI_HOST, WIFI_PORT) } }
        awaitCondition { links.size == 1 && links.first().connectCount == 1 }
        assertEquals(ConnectionState.Connecting(handle), manager.state.value)

        ceremony.cancel()
        joinAndSettle(ceremony)

        assertTrue(links.single().disconnected, "the in-flight WiFi link must be torn down with its ceremony")
        assertEquals(ConnectionState.Disconnected, manager.state.value)
    }

    /** Records the live value of the sync-notification suppression flag. */
    private class SuppressionSpy : IngestNotifier {
        var isSuppressing = false
            private set

        override fun setSyncNotificationSuppression(suppress: Boolean) {
            isSuppressing = suppress
        }

        override suspend fun postDirectMessage(
            contact: ResolvedContact,
            text: String,
            messageID: Uuid,
            hasSelfMention: Boolean,
        ) = Unit

        override suspend fun postChannelMessage(notice: ChannelMessageNotice) = Unit

        override suspend fun recordUnresolvedChannel(channelIndex: Int, radioID: Uuid, senderTimestamp: Long) = Unit

        override suspend fun updateBadge() = Unit

        override suspend fun notifyConversationsChanged() = Unit
    }

    /**
     * Suspends the first write of [gatedKey] so a test can park one connect ceremony at that await: the
     * last-connected handle is the final await inside `lastConnectionStore.persist`, the disconnect bit
     * the one inside `beginConnect`.
     */
    private class GateOnFirstWrite(private val gatedKey: String, private val gate: CompletableDeferred<Unit>) :
        PreferencesStore {
        private val backing = InMemoryPreferencesStore()
        private var gated = false

        @Volatile
        var reachedGate = false
            private set

        private suspend fun parkIfGated(key: String) {
            if (key == gatedKey && !gated) {
                gated = true
                reachedGate = true
                gate.await()
            }
        }

        override suspend fun putString(key: String, value: String) {
            parkIfGated(key)
            backing.putString(key, value)
        }

        override suspend fun getString(key: String): String? = backing.getString(key)

        override suspend fun getBoolean(key: String): Boolean? = backing.getBoolean(key)

        override suspend fun getInt(key: String): Int? = backing.getInt(key)

        override suspend fun getDouble(key: String): Double? = backing.getDouble(key)

        override suspend fun putBoolean(key: String, value: Boolean) {
            parkIfGated(key)
            backing.putBoolean(key, value)
        }

        override suspend fun putInt(key: String, value: Int) = backing.putInt(key, value)

        override suspend fun putDouble(key: String, value: Double) = backing.putDouble(key, value)

        override suspend fun remove(key: String) = backing.remove(key)
    }

    private suspend fun joinAndSettle(job: Job) {
        withTimeout(TIMEOUT_MS) { job.join() }
        delay(SETTLE_MS)
    }

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(TIMEOUT_MS) {
            while (!condition()) delay(POLL_MS)
        }
    }

    private fun manager(
        sessions: () -> FakeMeshSession,
        links: () -> MeshLink = { FakeMeshLink() },
        wifiLinks: () -> MeshLink = { FakeMeshLink() },
        notifier: IngestNotifier = IngestNotifier.NoOp,
        lastConnectionStore: LastConnectionStore? = null,
        intentStore: ConnectionIntentStore? = null,
    ): ConnectionManager = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = { links() },
        sessionFactory = { sessions() },
        nowEpochSeconds = { FIXED_CLOCK_SECONDS },
        ports = ConnectionManagerPorts(
            wifiLinkFactory = { _, _, _ -> wifiLinks() },
            newRadioId = { fixedRadioId },
            lastConnectionStore = lastConnectionStore,
            intentStore = intentStore,
            notifier = notifier,
            nodePasswordStore = InMemoryNodePasswordStore(),
        ),
    )

    private companion object {
        const val DEVICE = "device-1"
        const val OTHER_DEVICE = "device-2"
        const val WIFI_HOST = "10.0.0.1"
        const val WIFI_PORT = 4000
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
        const val SETTLE_MS = 150L
        const val FIXED_CLOCK_SECONDS = 1_000.0
    }
}
