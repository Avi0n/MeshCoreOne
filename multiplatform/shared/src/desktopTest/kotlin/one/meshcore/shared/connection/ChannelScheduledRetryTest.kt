package one.meshcore.shared.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.channel.ChannelSendTimeoutException
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The scheduled channel-only retry, mirroring the Swift `ConnectionManager.scheduleChannelOnlyRetry`: a slot
 * the connect pass and its in-phase retry both failed to read is re-read on a bounded backoff schedule, and
 * the recovered slot lands as a row.
 *
 * The row is the point. Without it every inbound message on that channel is treated as unconfigured — saved
 * with no unread count, no notification and no conversation — so these tests assert the persisted channel,
 * not that a retry function was reached.
 *
 * A real background scope is used (not virtual time) because Room dispatches to `Dispatchers.IO`; the
 * schedule is scaled to milliseconds through [ChannelRetryPolicy], whose shipped values stay pinned by its
 * defaults.
 */
class ChannelScheduledRetryTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope

    private val fixedRadioId = Uuid.normalized("A0000000-0000-0000-0000-000000000003")
    private val fastChannelRetry =
        ChannelRetryPolicy(maxAttempts = MAX_ATTEMPTS, initialDelay = RETRY_DELAY_MS.milliseconds)

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-channel-retry", ".db").also { it.delete() }
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
    fun aSlotThatTimedOutThroughTheConnectPassLandsOnTheScheduledRetry() = runBlocking {
        // The slot's read times out on the connect pass and again on the phase's own retry, so the connection
        // reaches Ready carrying no row for a channel the radio does have.
        val session = sessionWithSlot(sendTimeouts = READS_BEFORE_SCHEDULED_RETRY)
        val manager = manager { session }

        manager.connect(DEVICE)

        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value, "a short channel phase still reaches Ready")
        assertNull(storedChannel(manager), "the timed-out slot has no row when the connect window closes")

        awaitCondition { storedChannel(manager) != null }

        val recovered = assertNotNull(storedChannel(manager), "the scheduled retry must land the recovered slot")
        assertEquals(FAILING_SLOT, recovered.index, "the recovered channel keeps its slot; a retry never relocates")
        assertEquals(CHANNEL_NAME, recovered.name)
        assertTrue(recovered.secret.contentEquals(channelSecret()), "the recovered row carries the radio's secret")
        assertEquals(
            listOf(FAILING_SLOT),
            storedChannels(manager).map { it.index },
            "the retry upserts the slot it re-read; it must not mint a second row",
        )
    }

    @Test
    fun aSlotThatNeverAnswersStopsAtTheAttemptBudget() = runBlocking {
        // The radio never answers this slot. The retry is bounded: two scheduled attempts after the connect
        // pass and its in-phase retry, then it stops rather than reading the radio forever.
        val session = sessionWithSlot(sendTimeouts = ALWAYS_FAILING_READS)
        val manager = manager { session }

        manager.connect(DEVICE)
        delay(RETRY_DELAY_MS * QUIET_INTERVALS)

        assertEquals(
            READS_BEFORE_SCHEDULED_RETRY + MAX_ATTEMPTS,
            slotReadCount(session),
            "the connect pass and its in-phase retry, then exactly the scheduled attempt budget",
        )
        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value, "an unreadable slot never drops the link")
    }

    @Test
    fun aDisconnectStopsTheScheduledRetry() = runBlocking {
        // The retry is armed and has spent one attempt when the user disconnects: it must not keep talking to
        // a radio the manager has let go.
        val session = sessionWithSlot(sendTimeouts = ALWAYS_FAILING_READS)
        val manager = manager { session }

        manager.connect(DEVICE)
        awaitCondition { slotReadCount(session) > READS_BEFORE_SCHEDULED_RETRY }
        manager.disconnect()
        val readsAtDisconnect = slotReadCount(session)

        delay(RETRY_DELAY_MS * QUIET_INTERVALS)

        assertEquals(readsAtDisconnect, slotReadCount(session), "a disconnected radio must not be read again")
    }

    /**
     * A radio holding one configured slot whose reads send-time-out [sendTimeouts] times before answering. The
     * batch read reports the slot as unanswered so the serial reconcile has to read it, and a send timeout
     * propagates raw (no in-fetch backoff), so each pass costs exactly one queued failure.
     */
    private fun sessionWithSlot(sendTimeouts: Int): FakeMeshSession = FakeMeshSession().apply {
        channelBatch = CommandResult.ChannelsResult(received = emptyList(), missing = listOf(FAILING_SLOT))
        channelSlots[FAILING_SLOT] = ChannelInfo(index = FAILING_SLOT, name = CHANNEL_NAME, secret = channelSecret())
        channelSlotFailures[FAILING_SLOT] = MutableList(sendTimeouts) { ChannelSendTimeoutException() }
    }

    private fun slotReadCount(session: FakeMeshSession): Int =
        ALWAYS_FAILING_READS - (session.channelSlotFailures[FAILING_SLOT]?.size ?: 0)

    private fun channelSecret(): ByteArray = ByteArray(CHANNEL_SECRET_BYTES) { SECRET_BYTE }

    private suspend fun storedChannel(manager: ConnectionManager): ChannelDto? =
        manager.services?.channelService?.getChannel(fixedRadioId, FAILING_SLOT)

    private suspend fun storedChannels(manager: ConnectionManager): List<ChannelDto> =
        manager.services?.channelService?.getChannels(fixedRadioId).orEmpty()

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(TIMEOUT_MS) {
            while (!condition()) delay(POLL_MS)
        }
    }

    private fun manager(session: (MeshLink) -> FakeMeshSession): ConnectionManager = ConnectionManager(
        database = db,
        scope = scope,
        linkFactory = { FakeMeshLink() },
        sessionFactory = session,
        nowEpochSeconds = { FIXED_CLOCK_SECONDS },
        ports = ConnectionManagerPorts(
            channelRetryPolicy = fastChannelRetry,
            newRadioId = { fixedRadioId },
        ),
    )

    private companion object {
        const val DEVICE = "device-1"
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 10L
        const val FIXED_CLOCK_SECONDS = 1_000.0

        // The slot the batch read leaves unanswered, so the serial reconcile has to read it.
        const val FAILING_SLOT = 1
        const val CHANNEL_NAME = "Rescue"
        const val CHANNEL_SECRET_BYTES = 16
        const val SECRET_BYTE: Byte = 0x5A

        // Long enough that the connect ceremony (Room writes and the channel service's own 500ms retry
        // pre-delay included) finishes before the first scheduled attempt fires.
        const val RETRY_DELAY_MS = 900L
        const val MAX_ATTEMPTS = 2

        // The connect pass and the channel phase's own in-phase retry, both spent before any scheduled attempt.
        const val READS_BEFORE_SCHEDULED_RETRY = 2

        // Enough backoff intervals (the schedule doubles) to cover the whole budget and one silent interval past it.
        const val QUIET_INTERVALS = 8

        // More failures than any test can consume, for a radio that never answers the slot.
        const val ALWAYS_FAILING_READS = 32
    }
}
