package one.meshcore.shared.persistence

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/** Native Android Room ownership at the store boundary; manager replacement is exercised on the host. */
@RunWith(AndroidJUnit4::class)
class RemoteConnectionWriterInstrumentedTest {
    private val queries = QueuedTransactionDispatcher()
    private lateinit var databaseFile: File
    private lateinit var database: MeshDatabase
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "remote-writer-${Uuid.random().value}.db"
        databaseFile = context.getDatabasePath(databaseName)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        database = meshDatabase(context, queries, databaseName)
    }

    @After
    fun tearDown() = runBlocking {
        queries.release()
        if (::scope.isInitialized) scope.coroutineContext.job.cancelAndJoin()
        if (::database.isInitialized) database.close()
        if (::databaseFile.isInitialized) {
            databaseFile.delete()
            File(databaseFile.path + "-wal").delete()
            File(databaseFile.path + "-shm").delete()
        }
    }

    @Test
    fun canceledQueuedDisconnectCannotOverwriteFreshAuthentication() = queuedCancellationCase { store, id ->
        store.markSessionDisconnected(id)
    }

    @Test
    fun canceledQueuedConnectionUpdateCannotOverwriteFreshAuthentication() = queuedCancellationCase { store, id ->
        store.updateSessionConnection(id, false, RoomPermissionLevel.GUEST)
    }

    private fun queuedCancellationCase(mutation: suspend (RoomNodeStore, Uuid) -> Unit) = runBlocking {
        withTimeout(TIMEOUT_MS) {
            val dto = seededSession()
            val oldStore = store(dto.radioID, OLD_TIME)
            val newStore = store(dto.radioID, NEW_TIME)
            oldStore.saveSession(dto)
            val originatingJob = CompletableDeferred<Job>()
            val oldWriter = scope.async(CoroutineName(OLD_OPERATION_NAME)) {
                originatingJob.complete(currentCoroutineContext().job)
                mutation(oldStore, dto.id)
            }
            var databaseOperation: Job? = null
            try {
                val queuedWriter = queries.entered.await()
                val origin = originatingJob.await()
                val operation = queuedWriter.context.job
                databaseOperation = operation
                val roomScope = database.javaClass.getMethod("getCoroutineScope").invoke(database) as CoroutineScope
                assertSame(oldWriter, origin)
                assertNotSame(origin, operation)
                assertNotSame(scope.coroutineContext.job, operation)
                assertNotSame(roomScope.coroutineContext.job, operation)
                assertTrue(origin.isActive)
                assertTrue(operation.isActive)
                assertFalse(oldWriter.isCompleted)
                oldWriter.cancel()
                assertTrue(origin.isCancelled)
                assertTrue(operation.isActive)
                assertTrue(queries.isHeld)

                newStore.updateSessionConnection(dto.id, true, RoomPermissionLevel.ADMIN)
                assertFreshFields(requireNotNull(newStore.fetchSessionById(dto.id)))
                assertTrue(queries.isHeld)
                assertTrue(operation.isActive)
                queries.release()
                operation.join()
                assertTrue(operation.isCompleted)
                assertTrue(runCatching { oldWriter.await() }.exceptionOrNull() is CancellationException)
                assertFreshFields(requireNotNull(newStore.fetchSessionById(dto.id)))
                assertTrue(roomScope.coroutineContext.job.isActive)
                assertTrue(scope.coroutineContext.job.isActive)
            } finally {
                queries.release()
                withContext(NonCancellable) {
                    withTimeout(TIMEOUT_MS) { databaseOperation?.join() }
                }
            }
        }
    }

    private fun store(radioID: Uuid, now: Double) = RoomNodeStore(
        radioID,
        database.remoteNodeSessionDao(),
        database.roomMessageDao(),
        database.contactDao(),
    ) { now }

    private fun assertFreshFields(session: RemoteNodeSessionDto) {
        assertTrue(session.isConnected)
        assertEquals(RoomPermissionLevel.ADMIN, session.permissionLevel)
        assertEquals(NEW_TIME, requireNotNull(session.lastConnectedDate), DATE_TOLERANCE)
        assertTrue(session.isPinned)
        assertEquals(UNREAD_COUNT, session.unreadCount)
        assertEquals(NotificationLevel.MENTIONS_ONLY, session.notificationLevel)
        assertEquals(SYNC_TIMESTAMP, session.lastSyncTimestamp)
    }

    private fun seededSession() = RemoteNodeSessionDto(
        id = Uuid.random(),
        radioID = Uuid.random(),
        publicKey = ByteArray(PUBLIC_KEY_SIZE) { PUBLIC_KEY_SEED },
        name = "Native writer fixture",
        role = RemoteNodeRole.REPEATER,
        latitude = COORDINATE,
        longitude = COORDINATE,
        isConnected = true,
        permissionLevel = RoomPermissionLevel.READ_WRITE,
        unreadCount = UNREAD_COUNT,
        notificationLevel = NotificationLevel.MENTIONS_ONLY,
        isPinned = true,
        neighborCount = NEIGHBOR_COUNT,
        lastSyncTimestamp = SYNC_TIMESTAMP,
        lastConnectedDate = OLD_TIME,
    )

    private data class QueuedWork(val context: CoroutineContext, val block: Runnable)

    private class QueuedTransactionDispatcher : CoroutineDispatcher() {
        val entered = CompletableDeferred<QueuedWork>()
        private val armed = AtomicBoolean(true)
        private val held = AtomicReference<QueuedWork?>()
        val isHeld: Boolean get() = held.get() != null

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val transactionDispatch = context[CoroutineName]?.name == OLD_OPERATION_NAME &&
                Thread.currentThread().stackTrace.any {
                    it.className.startsWith("androidx.room.util.DBUtil") &&
                        it.methodName == "performInTransactionSuspending"
                }
            if (transactionDispatch && armed.compareAndSet(true, false)) {
                val work = QueuedWork(context, block)
                check(held.compareAndSet(null, work))
                entered.complete(work)
            } else {
                Dispatchers.IO.dispatch(context, block)
            }
        }

        fun release() {
            held.getAndSet(null)?.let { Dispatchers.IO.dispatch(it.context, it.block) }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val OLD_TIME = 1_700_000_000.0
        const val NEW_TIME = OLD_TIME + 60.0
        const val UNREAD_COUNT = 5
        const val SYNC_TIMESTAMP = 123L
        const val NEIGHBOR_COUNT = 2
        const val PUBLIC_KEY_SIZE = 32
        const val PUBLIC_KEY_SEED: Byte = 0x42
        const val COORDINATE = 0.0
        const val DATE_TOLERANCE = 0.0
        const val OLD_OPERATION_NAME = "native-old-connection-writer"
    }
}
