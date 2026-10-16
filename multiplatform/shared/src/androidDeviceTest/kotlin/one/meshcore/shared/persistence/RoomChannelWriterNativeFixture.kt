package one.meshcore.shared.persistence

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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class RoomChannelWriterNativeFixture(val kind: RoomChannelWriterNativeCase, val queued: Boolean) {
    private val executor = Executors.newSingleThreadExecutor()
    val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val queries = QueuedChannelWriterNativeDispatcher(kind.method, hold = queued)
    val rows = RoomChannelWriterNativeRows(queries)
    private val database get() = rows.database
    private val originEntered = CompletableDeferred<Job>()
    private val admittedEntered = CompletableDeferred<Job>()
    private val admittedRelease = CompletableDeferred<Unit>()
    private val admittedHold = AtomicBoolean(!queued)
    private var workersSettled = false
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
        caller = scope.async(CoroutineName(QueuedChannelWriterNativeDispatcher.ORIGIN)) {
            originEntered.complete(currentCoroutineContext().job)
            when (kind) {
                RoomChannelWriterNativeCase.SAVE -> store.saveChannel(rows.radio, rows.replacement)
                RoomChannelWriterNativeCase.BATCH -> store.batchSaveChannels(
                    rows.radio,
                    listOf(rows.replacement),
                    listOf(RoomChannelWriterNativeRows.VACATED),
                    RoomChannelWriterNativeRows.CAP,
                    beforeCommit = { afterMutation() },
                )
                RoomChannelWriterNativeCase.DELETE -> store.deleteChannel(rows.targetID)
                RoomChannelWriterNativeCase.MESSAGES -> store.deleteMessagesForChannel(
                    rows.radio,
                    RoomChannelWriterNativeRows.TARGET,
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
        workersSettled = true
    }

    private fun finite(work: QueuedChannelWriterNativeDispatcher.Work, original: Job): Job {
        val roomScope = database.javaClass.getMethod("getCoroutineScope").invoke(database) as CoroutineScope
        val job = work.context.job
        assertNotSame(original, job)
        assertNotSame(scope.coroutineContext.job, job)
        assertNotSame(roomScope.coroutineContext.job, job)
        assertEquals(QueuedChannelWriterNativeDispatcher.ORIGIN, work.context[CoroutineName]?.name)
        assertTrue("androidx.room.RoomDatabaseKt.useWriterConnection" in work.frames)
        return job
    }

    private suspend fun afterMutation() {
        if (!admittedHold.compareAndSet(true, false)) return
        assertEquals(0, rows.children(rows.radio, RoomChannelWriterNativeRows.TARGET))
        when (kind) {
            RoomChannelWriterNativeCase.SAVE, RoomChannelWriterNativeCase.BATCH -> {
                val actual = requireNotNull(database.channelDao().getById(rows.targetID))
                assertEquals(rows.replacement.name, actual.name)
                assertTrue(rows.replacement.secret.contentEquals(actual.secret))
            }
            RoomChannelWriterNativeCase.DELETE -> assertEquals(null, database.channelDao().getById(rows.targetID))
            RoomChannelWriterNativeCase.MESSAGES -> assertTrue(database.channelDao().getById(rows.targetID) != null)
        }
        if (kind == RoomChannelWriterNativeCase.BATCH) {
            assertEquals(0, rows.children(rows.radio, RoomChannelWriterNativeRows.VACATED))
            assertEquals(0, rows.children(rows.radio, RoomChannelWriterNativeRows.PRUNED))
            assertEquals(
                null,
                database.channelDao().getByRadioAndIndex(rows.radio, RoomChannelWriterNativeRows.VACATED),
            )
            assertEquals(null, database.channelDao().getByRadioAndIndex(rows.radio, RoomChannelWriterNativeRows.PRUNED))
        }
        mutationObserved = true
        admittedEntered.complete(currentCoroutineContext().job)
        admittedRelease.await()
    }

    private inner class ChannelGate(private val delegate: ChannelDao) : ChannelDao by delegate {
        override suspend fun upsertPreservingPin(entity: ChannelEntity) {
            delegate.upsertPreservingPin(entity)
            if (kind == RoomChannelWriterNativeCase.SAVE && entity.id == rows.targetID) afterMutation()
        }
        override suspend fun deleteById(id: Uuid) {
            delegate.deleteById(id)
            if (kind == RoomChannelWriterNativeCase.DELETE && id == rows.targetID) afterMutation()
        }
    }

    private inner class MessageGate(private val delegate: MessageDao) : MessageDao by delegate {
        override suspend fun deleteMessagesForChannel(radioID: Uuid, channelIndex: Int) {
            delegate.deleteMessagesForChannel(radioID, channelIndex)
            if (kind == RoomChannelWriterNativeCase.MESSAGES &&
                radioID == rows.radio &&
                channelIndex == RoomChannelWriterNativeRows.TARGET
            ) {
                afterMutation()
            }
        }
    }

    fun record(name: String, snapshotEqual: Boolean, outcomeMatched: Boolean) {
        rows.record(
            name,
            JSONObject().put("expectedSnapshotEqual", snapshotEqual).put("expectedOutcome", outcomeMatched)
                .put("writerKind", kind.name).put("queued", queued).put("mutationObserved", mutationObserved)
                .put("qualifiedFiniteWorkCount", queries.works().size).put("workersSettled", workersSettled)
                .put(
                    "allWorkersCompleted",
                    queries.works().all {
                        it.context.job.isCompleted && it.returned.isCompleted && it.forwards.get() == 1 && it.ran.get()
                    },
                ).put("originCompleted", requireNotNull(caller).isCompleted)
                .put("fixtureScopeActive", scope.coroutineContext.job.isActive)
                .put("radioOrSessionConstructed", false),
        )
    }

    fun close(name: String) {
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
                    rows.close(
                        name,
                        workersSettled,
                        scope.coroutineContext.job.isCompleted,
                        executor.isTerminated,
                    )
                }
            }
        }
    }

    companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
