package one.meshcore.shared.channel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.NotificationLevel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mirrors the iOS `ChannelServiceTests` sync cases plus the slot-keyed local-field preservation the task
 * calls out: a slot-keyed upsert rewrites only name+secret, unconfigured slots are pruned, and a cascade of
 * transport send timeouts trips the circuit breaker. Serial path over a [FakeChannelSession].
 */
class ChannelServiceSyncTest {
    private val radioID = ChannelTestIds.RADIO

    @Test
    fun syncUpsertPreservesLocalOnlyFields() = runTest {
        val store = InMemoryChannelStore()
        val seeded = channelDto(
            id = ChannelTestIds.fresh(),
            radioID = radioID,
            index = 1,
            name = "OldName",
            secretByte = 0x11,
            unreadCount = 9,
            unreadMentionCount = 4,
            notificationLevel = NotificationLevel.MUTED,
            isPinned = true,
            floodScope = ChannelFloodScope.Region("Germany"),
            lastMessageDate = 500.0,
        )
        store.seedChannel(seeded)

        val session = FakeChannelSession()
        session.slots[1] = channelInfo(1, name = "NewName", secretByte = 0x11)
        val service = testChannelService(session = session, store = store)

        val result = service.syncChannels(radioID, maxChannels = 2, usePipelinedRead = false)

        assertEquals(1, result.channelsSynced)
        val row = service.getChannel(radioID, 1)!!
        // Wire frame overwrites name + secret only.
        assertEquals("NewName", row.name)
        assertContentEquals(ByteArray(ChannelSecret.SIZE_BYTES) { 0x11 }, row.secret)
        // Every local-only field survives the sync upsert.
        assertEquals(seeded.id, row.id)
        assertEquals(9, row.unreadCount)
        assertEquals(4, row.unreadMentionCount)
        assertEquals(NotificationLevel.MUTED, row.notificationLevel)
        assertTrue(row.isPinned)
        assertEquals(ChannelFloodScope.Region("Germany"), row.floodScope)
        assertEquals(500.0, row.lastMessageDate)
    }

    @Test
    fun syncPrunesUnconfiguredSlotsAndClearsTheirDrafts() = runTest {
        val store = InMemoryChannelStore()
        // A stale local row the device now reports empty.
        store.seedChannel(channelDto(ChannelTestIds.fresh(), radioID, index = 1))

        val session = FakeChannelSession()
        session.slots[0] = channelInfo(0, name = "Public")
        session.slots[1] = emptySlot(1) // reported unconfigured → prune
        val drafts = RecordingSlotOccupantChanged()
        val service = testChannelService(session = session, store = store, slotOccupantChanged = drafts)

        val result = service.syncChannels(radioID, maxChannels = 2, usePipelinedRead = false)

        assertEquals(1, result.channelsSynced)
        assertEquals(listOf(0), service.getChannels(radioID).map { it.index })
        assertNull(service.getChannel(radioID, 1))
        // Slot 1 was occupied before and vacated by the prune → its drafts are cleared.
        assertEquals(listOf(radioID to setOf(1)), drafts.received)
    }

    @Test
    fun syncFiresTheSlotHandlerForVacatedAndSecretChangedSlotsNotFirstSightings() = runTest {
        val store = InMemoryChannelStore()
        store.seedChannel(channelDto(ChannelTestIds.fresh(), radioID, index = 1, secretByte = 0x11))
        store.seedChannel(channelDto(ChannelTestIds.fresh(), radioID, index = 2, secretByte = 0x22))
        val session = FakeChannelSession()
        session.slots[1] = channelInfo(1, name = "OneB", secretByte = 0x99)
        session.slots[3] = channelInfo(3, name = "First", secretByte = 0x33)
        val handler = RecordingSlotOccupantChanged()
        val service = testChannelService(session = session, store = store, slotOccupantChanged = handler)

        service.syncChannels(radioID, maxChannels = 4, usePipelinedRead = false)

        assertEquals(1, handler.received.size)
        assertEquals(radioID, handler.received.single().first)
        assertEquals(setOf(1, 2), handler.received.single().second)
    }

    @Test
    fun syncAbortsEarlyWhenSendTimeoutsCascade() = runTest {
        val session = FakeChannelSession()
        for (index in 0 until 6) session.slotErrors[index] = { ChannelSendTimeoutException() }
        val service = testChannelService(session = session)

        val result = service.syncChannels(radioID, maxChannels = 6, usePipelinedRead = false)

        assertEquals(3, result.sendTimeoutCount)
        assertTrue(result.circuitBreakerAborted)
        // The breaker opens after three send timeouts; the remaining slots are never read.
        assertEquals(3, session.getChannelIndices.size)
    }

    @Test
    fun syncKeepsSlotsSkippedByTheBreakerUntouched() = runTest {
        val store = InMemoryChannelStore()
        // Pre-seed a configured row at index 5 that the breaker will skip; it must not be deleted.
        store.seedChannel(channelDto(ChannelTestIds.fresh(), radioID, index = 5, name = "keep"))

        val session = FakeChannelSession()
        for (index in 0 until 6) session.slotErrors[index] = { ChannelTimeoutException() }
        val service = testChannelService(session = session, store = store)

        val result = service.syncChannels(radioID, maxChannels = 6, usePipelinedRead = false)

        assertTrue(result.circuitBreakerAborted)
        assertEquals("keep", service.getChannel(radioID, 5)?.name)
    }

    @Test
    fun secondConcurrentSyncIsRejectedThenAllowedAfterCompletion() = runTest {
        val gate = CompletableDeferred<Unit>()
        val reachedRead = CompletableDeferred<Unit>()
        val session = GatedChannelSession(gate, reachedRead)
        val service = testChannelService(session = session)

        val first = launch { service.syncChannels(radioID, maxChannels = 1, usePipelinedRead = false) }
        reachedRead.await() // the first sync is now in-flight, holding the guard

        assertFailsWith<ChannelServiceError.SyncAlreadyInProgress> {
            service.syncChannels(radioID, maxChannels = 1, usePipelinedRead = false)
        }

        gate.complete(Unit)
        first.join()
        // The flag is released once the first sync finishes, so a later sync succeeds.
        val again = service.syncChannels(radioID, maxChannels = 1, usePipelinedRead = false)
        assertEquals(0, again.channelsSynced)
    }

    @Test
    fun retryFailedChannelsUpsertsRecoveredOnlyAndNeverPrunes() = runTest {
        val store = InMemoryChannelStore()
        // A configured row at index 3 not among the retried indices must survive (retry never prunes).
        store.seedChannel(channelDto(ChannelTestIds.fresh(), radioID, index = 3, name = "survivor"))

        val session = FakeChannelSession()
        session.slots[1] = channelInfo(1, name = "recovered")
        session.markUnconfigured(2)
        val service = testChannelService(session = session, store = store)

        val result = service.retryFailedChannels(radioID, indices = listOf(1, 2))

        assertEquals(1, result.channelsSynced)
        assertEquals("recovered", service.getChannel(radioID, 1)?.name)
        assertEquals("survivor", service.getChannel(radioID, 3)?.name)
    }

    @Test
    fun retryWithNoIndicesIsANoOp() = runTest {
        val service = testChannelService()
        val result = service.retryFailedChannels(radioID, indices = emptyList())
        assertEquals(0, result.channelsSynced)
        assertTrue(result.isComplete)
    }

    @Test
    fun databasePersistFailureIsReportedAsDatabaseError() = runTest {
        val session = FakeChannelSession()
        session.slots[0] = channelInfo(0)
        val service = testChannelService(session = session, store = ThrowingBatchStore())

        val result = service.syncChannels(radioID, maxChannels = 1, usePipelinedRead = false)

        assertEquals(0, result.channelsSynced)
        assertTrue(result.errors.any { it.errorType is ChannelSyncErrorType.DatabaseError })
    }
}

/** A session whose first [getChannel] blocks on [gate], so a second sync can race the in-flight one. */
private class GatedChannelSession(
    private val gate: CompletableDeferred<Unit>,
    private val reachedRead: CompletableDeferred<Unit>,
) : ChannelSession {
    override suspend fun getChannel(index: Int): ChannelInfo {
        reachedRead.complete(Unit)
        gate.await()
        throw ChannelDeviceException(ChannelProtocolErrorCode.NOT_FOUND)
    }

    override suspend fun getChannels(indices: List<Int>): ChannelBatchRead =
        ChannelBatchRead(received = emptyList(), missing = indices)

    override suspend fun setChannel(index: Int, name: String, secret: ByteArray) = Unit
}

/** A store whose batch save always throws, to exercise the finalize-time database-error path. */
private class ThrowingBatchStore(private val delegate: InMemoryChannelStore = InMemoryChannelStore()) :
    ChannelStore by delegate {
    override suspend fun batchSaveChannels(
        radioID: one.meshcore.shared.model.Uuid,
        configured: List<one.meshcore.shared.meshcore.ChannelInfo>,
        unconfiguredIndices: List<Int>,
        pruneBeyond: Int?,
    ): List<one.meshcore.shared.backup.ChannelDto> = error("batch failed")
}
