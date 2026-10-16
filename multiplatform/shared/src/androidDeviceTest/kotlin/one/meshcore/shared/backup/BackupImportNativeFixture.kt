package one.meshcore.shared.backup

import androidx.test.platform.app.InstrumentationRegistry
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
import one.meshcore.shared.persistence.meshDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class BackupImportNativeFixture(val queued: Boolean) {
    private val executor = Executors.newSingleThreadExecutor()
    val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val queries = BackupImportNativeDispatcher(hold = queued)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "backup-cancellation-${Uuid.random().value}.db"
    private val file = context.getDatabasePath(name)
    private val database = meshDatabase(context, queries, name)
    val rows = BackupImportNativeRows(database)
    private val originEntered = CompletableDeferred<Job>()
    private val admittedEntered = CompletableDeferred<Job>()
    private val admittedRelease = CompletableDeferred<Unit>()
    private val admittedHold = AtomicBoolean(!queued)
    private var caller: Deferred<BackupImportResult>? = null
    var mutationObserved = false
        private set

    suspend fun start() {
        queries.arm()
        caller = scope.async(CoroutineName(BackupImportNativeDispatcher.ORIGIN)) {
            originEntered.complete(currentCoroutineContext().job)
            if (queued) {
                RoomBackupImporter.importEnvelope(database, rows.incoming)
            } else {
                RoomBackupImporter.importEnvelope(database, rows.incoming, beforeCommit = { afterMutation() })
            }
        }
        val original = originEntered.await()
        assertTrue(original === caller && original.isActive)
        val work = queries.entered.await()
        val room = finite(work, original)
        assertTrue(room.isActive)
        assertTrue(work.frames.any { it.startsWith("one.meshcore.shared.backup.RoomBackupImporter.importEnvelope") })
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

    private fun finite(work: BackupImportNativeDispatcher.Work, original: Job): Job {
        val roomScope = database.javaClass.getMethod("getCoroutineScope").invoke(database) as CoroutineScope
        val job = work.context.job
        assertNotSame(original, job)
        assertNotSame(scope.coroutineContext.job, job)
        assertNotSame(roomScope.coroutineContext.job, job)
        assertEquals(BackupImportNativeDispatcher.ORIGIN, work.context[CoroutineName]?.name)
        assertTrue("androidx.room.RoomDatabaseKt.useWriterConnection" in work.frames)
        return job
    }

    private suspend fun afterMutation() {
        if (!admittedHold.compareAndSet(true, false)) return
        rows.assertCounts(rows.snapshot(), copies = 2)
        mutationObserved = true
        admittedEntered.complete(currentCoroutineContext().job)
        admittedRelease.await()
    }

    suspend fun retry() {
        assertEquals(
            BackupImportNativeRows.INSERTED,
            RoomBackupImporter.importEnvelope(database, rows.incoming).totalInserted,
        )
    }

    fun record(caseName: String, outcome: Boolean) {
        val directory = File(context.getExternalFilesDir(null), "backup-cancellation")
        check(directory.mkdirs() || directory.isDirectory)
        File(directory, "$caseName.json").writeText(
            JSONObject().put("outcome", outcome).put("mutationObserved", mutationObserved)
                .put("originCancelled", caller?.isCancelled).put("workCount", queries.works().size)
                .put("workersSettled", queries.works().all { it.context.job.isCompleted && it.returned.isCompleted })
                .put("forwardedExactlyOnce", queries.works().all { it.ran.get() && it.forwards.get() == 1 })
                .put("radioOrSessionConstructed", false).toString(),
        )
    }

    fun close(caseName: String) {
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
                    closeDatabase(caseName)
                }
            }
        }
    }

    private fun closeDatabase(caseName: String) {
        var closed = false
        try {
            database.close()
            closed = true
        } finally {
            val owned = listOf("", "-wal", "-shm").map { File(file.absolutePath + it) }
            val removed = owned.map { runCatching { !it.exists() || it.delete() }.getOrDefault(false) }
            val directory = File(context.getExternalFilesDir(null), "backup-cancellation")
            check(directory.mkdirs() || directory.isDirectory)
            File(directory, "$caseName-teardown.json").writeText(
                JSONObject().put("databaseClosed", closed).put(
                    "workersSettled",
                    queries.works().all {
                        it.context.job.isCompleted && it.returned.isCompleted
                    },
                ).put("scopeStopped", scope.coroutineContext.job.isCompleted)
                    .put("executorStopped", executor.isTerminated)
                    .put("allOwnedFilesAbsent", owned.none { it.exists() }).toString(),
            )
            check(removed.all { it } && owned.none { it.exists() })
        }
    }

    companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
