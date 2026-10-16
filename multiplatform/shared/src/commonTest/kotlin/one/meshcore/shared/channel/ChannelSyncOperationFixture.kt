package one.meshcore.shared.channel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.model.Uuid
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class ChannelSyncOperationFixture(private val kind: ChannelSyncOperationCase) {
    val store = InMemoryChannelStore()
    val cache = RecordingChannelListSink()
    val occupants = RecordingSlotOccupantChanged()
    val readEntered = CompletableDeferred<Job>()
    val handlerEntered = CompletableDeferred<Job>()
    private val handlerRelease = CompletableDeferred<Unit>()
    private val readRelease = CompletableDeferred<Unit>()
    private var holdRead = false
    private val delegate = FakeChannelSession()
    private val wire = channelInfo(TARGET, "Live frame", NEW_KEY)
    private val prior = channelDto(
        ChannelTestIds.fresh(), ChannelTestIds.RADIO, TARGET, name = "Cached frame", secretByte = OLD_KEY,
        unreadCount = HISTORY, unreadMentionCount = MENTIONS, isPinned = true, lastMessageDate = NOW,
    ).copy(isEnabled = false)
    private val committed = prior.copy(
        name = wire.name,
        secret = wire.secret,
        unreadCount = 0,
        unreadMentionCount = 0,
        lastMessageDate = null,
    )
    var retireAfterStore = false
    var retireInHandler = false
    var persisted = 0
        private set
    var forwarded = 0
        private set
    lateinit var service: ChannelService
        private set

    init {
        delegate.slots[TARGET] = wire
        delegate.markUnconfigured(PUBLIC_SLOT)
        delegate.batchResult = ChannelBatchRead(listOf(emptySlot(PUBLIC_SLOT), wire), emptyList())
        service = ChannelService(
            ReadSession(),
            PersistedStore(),
            cache,
            ChannelSlotOccupantChanged { radio, slots ->
                occupants.onOccupantChanged(radio, slots)
                if (retireInHandler) {
                    handlerEntered.complete(currentCoroutineContext().job)
                    handlerRelease.await()
                    service.retireChannelSyncOperations()
                }
            },
        )
    }

    suspend fun seed() {
        store.seedChannel(prior)
        store.seedMessages(TARGET, HISTORY)
        assertBefore()
    }

    fun armRead() {
        holdRead = true
    }
    fun releaseRead() {
        readRelease.complete(Unit)
    }

    fun releaseHandler() {
        handlerRelease.complete(Unit)
    }

    suspend fun run(): ChannelSyncResult = when (kind) {
        ChannelSyncOperationCase.PIPELINED -> service.syncChannels(ChannelTestIds.RADIO, MAX_CHANNELS, true)
        ChannelSyncOperationCase.SERIAL -> service.syncChannels(ChannelTestIds.RADIO, MAX_CHANNELS, false)
        ChannelSyncOperationCase.RETRY -> service.retryFailedChannels(ChannelTestIds.RADIO, listOf(TARGET))
    }

    fun assertNoCommands() {
        assertTrue(delegate.getChannelIndices.isEmpty() && delegate.getChannelsCalls.isEmpty())
        assertTrue(delegate.setChannelCalls.isEmpty())
    }

    suspend fun assertBefore() {
        assertEquals(0, persisted)
        assertTrue(cache.updates.isEmpty() && occupants.received.isEmpty())
        assertEquals(HISTORY, store.remainingMessageCount(TARGET))
        assertEquals(
            normalized(listOf(prior)),
            normalized(store.fetchChannels(ChannelTestIds.RADIO)),
            "LAST: retired read persisted",
        )
    }

    suspend fun assertCommitted(handlerCalls: Int, cacheCalls: Int) {
        assertEquals(1, persisted)
        assertEquals(
            List(handlerCalls) { ChannelTestIds.RADIO to setOf(TARGET) },
            occupants.received,
        )
        assertEquals(cacheCalls, cache.updates.size)
        if (cacheCalls > 0) assertEquals(normalized(listOf(committed)), normalized(cache.updates.single()))
        assertEquals(0, store.remainingMessageCount(TARGET))
        assertEquals(
            normalized(listOf(committed)),
            normalized(store.fetchChannels(ChannelTestIds.RADIO)),
            "LAST: committed read policy changed",
        )
    }

    private fun normalized(rows: List<ChannelDto>) = rows.map { it.copy(secret = EMPTY) to it.secret.toList() }

    private suspend fun holdCompletedRead() {
        if (!holdRead) return
        holdRead = false
        readEntered.complete(currentCoroutineContext().job)
        readRelease.await()
        forwarded++
    }

    private inner class ReadSession : ChannelSession by delegate {
        override suspend fun getChannel(index: Int): ChannelInfo {
            val result = delegate.getChannel(index).let { it.copy(secret = it.secret.copyOf()) }
            holdCompletedRead()
            return result
        }
        override suspend fun getChannels(indices: List<Int>): ChannelBatchRead {
            val result = delegate.getChannels(indices)
            val copied = result.copy(received = result.received.map { it.copy(secret = it.secret.copyOf()) })
            holdCompletedRead()
            return copied
        }
    }

    private inner class PersistedStore : ChannelStore by store {
        override suspend fun batchSaveChannels(
            radioID: Uuid,
            configured: List<ChannelInfo>,
            unconfiguredIndices: List<Int>,
            pruneBeyond: Int?,
        ): List<ChannelDto> {
            val result = store.batchSaveChannels(radioID, configured, unconfiguredIndices, pruneBeyond)
            persisted++
            if (retireAfterStore) service.retireChannelSyncOperations()
            return result
        }
    }

    companion object {
        private const val TARGET = 1
        private const val PUBLIC_SLOT = 0
        private const val MAX_CHANNELS = 2
        private const val OLD_KEY = 0x31
        private const val NEW_KEY = 0x62
        private const val HISTORY = 4
        private const val MENTIONS = 2
        private const val NOW = 1_700_000_000.0
        private val EMPTY = ByteArray(0)
    }
}
