package one.meshcore.shared.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.channel.ChannelDeviceException
import one.meshcore.shared.chat.ChannelMessageNotice
import one.meshcore.shared.chat.IngestNotifier
import one.meshcore.shared.chat.ResolvedContact
import one.meshcore.shared.contact.ContactDeviceException
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.meshcore.CommandResult
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The post-connect sync-retry loop over a fake link and fake session, mirroring the Swift
 * `ConnectionManagerResyncLoopTests`: a contacts phase that comes back short holds the connection at
 * [ConnectionState.Syncing] and retries, a recovered attempt promotes and clears the pill, a spent budget
 * raises the pill and drops the link, and a loop orphaned by a later connection cannot tear its successor
 * down. Only the contacts phase decides usability, so a channel slot that will not read reaches Ready.
 *
 * A real background scope is used (not a virtual-time dispatcher) because Room dispatches its queries to
 * `Dispatchers.IO`, which virtual time cannot control — the same reason [ConnectionManagerTest] polls the
 * observable state. The shipped interval and attempt budget are pinned by [ResyncPolicy]'s defaults; here a
 * millisecond-scaled policy keeps the loop fast and deterministic.
 */
class ConnectionResyncLoopTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope

    private val fixedRadioId = Uuid.normalized("A0000000-0000-0000-0000-000000000002")
    private val fastResync = ResyncPolicy(interval = RESYNC_INTERVAL_MS.milliseconds, maxAttempts = MAX_ATTEMPTS)

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-resync", ".db").also { it.delete() }
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
    fun aChannelSlotThatWillNotReadStillReachesReady() = runBlocking {
        // One slot never answers its read, so every channel pass comes back short. The identity graph is
        // intact, so the radio is usable: holding it at Syncing would spend the resync budget and drop a
        // working link over a single channel slot.
        val session = sessionFailingChannelReads(times = ALWAYS_FAILING_READS)
        val ui = ConnectionUiState(scope)
        val manager = manager(session = { session }, connectionUi = ui)

        manager.connect(DEVICE)

        assertEquals(
            ConnectionState.Ready(DEVICE),
            manager.state.value,
            "a channel phase short one slot must not hold a usable radio at Syncing",
        )

        // Past the point a resync loop would have spent its budget and disconnected.
        delay(RESYNC_INTERVAL_MS * (MAX_ATTEMPTS + 1))

        assertEquals(
            ConnectionState.Ready(DEVICE),
            manager.state.value,
            "a channel phase short one slot must not drive the radio offline",
        )
        assertNotNull(manager.services, "the connection must survive an unreadable channel slot")
        assertFalse(ui.syncFailedPillVisible.value, "an unreadable channel slot is not a sync failure")
    }

    @Test
    fun aShortContactsPassHoldsAtSyncingAndTheLoopPromotesOnceItRecovers() = runBlocking {
        // The contact pull drops on the connect pass and answers on the next: the identity graph is
        // incomplete, which is the one phase that decides whether the connection is usable.
        val session = sessionFailingContactReads(times = 1)
        val manager = manager(session = { session })

        manager.connect(DEVICE)

        assertEquals(
            ConnectionState.Syncing(DEVICE),
            manager.state.value,
            "a connection without contacts must not present a Ready radio",
        )

        awaitCondition { manager.state.value == ConnectionState.Ready(DEVICE) }
    }

    @Test
    fun aShortContactsPassStartsAutoFetchBeforeTheResyncWait() = runBlocking {
        // iOS `onConnectionEstablished` starts auto-fetch even when contacts are not usable, then
        // pauses it around each resync backlog poll. Without that, `messagesWaiting` sits on the
        // radio until the first resync `pollBacklog`.
        val session = sessionFailingContactReads(times = 1)
        val manager = manager(session = { session })

        manager.connect(DEVICE)

        assertEquals(ConnectionState.Syncing(DEVICE), manager.state.value)
        assertTrue(
            session.autoFetchStartCount >= 1,
            "auto-fetch must start after a failed-contact initial window, not wait for Ready",
        )

        awaitCondition { manager.state.value == ConnectionState.Ready(DEVICE) }

        assertTrue(session.autoFetchStopCount >= 1, "resync must pause the session drain around pollBacklog")
        assertTrue(
            session.autoFetchStartCount >= 2,
            "resync must resume the session drain after the backlog poll",
        )
    }

    @Test
    fun aResyncAttemptReArmsNotificationSuppression() = runBlocking {
        // Connect sets suppress, then the owner finally clears it before the loop's first wait. Each
        // performResync must raise it again so the recovered backlog drain does not post (iOS
        // performResync). History is therefore connect true/false plus one resync true/false.
        val notifier = RecordingSuppressNotifier()
        val session = sessionFailingContactReads(times = 1)
        val manager = manager(session = { session }, notifier = notifier)

        manager.connect(DEVICE)
        awaitCondition { manager.state.value == ConnectionState.Ready(DEVICE) }

        assertEquals(listOf(true, false, true, false), notifier.suppressHistory)
    }

    @Test
    fun aRecoveredResyncClearsTheSyncFailedPill() = runBlocking {
        val ui = ConnectionUiState(scope)
        // The pill an earlier failure left up must not survive a connection that has since synced.
        ui.showSyncFailedPill()
        val session = sessionFailingContactReads(times = 1)
        val manager = manager(session = { session }, connectionUi = ui)

        manager.connect(DEVICE)
        awaitCondition { manager.state.value == ConnectionState.Ready(DEVICE) }

        assertFalse(ui.syncFailedPillVisible.value, "a recovered resync must clear the sync-failed pill")
    }

    @Test
    fun anExhaustedResyncBudgetRaisesThePillAndDropsTheLinkWithoutClearingIntent() = runBlocking {
        // The connect pass plus every attempt in the budget fail, so the loop runs out of attempts.
        val session = sessionFailingContactReads(times = MAX_ATTEMPTS + 1)
        val ui = ConnectionUiState(scope)
        val intentStore = ConnectionIntentStore(InMemoryPreferencesStore())
        val manager = manager(session = { session }, connectionUi = ui, intentStore = intentStore)

        manager.connect(DEVICE)
        awaitCondition { manager.state.value == ConnectionState.Disconnected }

        assertTrue(ui.syncFailedPillVisible.value, "a spent resync budget must raise the sync-failed pill")
        assertNull(manager.services, "the link is dropped when the resync budget is spent")
        assertFalse(
            intentStore.restore().isUserDisconnected,
            "a resync failure must preserve the connection intent — the user still wants this radio",
        )
    }

    @Test
    fun aForcedFullSyncIsRetainedAcrossResyncAttempts() = runBlocking {
        val aliceKey = ByteArray(32) { 0x09 }
        val session = FakeMeshSession(
            contacts = listOf(sampleContact(aliceKey, "Alice").copy(lastModified = ALICE_LAST_MODIFIED)),
        )
        val manager = manager(session = { session })

        manager.connect(DEVICE)
        session.contacts = emptyList()
        manager.disconnect()
        session.getContactsSince.clear()
        session.contactsFailures += ContactDeviceException(DEVICE_ERROR_CODE)

        manager.connect(DEVICE, forceFullSync = true)
        awaitCondition { manager.state.value == ConnectionState.Ready(DEVICE) }

        assertTrue(
            session.getContactsSince.all { it == null },
            "a contacts throw on a forced connect must still prune on resync, not fall back to the watermark",
        )
        assertTrue(
            db.contactDao().listByRadio(fixedRadioId).isEmpty(),
            "the retained force-full resync must prune a contact the radio no longer holds",
        )
    }

    @Test
    fun checkSyncHealthIsANoOpWhenTheRadioIsReady() = runBlocking {
        val session = FakeMeshSession()
        val manager = manager(session = { session })
        manager.connect(DEVICE)
        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
        val pulls = session.getContactsSince.size

        manager.checkSyncHealth()
        delay(RESYNC_INTERVAL_MS)

        assertEquals(pulls, session.getContactsSince.size, "a Ready radio must not start a new contacts pull")
        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
    }

    @Test
    fun checkSyncHealthDoesNotStartAResyncWhileTheFirstSyncIsStillRunning() = runBlocking {
        val session = FakeMeshSession()
        val gate = CompletableDeferred<Unit>()
        session.contactsGate = gate
        val manager = manager(session = { session })
        val connectJob = scope.launch { manager.connect(DEVICE) }

        awaitCondition { manager.state.value is ConnectionState.Syncing }
        // Syncing is published before GET_CONTACTS, so the in-flight pull must be recorded first.
        awaitCondition { session.getContactsSince.isNotEmpty() }
        val pulls = session.getContactsSince.size
        manager.checkSyncHealth()
        delay(RESYNC_INTERVAL_MS)

        assertEquals(
            pulls,
            session.getContactsSince.size,
            "foreground must not start GET_CONTACTS beside the first sync",
        )
        gate.complete(Unit)
        connectJob.join()
        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
    }

    @Test
    fun anOrphanedResyncLoopNeverDisconnectsTheSuccessorConnection() = runBlocking {
        // The first radio never answers a contact pull, so its resync loop is still running when the user
        // connects a second radio. That loop is now orphaned: its container has been replaced.
        val staleSession = sessionFailingContactReads(times = ALWAYS_FAILING_READS)
        val freshSession = FakeMeshSession()
        var connects = 0
        val ui = ConnectionUiState(scope)
        val manager = manager(
            session = { if (++connects == 1) staleSession else freshSession },
            connectionUi = ui,
        )

        manager.connect(STALE_DEVICE)
        assertEquals(ConnectionState.Syncing(STALE_DEVICE), manager.state.value)

        manager.connect(DEVICE)
        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)

        // Past the point the orphaned loop would have spent its budget on the replaced container.
        delay(RESYNC_INTERVAL_MS * (MAX_ATTEMPTS + 1))

        assertEquals(
            ConnectionState.Ready(DEVICE),
            manager.state.value,
            "an orphaned resync loop must not tear down the successor connection",
        )
        assertNotNull(manager.services, "the successor connection's services must survive an orphaned loop")
        assertFalse(ui.syncFailedPillVisible.value, "an orphaned loop must not raise the pill over a synced radio")
    }

    /**
     * A session whose channel reads fail [times] times before answering. The batch read reports the slot as
     * unanswered, so the phase falls back to the serial reconcile that consumes one queued failure per pass;
     * a device error is non-retryable, so each sync pass costs exactly one read.
     */
    private fun sessionFailingChannelReads(times: Int): FakeMeshSession = FakeMeshSession().apply {
        channelBatch = CommandResult.ChannelsResult(received = emptyList(), missing = listOf(FAILING_SLOT))
        channelSlotFailures[FAILING_SLOT] = MutableList(times) { ChannelDeviceException(DEVICE_ERROR_CODE) }
    }

    /** A session whose contact pull drops [times] times before answering, one failure consumed per pass. */
    private fun sessionFailingContactReads(times: Int): FakeMeshSession = FakeMeshSession().apply {
        contactsFailures += MutableList(times) { ContactDeviceException(DEVICE_ERROR_CODE) }
    }

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(TIMEOUT_MS) {
            while (!condition()) delay(POLL_MS)
        }
    }

    private fun manager(
        session: (MeshLink) -> FakeMeshSession,
        connectionUi: ConnectionUiState? = null,
        intentStore: ConnectionIntentStore? = null,
        notifier: IngestNotifier = IngestNotifier.NoOp,
    ): ConnectionManager = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = { FakeMeshLink() },
        sessionFactory = session,
        nowEpochSeconds = { FIXED_CLOCK_SECONDS },
        ports = ConnectionManagerPorts(
            resyncPolicy = fastResync,
            newRadioId = { fixedRadioId },
            intentStore = intentStore,
            connectionUi = connectionUi,
            notifier = notifier,
        ),
    )

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
        const val STALE_DEVICE = "device-0"
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
        const val FIXED_CLOCK_SECONDS = 1_000.0

        // Long enough that a connect ceremony (Room writes included) completes inside one interval, short
        // enough that a spent budget lands well inside the poll timeout.
        const val RESYNC_INTERVAL_MS = 200L
        const val MAX_ATTEMPTS = 3

        // The slot the batch read leaves unanswered, so the serial reconcile has to read it.
        const val FAILING_SLOT = 0

        // Any firmware sub-code other than NOT_FOUND: a device error, which is classified non-retryable, so
        // the channel phase does not spend its in-phase retry (or a backoff) before reporting the failure.
        const val DEVICE_ERROR_CODE = 0x03

        const val ALICE_LAST_MODIFIED = 1_699_000_000L

        // More failures than any test can consume, for a radio that never recovers the slot.
        const val ALWAYS_FAILING_READS = 32
    }
}
