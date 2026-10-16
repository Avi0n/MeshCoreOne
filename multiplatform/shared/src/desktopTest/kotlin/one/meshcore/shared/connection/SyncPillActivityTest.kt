package one.meshcore.shared.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.channel.ChannelSendTimeoutException
import one.meshcore.shared.contact.ContactDeviceException
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.meshcore.CommandResult
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.transport.MeshLink
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The syncing pill and the "Ready" toast over a real connect, mirroring the Swift sync-activity bracket:
 * `SyncCoordinator` raises the activity at the top of the contacts phase and releases it after channels
 * (`SyncCoordinator+Sync.swift:95,120`), the resync loop holds one across every attempt
 * (`ConnectionManager+SyncRetry.swift:114,161,194`), and the channel-only retry raises its own
 * (`SyncCoordinator+Sync.swift:644,676`).
 *
 * The toast is the load-bearing assertion. `ConnectionUiState.syncEnded` only shows it when the count it
 * decrements reaches zero, so a visible toast proves both halves of the bracket ran; the pill's own
 * counter is a `StateFlow` and conflates, so it is sampled only where the count is held across a wait.
 *
 * A real background scope is used (not virtual time) because Room dispatches to `Dispatchers.IO`.
 */
class SyncPillActivityTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope

    private val fixedRadioId = Uuid.normalized("A0000000-0000-0000-0000-000000000004")
    private val fastResync = ResyncPolicy(interval = RESYNC_INTERVAL_MS.milliseconds, maxAttempts = MAX_ATTEMPTS)
    private val fastChannelRetry =
        ChannelRetryPolicy(maxAttempts = CHANNEL_RETRY_ATTEMPTS, initialDelay = RETRY_DELAY_MS.milliseconds)

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-sync-pill", ".db").also { it.delete() }
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
    fun aCleanConnectEndsTheSyncActivityWithTheReadyToast() = runBlocking {
        val ui = ConnectionUiState(scope)
        val manager = manager(session = { FakeMeshSession() }, connectionUi = ui)

        manager.connect(DEVICE)

        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
        awaitCondition { ui.showReadyToast.value }
        assertTrue(ui.showReadyToast.value, "a completed sync must raise the Ready toast")
        assertEquals(0, ui.syncActivityCount.value, "the sync bracket must close, not leak a held count")
    }

    @Test
    fun theResyncLoopHoldsTheSyncingPillUntilItRecovers() = runBlocking {
        // The contact pull drops on the connect pass and answers on the next, so the loop runs one attempt.
        val session = sessionFailingContactReads(times = 1)
        val ui = ConnectionUiState(scope)
        val manager = manager(session = { session }, connectionUi = ui)

        manager.connect(DEVICE)

        assertEquals(ConnectionState.Syncing(DEVICE), manager.state.value)
        // Held across the whole wait, so this is a sample of a stable value rather than of a transient.
        awaitCondition { ui.syncActivityCount.value > 0 }
        assertTrue(ui.syncActivityCount.value > 0, "a retrying radio must keep the syncing pill up")

        awaitCondition { manager.state.value == ConnectionState.Ready(DEVICE) }
        awaitCondition { ui.showReadyToast.value }
        assertEquals(0, ui.syncActivityCount.value, "the resync bracket must close on recovery")
    }

    @Test
    fun aRadioThatNeverSyncsNeverShowsTheReadyToast() = runBlocking {
        // Every attempt in the budget fails, so the loop gives up and the link drops.
        val session = sessionFailingContactReads(times = ALWAYS_FAILING_READS)
        val ui = ConnectionUiState(scope)
        val manager = manager(session = { session }, connectionUi = ui)

        manager.connect(DEVICE)
        var toastSeen = false
        withTimeout(TIMEOUT_MS) {
            while (manager.state.value != ConnectionState.Disconnected) {
                if (ui.showReadyToast.value) toastSeen = true
                delay(POLL_MS)
            }
        }

        assertFalse(toastSeen, "a radio that never synced must not be announced as ready")
        assertTrue(ui.syncFailedPillVisible.value, "a spent resync budget still raises the sync-failed pill")
    }

    @Test
    fun theScheduledChannelRetryRaisesItsOwnSyncActivity() = runBlocking {
        // One slot times out through the connect pass and its in-phase retry, so the scheduled retry re-reads
        // it after the connection has already reached Ready — the window where the connection rung alone
        // would leave the pill hidden.
        val session = sessionWithFailingSlot(sendTimeouts = READS_BEFORE_SCHEDULED_RETRY)
        val ui = ConnectionUiState(scope)
        val manager = manager(session = { session }, connectionUi = ui)

        manager.connect(DEVICE)
        awaitCondition { ui.showReadyToast.value }
        // Clear the connect window's own toast so the next one can only come from the scheduled retry.
        ui.hideReadyToast()

        assertNotNull(awaitChannel(manager), "the scheduled retry must land the recovered slot")
        awaitCondition { ui.showReadyToast.value }
        assertEquals(0, ui.syncActivityCount.value, "the retry's bracket must close too")
    }

    /** A session whose contact pull drops [times] times before answering, one failure consumed per pass. */
    private fun sessionFailingContactReads(times: Int): FakeMeshSession = FakeMeshSession().apply {
        contactsFailures += MutableList(times) { ContactDeviceException(DEVICE_ERROR_CODE) }
    }

    /** A radio holding one configured slot whose reads send-time-out [sendTimeouts] times before answering. */
    private fun sessionWithFailingSlot(sendTimeouts: Int): FakeMeshSession = FakeMeshSession().apply {
        channelBatch = CommandResult.ChannelsResult(received = emptyList(), missing = listOf(FAILING_SLOT))
        channelSlots[FAILING_SLOT] =
            ChannelInfo(index = FAILING_SLOT, name = CHANNEL_NAME, secret = ByteArray(CHANNEL_SECRET_BYTES))
        channelSlotFailures[FAILING_SLOT] = MutableList(sendTimeouts) { ChannelSendTimeoutException() }
    }

    private suspend fun awaitChannel(manager: ConnectionManager): ChannelDto? {
        awaitCondition { manager.services?.channelService?.getChannel(fixedRadioId, FAILING_SLOT) != null }
        return manager.services?.channelService?.getChannel(fixedRadioId, FAILING_SLOT)
    }

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(TIMEOUT_MS) {
            while (!condition()) delay(POLL_MS)
        }
    }

    private fun manager(session: (MeshLink) -> FakeMeshSession, connectionUi: ConnectionUiState): ConnectionManager =
        ConnectionManager(
            database = db,
            scope = scope,
            linkFactory = { FakeMeshLink() },
            sessionFactory = session,
            nowEpochSeconds = { FIXED_CLOCK_SECONDS },
            ports = ConnectionManagerPorts(
                resyncPolicy = fastResync,
                channelRetryPolicy = fastChannelRetry,
                newRadioId = { fixedRadioId },
                connectionUi = connectionUi,
            ),
        )

    private companion object {
        const val DEVICE = "device-1"
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 5L
        const val FIXED_CLOCK_SECONDS = 1_000.0

        const val RESYNC_INTERVAL_MS = 200L
        const val MAX_ATTEMPTS = 3

        // The slot the batch read leaves unanswered, so the serial reconcile has to read it.
        const val FAILING_SLOT = 1
        const val CHANNEL_NAME = "Rescue"
        const val CHANNEL_SECRET_BYTES = 16

        // Long enough that the connect ceremony finishes before the first scheduled attempt fires.
        const val RETRY_DELAY_MS = 900L
        const val CHANNEL_RETRY_ATTEMPTS = 2

        // The connect pass and the channel phase's own in-phase retry, both spent before any scheduled attempt.
        const val READS_BEFORE_SCHEDULED_RETRY = 2

        // Any firmware sub-code other than NOT_FOUND: a device error, classified non-retryable, so the
        // contacts phase does not spend an extra read before reporting the failure.
        const val DEVICE_ERROR_CODE = 0x03

        // More failures than any test can consume, for a radio that never answers a contact pull.
        const val ALWAYS_FAILING_READS = 32
    }
}
