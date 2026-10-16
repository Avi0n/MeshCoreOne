package one.meshcore.shared.backup

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
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.productionSchemaPolicy
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

internal class BackupImportCancellationFixture(val queued: Boolean) {
    private val executor = Executors.newSingleThreadExecutor()
    val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val queries = BackupImportWriterDispatcher(hold = queued)
    private val directory = Files.createTempDirectory("backup-import-cancellation-").toFile()
    private val file = directory.resolve("owned.db")
    private val database = Room.databaseBuilder<MeshDatabase>(name = file.absolutePath)
        .setQueryCoroutineContext(queries).setDriver(BundledSQLiteDriver()).productionSchemaPolicy().build()
    val rows = BackupImportCancellationRows(database)
    private val originEntered = CompletableDeferred<Job>()
    private val admittedEntered = CompletableDeferred<Job>()
    private val admittedRelease = CompletableDeferred<Unit>()
    private val admittedHold = AtomicBoolean(!queued)
    private var caller: Deferred<BackupImportResult>? = null
    var mutationObserved = false
        private set

    suspend fun start() {
        queries.arm()
        caller = scope.async(CoroutineName(BackupImportWriterDispatcher.ORIGIN)) {
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

    private fun finite(work: BackupImportWriterDispatcher.Work, original: Job): Job {
        val roomScope = database.javaClass.getMethod("getCoroutineScope").invoke(database) as CoroutineScope
        val job = work.context.job
        assertNotSame(original, job)
        assertNotSame(scope.coroutineContext.job, job)
        assertNotSame(roomScope.coroutineContext.job, job)
        assertEquals(BackupImportWriterDispatcher.ORIGIN, work.context[CoroutineName]?.name)
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
            BackupImportCancellationRows.INSERTED,
            RoomBackupImporter.importEnvelope(database, rows.incoming).totalInserted,
        )
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
                    try {
                        database.close()
                    } finally {
                        check(directory.deleteRecursively() && !directory.exists())
                    }
                }
            }
        }
    }

    companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
