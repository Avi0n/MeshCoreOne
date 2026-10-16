package one.meshcore.shared.persistence

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.channel.ChannelService
import one.meshcore.shared.channel.ChannelStore
import one.meshcore.shared.channel.FakeChannelSession
import one.meshcore.shared.channel.RecordingChannelListSink
import one.meshcore.shared.channel.RecordingSlotOccupantChanged
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.model.Uuid
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

internal class RoomChannelAcknowledgedFixture(val kind: RoomChannelAcknowledgedCase, private val queued: Boolean) {
    private val executor = Executors.newSingleThreadExecutor()
    val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val queries = QueuedChannelWriterDispatcher(kind.firstWriter, hold = queued)
    val rows = RoomChannelWriterRows { path ->
        Room.databaseBuilder<MeshDatabase>(name = path).setQueryCoroutineContext(queries)
            .setDriver(BundledSQLiteDriver()).productionSchemaPolicy().build()
    }
    private val database get() = rows.database
    private val session = FakeChannelSession()
    private val cache = RecordingChannelListSink()
    private val occupants = RecordingSlotOccupantChanged()
    private val originEntered = CompletableDeferred<Job>()
    private val admittedEntered = CompletableDeferred<Job>()
    private val admittedRelease = CompletableDeferred<Unit>()
    private val admittedHold = AtomicBoolean(!queued)
    private val storeOrigins = mutableListOf<Job>()
    private var caller: Deferred<Unit>? = null
    var storeError: Throwable? = null
    private val store = RoomChannelStore(
        RoomChannelParts(
            database,
            Uuid::random,
            ChannelGate(database.channelDao()),
            MessageGate(database.messageDao()),
        ),
    )
    private val service = ChannelService(session, OriginStore(store), cache, occupants)
    var mutationObserved = false
        private set

    suspend fun seed() {
        rows.seed()
        if (kind == RoomChannelAcknowledgedCase.CLEAR_ORPHAN) rows.orphanTarget()
    }

    private fun launchCall() {
        caller = scope.async(CoroutineName(QueuedChannelWriterDispatcher.ORIGIN)) {
            originEntered.complete(currentCoroutineContext().job)
            if (kind == RoomChannelAcknowledgedCase.SET) {
                service.setChannelWithSecret(
                    rows.radio,
                    rows.replacement.index,
                    rows.replacement.name,
                    rows.replacement.secret,
                )
            } else {
                service.clearChannel(rows.radio, RoomChannelWriterRows.TARGET)
            }
        }
    }

    suspend fun start() {
        queries.arm()
        launchCall()
        val original = originEntered.await()
        assertTrue(original === caller && original.isActive)
        val work = queries.entered.await()
        assertTrue(finite(work, original).isActive)
        assertAck()
        assertTrue(storeOrigins.isNotEmpty() && storeOrigins.first().isActive)
        assertNotSame(storeOrigins.first(), work.context.job)
        if (queued) {
            assertFalse(work.ran.get() || mutationObserved)
        } else {
            val admitted = admittedEntered.await()
            assertNotSame(original, admitted)
            assertTrue(admitted.isActive && work.ran.get() && mutationObserved)
        }
    }

    suspend fun cancelOrigin() {
        requireNotNull(caller).cancel(CancellationException("Synthetic acknowledged editor cancellation"))
        assertTrue(originEntered.await().isCancelled)
        assertTrue(finite(queries.entered.await(), originEntered.await()).isActive)
    }

    suspend fun releaseAndJoin(): Throwable? {
        queries.release()
        admittedRelease.complete(Unit)
        requireNotNull(caller).join()
        val cause = runCatching { requireNotNull(caller).await() }.exceptionOrNull()
        settleWrites()
        assertTrue(scope.coroutineContext.job.isActive && requireNotNull(caller).isCompleted)
        assertAck()
        return cause
    }

    suspend fun rejectCommand(error: Throwable, beforeAck: Boolean): Throwable? {
        if (beforeAck) session.setChannelError = { error } else storeError = error
        launchCall()
        requireNotNull(caller).join()
        val cause = runCatching { requireNotNull(caller).await() }.exceptionOrNull()
        assertTrue(scope.coroutineContext.job.isActive && requireNotNull(caller).isCompleted)
        assertTrue(queries.works().isEmpty())
        if (beforeAck) {
            assertTrue(session.setChannelCalls.isEmpty() && storeOrigins.isEmpty())
        } else {
            assertAck()
            assertEquals(1, storeOrigins.size)
        }
        assertEffects(cancelled = true)
        return cause
    }

    fun assertEffects(cancelled: Boolean) {
        if (cancelled) {
            assertTrue(cache.updates.isEmpty() && occupants.received.isEmpty())
        } else {
            assertEquals(listOf(rows.radio to setOf(RoomChannelWriterRows.TARGET)), occupants.received)
            assertEquals(1, cache.updates.size)
            assertEquals(
                rows.expectedPublishedChannels(kind.expected),
                rows.normalizePublishedChannels(cache.updates.single()),
            )
        }
    }

    private fun assertAck() {
        val ack = session.setChannelCalls.single()
        assertEquals(RoomChannelWriterRows.TARGET, ack.index)
        val expected = if (kind == RoomChannelAcknowledgedCase.SET) {
            rows.replacement
        } else {
            ChannelInfo(
                RoomChannelWriterRows.TARGET,
                "",
                ByteArray(KEY_SIZE),
            )
        }
        assertEquals(expected.name, ack.name)
        assertTrue(expected.secret.contentEquals(ack.secret))
        assertTrue(session.getChannelIndices.isEmpty() && session.getChannelsCalls.isEmpty())
    }

    private suspend fun settleWrites() {
        val original = originEntered.await()
        val work = queries.works()
        for (observed in work) {
            val room = finite(observed, original)
            room.join()
            observed.returned.await()
            assertTrue(room.isCompleted && observed.ran.get())
            assertEquals(1, observed.forwards.get())
        }
        assertTrue(work.isNotEmpty())
        assertEquals(work.size, queries.works().size)
    }

    private fun finite(work: QueuedChannelWriterDispatcher.Work, original: Job): Job {
        val roomScope = database.javaClass.getMethod("getCoroutineScope").invoke(database) as CoroutineScope
        val job = work.context.job
        assertNotSame(original, job)
        assertNotSame(scope.coroutineContext.job, job)
        assertNotSame(roomScope.coroutineContext.job, job)
        assertEquals(QueuedChannelWriterDispatcher.ORIGIN, work.context[CoroutineName]?.name)
        assertTrue("androidx.room.RoomDatabaseKt.useWriterConnection" in work.frames)
        return job
    }

    private suspend fun afterMutation() {
        if (!admittedHold.compareAndSet(true, false)) return
        assertAck()
        assertEquals(0, rows.children(rows.radio, RoomChannelWriterRows.TARGET))
        val row = database.channelDao().getById(rows.targetID)
        if (kind == RoomChannelAcknowledgedCase.SET) {
            val actual = requireNotNull(row)
            assertEquals(rows.replacement.name, actual.name)
            assertTrue(rows.replacement.secret.contentEquals(actual.secret))
        } else {
            assertEquals(kind == RoomChannelAcknowledgedCase.CLEAR_PRESENT, row != null)
        }
        mutationObserved = true
        admittedEntered.complete(currentCoroutineContext().job)
        admittedRelease.await()
    }

    private inner class OriginStore(private val delegate: ChannelStore) : ChannelStore by delegate {
        private suspend fun enter() {
            storeOrigins += currentCoroutineContext().job
            storeError?.let { throw it }
        }
        override suspend fun saveChannel(radioID: Uuid, info: ChannelInfo): Uuid {
            enter()
            return delegate.saveChannel(radioID, info)
        }
        override suspend fun deleteMessagesForChannel(radioID: Uuid, channelIndex: Int) {
            enter()
            delegate.deleteMessagesForChannel(radioID, channelIndex)
        }
        override suspend fun deleteChannel(id: Uuid) {
            enter()
            delegate.deleteChannel(id)
        }
    }

    private inner class ChannelGate(private val delegate: ChannelDao) : ChannelDao by delegate {
        override suspend fun upsertPreservingPin(entity: ChannelEntity) {
            delegate.upsertPreservingPin(entity)
            if (kind == RoomChannelAcknowledgedCase.SET && entity.id == rows.targetID) afterMutation()
        }
    }

    private inner class MessageGate(private val delegate: MessageDao) : MessageDao by delegate {
        override suspend fun deleteMessagesForChannel(radioID: Uuid, channelIndex: Int) {
            delegate.deleteMessagesForChannel(radioID, channelIndex)
            if (kind != RoomChannelAcknowledgedCase.SET &&
                radioID == rows.radio &&
                channelIndex == RoomChannelWriterRows.TARGET
            ) {
                afterMutation()
            }
        }
    }

    fun close() {
        queries.release()
        admittedRelease.complete(Unit)
        try {
            runBlocking(dispatcher) {
                withTimeout(TIMEOUT_MS) {
                    caller?.cancelAndJoin()
                    if (originEntered.isCompleted && queries.entered.isCompleted) settleWrites()
                }
            }
        } finally {
            try {
                runBlocking(dispatcher) { withTimeout(TIMEOUT_MS) { scope.coroutineContext.job.cancelAndJoin() } }
            } finally {
                dispatcher.close()
                try {
                    check(executor.awaitTermination(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                } finally {
                    rows.close()
                }
            }
        }
    }

    companion object {
        const val TIMEOUT_MS = 10_000L
        private const val KEY_SIZE = 16
    }
}
