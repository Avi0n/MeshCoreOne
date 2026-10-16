package one.meshcore.shared.connection

import androidx.room.Room
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ContactServiceEdges
import one.meshcore.shared.contact.ContactSession
import one.meshcore.shared.contact.retireContactSyncOperations
import one.meshcore.shared.contact.syncContacts
import one.meshcore.shared.contact.syncContactsForRefresh
import one.meshcore.shared.meshcore.ContactFetchResult
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.ContactDao
import one.meshcore.shared.persistence.ContactEntity
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.RoomContactParts
import one.meshcore.shared.persistence.RoomContactStore
import one.meshcore.shared.persistence.productionSchemaPolicy
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class ConnectionContactBulkRetirementFixture(val refresh: Boolean) {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val file = File.createTempFile("contact-bulk-retirement", ".db").also { it.delete() }
    val database = Room.databaseBuilder<MeshDatabase>(name = file.absolutePath)
        .setDriver(BundledSQLiteDriver()).productionSchemaPolicy().build()
    val rows = ConnectionInitialContactReturnRows(database)
    val radio = Uuid.random()
    private val selfKey = ConnectionInitialContactReturnFixture.PUBLIC_A
    private val oldFrame = ConnectionInitialContactReturnFixture.oldFrame
    private val newFrame = ConnectionInitialContactReturnFixture.newFrame
    private val newOnly = ConnectionInitialContactReturnFixture.newOnly
    private val oldSession = ConnectionInitialContactReturnSession(selfKey, listOf(oldFrame), holdReturn = refresh)
    private val freshSession = ConnectionInitialContactReturnSession(selfKey, listOf(newFrame, newOnly))
    private val originEntered = CompletableDeferred<Job>()
    private var writerOperation: Job? = null
    private val writerEntered = CompletableDeferred<Job>()
    private val writerReturned = CompletableDeferred<Unit>()
    private val writerRelease = CompletableDeferred<Unit>()
    private val writerHold = AtomicBoolean(!refresh)
    private val dao = WriterDao(database.contactDao())
    private val adapter = ContactSessionAdapter(oldSession)
    private val observedSession = object : ContactSession by adapter {
        override suspend fun getContactsReportingTotal(since: Long?): ContactFetchResult {
            originEntered.complete(currentCoroutineContext().job)
            return adapter.getContactsReportingTotal(since)
        }
    }
    private val oldService = ContactService(
        observedSession,
        RoomContactStore(RoomContactParts(database, Uuid::random, contactDao = dao)),
        now = { NOW },
        edges = ContactServiceEdges(selfPublicKey = { selfKey }),
    )
    private val freshService = ContactService(
        ContactSessionAdapter(freshSession),
        RoomContactStore(database),
        now = { NOW },
        edges = ContactServiceEdges(selfPublicKey = { selfKey }),
    )
    private val callers = mutableListOf<Job>()
    lateinit var held: Deferred<Result<Unit>>
        private set
    lateinit var operation: Job
        private set
    var writeObservedInsideTransaction = false
        private set

    suspend fun seed() {
        freshService.syncContacts(radio)
        rows.enrich(radio, selfKey)
        assertEquals(2, database.contactDao().listByRadio(radio).size)
        assertEquals(2, database.messageDao().listByRadio(radio).size)
        noOutgoing()
    }

    suspend fun start() {
        held = scope.async {
            runCatching {
                if (refresh) oldService.syncContactsForRefresh(radio) else oldService.syncContacts(radio)
                Unit
            }
        }.also { callers += it }
        operation = originEntered.await()
        if (refresh) {
            val (job, result) = oldSession.completed.await()
            assertEquals(1, result.reportedTotal)
            assertEquals(1, result.contacts.size)
            assertTrue(operation.owns(job))
        } else {
            writerOperation = writerEntered.await()
            assertTrue(requireNotNull(writerOperation).isActive)
        }
        assertEquals(listOf<Long?>(null), oldSession.getContactsSince)
        assertTrue(held.isActive && operation.isActive && held.owns(operation))
        assertTrue(oldService.isSyncingContacts)
        assertFalse(oldSession.closed)
        if (!refresh) assertTrue(writeObservedInsideTransaction)
    }

    fun retire() = oldService.retireContactSyncOperations()

    suspend fun releaseAndJoin(): Throwable? {
        oldSession.release.complete(Unit)
        writerRelease.complete(Unit)
        val cause = held.await().exceptionOrNull()
        if (!refresh) writerReturned.await()
        assertTrue(cause == null || cause is CancellationException, "unexpected bulk failure: $cause")
        assertTrue(held.isCompleted && operation.isCompleted)
        assertTrue(scope.coroutineContext.job.isActive)
        assertFalse(oldService.isSyncingContacts)
        noOutgoing()
        return cause
    }

    suspend fun freshWritesRemainUsable() {
        freshService.syncContacts(radio)
        assertEquals(2, database.contactDao().listByRadio(radio).size)
        database.contactDao().listByRadio(radio).forEach { assertEquals(newFrame.advertisedName, it.name) }
        noOutgoing()
    }

    suspend fun noOutgoing() {
        assertTrue(oldSession.sentMessages.isEmpty() && freshSession.sentMessages.isEmpty())
        assertTrue(database.pendingSendDao().listByRadio(radio).isEmpty())
    }

    fun close() {
        oldSession.release.complete(Unit)
        writerRelease.complete(Unit)
        try {
            runBlocking(dispatcher) {
                withTimeout(TIMEOUT_MS) {
                    callers.forEach { it.cancel() }
                    callers.forEach { it.join() }
                    if (writerEntered.isCompleted) writerReturned.await()
                }
            }
        } finally {
            try {
                runBlocking(dispatcher) { withTimeout(TIMEOUT_MS) { scope.coroutineContext.job.cancelAndJoin() } }
            } finally {
                try {
                    database.close()
                } finally {
                    dispatcher.close()
                    val owned = listOf("", "-wal", "-shm").map { File(file.absolutePath + it) }
                    owned.forEach { it.delete() }
                    assertTrue(owned.none { it.exists() })
                }
            }
        }
    }

    private inner class WriterDao(private val delegate: ContactDao) : ContactDao by delegate {
        override suspend fun upsertPreservingPin(entity: ContactEntity) {
            if (!writerHold.compareAndSet(true, false)) {
                delegate.upsertPreservingPin(entity)
                return
            }
            database.useWriterConnection { writer ->
                try {
                    writer.immediateTransaction {
                        delegate.upsertPreservingPin(entity)
                        val written = requireNotNull(delegate.getById(entity.id))
                        assertEquals(oldFrame.advertisedName, written.name)
                        assertEquals(oldFrame.lastModified, written.lastModified)
                        writeObservedInsideTransaction = true
                        writerEntered.complete(currentCoroutineContext().job)
                        writerRelease.await()
                    }
                } finally {
                    writerReturned.complete(Unit)
                }
            }
        }
    }

    companion object {
        const val TIMEOUT_MS = 10_000L
        private const val NOW = 1_700_000_000L
        private fun Job.owns(other: Job): Boolean = this === other || children.any { it.owns(other) }
    }
}
