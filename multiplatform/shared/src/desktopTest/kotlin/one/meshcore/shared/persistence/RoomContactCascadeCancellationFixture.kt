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
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.model.Uuid
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class RoomContactCascadeCancellationFixture(
    val kind: RoomContactCascadeCancellationCase,
    val queued: Boolean,
) {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val queries = QueuedContactCascadeDispatcher(kind.frame, hold = queued)
    val rows = RoomContactDeleteAtomicityFixture { path ->
        Room.databaseBuilder<MeshDatabase>(name = path)
            .setQueryCoroutineContext(queries)
            .setDriver(BundledSQLiteDriver())
            .productionSchemaPolicy()
            .build()
    }
    val database get() = rows.database
    private val originEntered = CompletableDeferred<Job>()
    private val admittedEntered = CompletableDeferred<Job>()
    private val admittedRelease = CompletableDeferred<Unit>()
    private val admittedHold = AtomicBoolean(!queued)
    private var caller: Deferred<Unit>? = null
    private var original: Job? = null
    private var finiteWriter: Job? = null
    private val contactDao = ContactGate(database.contactDao())
    private val messageDao = MessageGate(database.messageDao())
    private val store = RoomContactStore(
        RoomContactParts(database, Uuid::random, contactDao = contactDao, messageDao = messageDao),
    )
    var mutationObserved = false
        private set

    suspend fun seed() {
        rows.seed()
        if (kind == RoomContactCascadeCancellationCase.UNREFERENCED) rows.prepareUnreferencedFirstContact()
        assertTrue(rows.childRowCount(rows.firstContact) > 0)
    }

    suspend fun start() {
        queries.arm()
        caller = scope.async(CoroutineName(QueuedContactCascadeDispatcher.ORIGIN)) {
            originEntered.complete(currentCoroutineContext().job)
            when (kind) {
                RoomContactCascadeCancellationCase.SINGLE -> store.deleteContact(rows.firstContact)
                RoomContactCascadeCancellationCase.COLLECTION -> store.deleteContacts(
                    ChatFixtures.RADIO,
                    listOf(rows.firstKey),
                    skippingPublicKeys = { emptySet() },
                )
                RoomContactCascadeCancellationCase.UNREFERENCED -> store.deleteContactIfUnreferenced(rows.firstContact)
                RoomContactCascadeCancellationCase.MESSAGES -> store.deleteMessagesForContact(rows.firstContact)
            }
            Unit
        }
        original = originEntered.await()
        assertTrue(original === caller)
        val work = queries.entered.await()
        finiteWriter = finiteRoomOperation(work, requireNotNull(original))
        assertTrue(requireNotNull(original).isActive && requireNotNull(finiteWriter).isActive)
        assertEquals(QueuedContactCascadeDispatcher.ORIGIN, work.context[CoroutineName]?.name)
        assertTrue(work.frames.any { it.substringBefore('-') == kind.frame })
        assertTrue(work.frames.any { it == "androidx.room.RoomDatabaseKt.useWriterConnection" })
        if (queued) {
            assertFalse(work.ran.get())
            assertFalse(mutationObserved)
        } else {
            val mutation = admittedEntered.await()
            assertTrue(mutation.isActive)
            assertNotSame(requireNotNull(original), mutation)
            assertTrue(work.ran.get() && mutationObserved)
        }
    }

    fun cancelOrigin() {
        requireNotNull(caller).cancel()
        assertTrue(requireNotNull(original).isCancelled)
        assertTrue(requireNotNull(finiteWriter).isActive)
    }

    suspend fun releaseAndJoin(): Throwable? {
        queries.release()
        admittedRelease.complete(Unit)
        val cause = runCatching { requireNotNull(caller).await() }.exceptionOrNull()
        requireNotNull(finiteWriter).join()
        queries.entered.await().returned.await()
        assertTrue(requireNotNull(caller).isCompleted && requireNotNull(finiteWriter).isCompleted)
        assertTrue(scope.coroutineContext.job.isActive)
        assertTrue(cause == null || cause is CancellationException)
        return cause
    }

    suspend fun assertLivePolicy(before: Pair<kotlinx.serialization.json.JsonObject, List<PendingSendEntity>>) {
        assertEquals(0, rows.childRowCount(rows.firstContact))
        if (kind == RoomContactCascadeCancellationCase.MESSAGES) {
            assertTrue(database.contactDao().getById(rows.firstContact) != null)
            assertEquals(before.first["contacts"], rows.snapshot().first["contacts"])
        } else {
            assertNull(database.contactDao().getById(rows.firstContact))
        }
        assertTrue(database.contactDao().getById(rows.secondContact) != null)
        assertTrue(rows.childRowCount(rows.secondContact) > 0)
    }

    private fun finiteRoomOperation(work: QueuedContactCascadeDispatcher.Work, origin: Job): Job {
        val roomScope = database.javaClass.getMethod("getCoroutineScope").invoke(database) as CoroutineScope
        assertNotSame(roomScope.coroutineContext.job, work.context.job)
        assertNotSame(scope.coroutineContext.job, work.context.job)
        assertNotSame(origin, work.context.job)
        return work.context.job
    }

    private suspend fun afterMutation(id: Uuid) {
        if (id != rows.firstContact || !admittedHold.compareAndSet(true, false)) return
        assertEquals(0, rows.childRowCount(rows.firstContact))
        if (kind == RoomContactCascadeCancellationCase.MESSAGES) {
            assertTrue(database.contactDao().getById(id) != null)
        } else {
            assertNull(database.contactDao().getById(id))
        }
        mutationObserved = true
        admittedEntered.complete(currentCoroutineContext().job)
        admittedRelease.await()
    }

    fun close() {
        queries.release()
        admittedRelease.complete(Unit)
        try {
            runBlocking(dispatcher) {
                withTimeout(TIMEOUT_MS) {
                    caller?.cancelAndJoin()
                    if (queries.entered.isCompleted) {
                        val work = queries.entered.await()
                        val origin = originEntered.await()
                        (finiteWriter ?: finiteRoomOperation(work, origin)).join()
                        work.returned.await()
                    }
                }
            }
        } finally {
            try {
                runBlocking(dispatcher) { withTimeout(TIMEOUT_MS) { scope.coroutineContext.job.cancelAndJoin() } }
            } finally {
                try {
                    rows.close()
                } finally {
                    dispatcher.close()
                }
            }
        }
    }

    private inner class ContactGate(private val delegate: ContactDao) : ContactDao by delegate {
        override suspend fun deleteById(id: Uuid) {
            delegate.deleteById(id)
            afterMutation(id)
        }
    }

    private inner class MessageGate(private val delegate: MessageDao) : MessageDao by delegate {
        override suspend fun deleteDirectByContact(contactID: Uuid) {
            delegate.deleteDirectByContact(contactID)
            if (kind == RoomContactCascadeCancellationCase.MESSAGES) afterMutation(contactID)
        }
    }

    companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
