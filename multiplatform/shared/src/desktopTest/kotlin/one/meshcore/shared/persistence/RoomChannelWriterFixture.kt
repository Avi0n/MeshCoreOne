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
import one.meshcore.shared.model.Uuid
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

internal class RoomChannelWriterFixture(val kind: RoomChannelWriterCase, val queued: Boolean) {
    private val executor = Executors.newSingleThreadExecutor()
    val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val queries = QueuedChannelWriterDispatcher(kind.method, hold = queued)
    val rows = RoomChannelWriterRows { path ->
        Room.databaseBuilder<MeshDatabase>(name = path).setQueryCoroutineContext(queries)
            .setDriver(BundledSQLiteDriver()).productionSchemaPolicy().build()
    }
    private val database get() = rows.database
    private val originEntered = CompletableDeferred<Job>()
    private val admittedEntered = CompletableDeferred<Job>()
    private val admittedRelease = CompletableDeferred<Unit>()
    private val admittedHold = AtomicBoolean(!queued)
    private var caller: Deferred<Unit>? = null
    private val store = RoomChannelStore(
        RoomChannelParts(
            database,
            Uuid::random,
            ChannelGate(database.channelDao()),
            MessageGate(database.messageDao()),
        ),
    )
    var mutationObserved = false
        private set

    suspend fun start() {
        queries.arm()
        caller = scope.async(CoroutineName(QueuedChannelWriterDispatcher.ORIGIN)) {
            originEntered.complete(currentCoroutineContext().job)
            when (kind) {
                RoomChannelWriterCase.SAVE -> store.saveChannel(rows.radio, rows.replacement)
                RoomChannelWriterCase.BATCH -> store.batchSaveChannels(
                    rows.radio,
                    listOf(rows.replacement),
                    listOf(RoomChannelWriterRows.VACATED),
                    RoomChannelWriterRows.CAP,
                    beforeCommit = { afterMutation() },
                )
                RoomChannelWriterCase.DELETE -> store.deleteChannel(rows.targetID)
                RoomChannelWriterCase.MESSAGES -> store.deleteMessagesForChannel(
                    rows.radio,
                    RoomChannelWriterRows.TARGET,
                )
            }
            Unit
        }
        val original = originEntered.await()
        assertTrue(original === caller && original.isActive)
        val work = queries.entered.await()
        val room = finite(work, original)
        assertTrue(room.isActive)
        assertTrue(work.frames.any { it.substringBefore('-').endsWith(".${kind.method}") })
        if (queued) {
            assertFalse(work.ran.get() || mutationObserved)
        } else {
            val admitted = admittedEntered.await()
            assertNotSame(original, admitted)
            assertTrue(admitted.isActive && work.ran.get() && mutationObserved)
        }
    }

    suspend fun cancelOrigin() {
        requireNotNull(caller).cancel(CancellationException("Synthetic original writer cancellation"))
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
        assertTrue(cause == null || cause is CancellationException)
        return cause
    }

    private suspend fun settleWrites() {
        val original = originEntered.await()
        val work = queries.works()
        assertTrue(work.isNotEmpty())
        for (observed in work) {
            val room = finite(observed, original)
            room.join()
            observed.returned.await()
            assertTrue(room.isCompleted && observed.ran.get())
            assertEquals(1, observed.forwards.get())
        }
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
        assertEquals(0, rows.children(rows.radio, RoomChannelWriterRows.TARGET))
        when (kind) {
            RoomChannelWriterCase.SAVE, RoomChannelWriterCase.BATCH -> {
                val actual = requireNotNull(database.channelDao().getById(rows.targetID))
                assertEquals(rows.replacement.name, actual.name)
                assertTrue(rows.replacement.secret.contentEquals(actual.secret))
            }
            RoomChannelWriterCase.DELETE -> assertEquals(null, database.channelDao().getById(rows.targetID))
            RoomChannelWriterCase.MESSAGES -> assertTrue(database.channelDao().getById(rows.targetID) != null)
        }
        if (kind == RoomChannelWriterCase.BATCH) {
            assertEquals(0, rows.children(rows.radio, RoomChannelWriterRows.VACATED))
            assertEquals(0, rows.children(rows.radio, RoomChannelWriterRows.PRUNED))
            assertEquals(null, database.channelDao().getByRadioAndIndex(rows.radio, RoomChannelWriterRows.VACATED))
            assertEquals(null, database.channelDao().getByRadioAndIndex(rows.radio, RoomChannelWriterRows.PRUNED))
        }
        mutationObserved = true
        admittedEntered.complete(currentCoroutineContext().job)
        admittedRelease.await()
    }

    private inner class ChannelGate(private val delegate: ChannelDao) : ChannelDao by delegate {
        override suspend fun upsertPreservingPin(entity: ChannelEntity) {
            delegate.upsertPreservingPin(entity)
            if (kind == RoomChannelWriterCase.SAVE && entity.id == rows.targetID) afterMutation()
        }
        override suspend fun deleteById(id: Uuid) {
            delegate.deleteById(id)
            if (kind == RoomChannelWriterCase.DELETE && id == rows.targetID) afterMutation()
        }
    }

    private inner class MessageGate(private val delegate: MessageDao) : MessageDao by delegate {
        override suspend fun deleteMessagesForChannel(radioID: Uuid, channelIndex: Int) {
            delegate.deleteMessagesForChannel(radioID, channelIndex)
            if (kind == RoomChannelWriterCase.MESSAGES &&
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
                runBlocking(dispatcher) {
                    withTimeout(TIMEOUT_MS) { scope.coroutineContext.job.cancelAndJoin() }
                }
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
    }
}
