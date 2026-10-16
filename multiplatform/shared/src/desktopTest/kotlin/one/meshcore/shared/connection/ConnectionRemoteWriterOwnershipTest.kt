package one.meshcore.shared.connection

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.productionSchemaPolicy
import one.meshcore.shared.remotenode.login
import one.meshcore.shared.remotenode.testSession
import one.meshcore.shared.transport.LinkDropReason
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectionRemoteWriterOwnershipTest {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val queries = QueuedQueryDispatcher()
    private val readGate = ReadGate()
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("remote-writer-owner", ".db").also { it.delete() }
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        db = Room.databaseBuilder<MeshDatabase>(name = dbFile.absolutePath)
            .setDriver(GatedDriver(readGate))
            .setQueryCoroutineContext(queries)
            .productionSchemaPolicy()
            .build()
    }

    @AfterTest
    fun tearDown() {
        queries.release()
        scope.cancelAndJoinBlocking()
        dispatcher.close()
        db.close()
        dbFile.delete()
    }

    @Test
    fun aQueuedOldDatabaseWriterCannotOverwriteFreshAuthentication() = runBlocking(dispatcher) {
        verifyQueuedWriter(logout = false)
    }

    @Test
    fun aCancelledQueuedLogoutCannotOverwriteFreshAuthentication() = runBlocking(dispatcher) {
        verifyQueuedWriter(logout = true)
    }

    private suspend fun verifyQueuedWriter(logout: Boolean) {
        withTimeout(TIMEOUT_MS) {
            var now = OLD_TIME
            val manager = ConnectionManager(
                database = db,
                scope = scope,
                linkFactory = { FakeMeshLink() },
                sessionFactory = { FakeMeshSession() },
                nowEpochSeconds = { now },
            )
            manager.connect(DEVICE)
            val oldServices = requireNotNull(manager.services)
            val oldSession = requireNotNull(manager.session)
            val generation = manager.connectGeneration
            val dto = testSession(
                radioID = oldServices.radioId,
                isConnected = true,
                permissionLevel = RoomPermissionLevel.READ_WRITE,
            ).copy(lastConnectedDate = OLD_TIME)
            oldServices.remoteNodeService.store.saveSession(dto)
            val seedMutations = readGate.mutationPrepares.get()
            queries.armed.set(true)
            val oldDrop = scope.async(CoroutineName(OLD_DROP_NAME)) {
                if (logout) {
                    oldServices.remoteNodeService.logout(dto.id)
                } else {
                    manager.handleLinkDrop(DEVICE, LinkDropReason.CONNECTION_LOST, generation, oldSession)
                }
            }
            try {
                val queuedWriter = queries.entered.await()
                val operationChildren = oldDrop.children.toList()
                assertTrue(operationChildren.size <= 1)
                val originOperation = operationChildren.singleOrNull() ?: oldDrop
                val databaseOperation = queuedWriter.context.job
                assertTrue(originOperation === oldDrop || operationChildren.single() === originOperation)
                assertTrue(oldDrop.isActive)
                assertTrue(scope.coroutineContext.job.isActive)
                assertNotSame(oldDrop, databaseOperation)
                assertNotSame(originOperation, databaseOperation)
                val roomScope = db.javaClass.getMethod("getCoroutineScope").invoke(db) as CoroutineScope
                assertNotSame(roomScope.coroutineContext.job, databaseOperation)
                assertNotSame(scope.coroutineContext.job, databaseOperation)
                assertTrue(originOperation.isActive)
                assertTrue(databaseOperation.isActive)
                assertEquals(seedMutations, readGate.mutationPrepares.get())
                assertFalse(oldDrop.isCompleted)

                manager.rebuildConnectedBleAppStack(DEVICE)
                assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
                val newServices = requireNotNull(manager.services)
                val newSession = manager.session as FakeMeshSession
                val newLink = requireNotNull(manager.link)
                assertNotSame(oldServices, newServices)
                assertNotSame(oldSession, newSession)
                assertEquals(generation, manager.connectGeneration)
                if (logout) oldDrop.cancel()
                assertTrue(originOperation.isCancelled)
                assertTrue(databaseOperation.isActive)
                now = NEW_TIME
                assertEquals(
                    RoomPermissionLevel.ADMIN,
                    newServices.remoteNodeService.login(dto.id, password = TEST_PASSWORD).permissionLevel,
                )
                val fresh = requireNotNull(newServices.remoteNodeService.store.fetchSessionById(dto.id))
                assertTrue(fresh.isConnected)
                assertEquals(RoomPermissionLevel.ADMIN, fresh.permissionLevel)
                assertEquals(NEW_TIME, fresh.lastConnectedDate)
                assertTrue(readGate.mutationPrepares.get() > seedMutations)
                queries.release()
                val failure = runCatching { oldDrop.await() }.exceptionOrNull()
                assertTrue(failure is CancellationException)
                databaseOperation.join()
                assertTrue(databaseOperation.isCompleted)
                assertTrue(originOperation.isCancelled)
                assertTrue(scope.coroutineContext.job.isActive)
                assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
                assertSame(newServices, manager.services)
                assertSame(newSession, manager.session)
                assertSame(newLink, manager.link)
                val final = requireNotNull(newServices.remoteNodeService.store.fetchSessionById(dto.id))
                assertEquals(
                    ConnectionFields(true, RoomPermissionLevel.ADMIN, NEW_TIME),
                    ConnectionFields(final.isConnected, final.permissionLevel, final.lastConnectedDate),
                )
            } finally {
                queries.release()
            }
        }
    }

    private data class ConnectionFields(val connected: Boolean, val permission: RoomPermissionLevel, val date: Double?)

    private data class QueuedWork(val context: CoroutineContext, val block: Runnable)

    private class QueuedQueryDispatcher : CoroutineDispatcher() {
        val armed = AtomicBoolean(false)
        val entered = CompletableDeferred<QueuedWork>()
        private val held = AtomicReference<QueuedWork?>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (
                context[CoroutineName]?.name == OLD_DROP_NAME &&
                Thread.currentThread().stackTrace.any {
                    it.className.startsWith("androidx.room.util.DBUtil") &&
                        it.methodName == "performInTransactionSuspending"
                } &&
                armed.compareAndSet(true, false)
            ) {
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

    private class ReadGate {
        val mutationPrepares = AtomicInteger()
    }

    private class GatedDriver(private val gate: ReadGate) : SQLiteDriver {
        private val backing = BundledSQLiteDriver()

        override fun open(fileName: String): SQLiteConnection = GatedConnection(backing.open(fileName), gate)
    }

    private class GatedConnection(private val backing: SQLiteConnection, private val gate: ReadGate) :
        SQLiteConnection by backing {
        override fun prepare(sql: String): SQLiteStatement {
            if ((sql.startsWith("INSERT") || sql.startsWith("UPDATE")) && sql.contains("remote_node_session")) {
                gate.mutationPrepares.incrementAndGet()
            }
            return backing.prepare(sql)
        }
    }

    private companion object {
        const val DEVICE = "AA:BB:CC:DD:EE:76"
        const val TIMEOUT_MS = 10_000L
        const val OLD_TIME = 1_700_000_000.0
        const val NEW_TIME = OLD_TIME + 60.0
        const val OLD_DROP_NAME = "old-disconnect-writer"
        const val TEST_PASSWORD = "test-only-password"
    }
}
