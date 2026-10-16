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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.RoomNodeStore
import one.meshcore.shared.persistence.productionSchemaPolicy
import one.meshcore.shared.remotenode.login
import one.meshcore.shared.remotenode.testSession
import one.meshcore.shared.transport.LinkDropReason
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectionRemoteHeldWriterOwnershipTest {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val queries = WriterObservingDispatcher()
    private val gate = MutationGate()
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("remote-held-writer", ".db").also { it.delete() }
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        db = Room.databaseBuilder<MeshDatabase>(name = dbFile.absolutePath)
            .setDriver(GatedDriver(gate))
            .setQueryCoroutineContext(queries)
            .productionSchemaPolicy()
            .build()
    }

    @AfterTest
    fun tearDown() {
        gate.release.countDown()
        scope.cancelAndJoinBlocking()
        dispatcher.close()
        db.close()
        dbFile.delete()
    }

    @Test
    fun anAlreadyHeldOldWriterFinishesBeforeActualRebuildAndAuthentication() = runBlocking(dispatcher) {
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
            ).copy(lastConnectedDate = OLD_TIME, isPinned = true, unreadCount = UNREAD_COUNT)
            oldServices.remoteNodeService.store.saveSession(dto)
            val seedMutations = gate.successfulMutationSteps.get()
            gate.armed.set(true)
            val oldDrop = scope.async(CoroutineName(OLD_WRITER_NAME)) {
                manager.handleLinkDrop(DEVICE, LinkDropReason.CONNECTION_LOST, generation, oldSession)
            }
            try {
                gate.entered.await()
                val oldDatabaseOperation = queries.oldTransaction.await().context.job
                val originOperation = oldDrop.children.single()
                assertFiniteDatabaseOperation(oldDatabaseOperation, originOperation)
                assertTrue(originOperation.isActive)
                assertTrue(oldDatabaseOperation.isActive)
                now = NEW_TIME
                val rebuildAndLogin = scope.async(CoroutineName(REBUILD_NAME)) {
                    manager.rebuildConnectedBleAppStack(DEVICE)
                    val replacement = requireNotNull(manager.services)
                    assertNotSame(oldServices, replacement)
                    assertNotSame(oldSession, manager.session)
                    assertEquals(generation, manager.connectGeneration)
                    assertEquals(
                        RoomPermissionLevel.ADMIN,
                        replacement.remoteNodeService.login(dto.id, password = TEST_PASSWORD).permissionLevel,
                    )
                    replacement
                }
                val replacementWrite = queries.contendingWrite.await()
                replacementWrite.returned.await()
                assertTrue(originOperation.isCancelled)
                assertTrue(oldDatabaseOperation.isActive)
                assertTrue(replacementWrite.context.job.isActive)
                assertFalse(rebuildAndLogin.isCompleted)
                assertEquals(seedMutations, gate.successfulMutationSteps.get())
                assertEquals(1L, gate.release.count)

                // Rebuild persists its device before publishing services, so authentication follows
                // that blocked writer. The separate store control exercises auth-writer contention.
                gate.release.countDown()
                oldDatabaseOperation.join()
                assertTrue(oldDatabaseOperation.isCompleted)
                assertTrue(runCatching { oldDrop.await() }.exceptionOrNull() is CancellationException)
                val replacement = rebuildAndLogin.await()
                assertSame(replacement, manager.services)
                assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
                assertFreshFields(requireNotNull(replacement.remoteNodeService.store.fetchSessionById(dto.id)))
                assertTrue(scope.coroutineContext.job.isActive)
            } finally {
                gate.release.countDown()
            }
        }
    }

    @Test
    fun aSecondRealRoomStoresAuthenticationWriteWaitsForAnAlreadyHeldWriter() = runBlocking(dispatcher) {
        withTimeout(TIMEOUT_MS) {
            val dto = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.READ_WRITE)
                .copy(lastConnectedDate = OLD_TIME, isPinned = true, unreadCount = UNREAD_COUNT)
            fun store(now: Double) = RoomNodeStore(
                dto.radioID,
                db.remoteNodeSessionDao(),
                db.roomMessageDao(),
                db.contactDao(),
            ) { now }
            val oldStore = store(OLD_TIME)
            val newStore = store(NEW_TIME)
            oldStore.saveSession(dto)
            val seedMutations = gate.successfulMutationSteps.get()
            gate.armed.set(true)
            val oldMutation = scope.async(CoroutineName(OLD_WRITER_NAME)) {
                oldStore.markSessionDisconnected(dto.id)
            }
            try {
                gate.entered.await()
                val oldDatabaseOperation = queries.oldTransaction.await().context.job
                assertFiniteDatabaseOperation(oldDatabaseOperation, oldMutation)
                oldMutation.cancel()
                val authenticationWrite = scope.async(CoroutineName(AUTH_WRITE_NAME)) {
                    newStore.updateSessionConnection(dto.id, true, RoomPermissionLevel.ADMIN)
                }
                val contendingWrite = queries.contendingWrite.await()
                contendingWrite.returned.await()
                assertTrue(oldMutation.isCancelled)
                assertTrue(oldDatabaseOperation.isActive)
                assertTrue(contendingWrite.context.job.isActive)
                assertFalse(authenticationWrite.isCompleted)
                assertEquals(seedMutations, gate.successfulMutationSteps.get())
                assertEquals(1L, gate.release.count)
                gate.release.countDown()
                oldDatabaseOperation.join()
                assertTrue(oldDatabaseOperation.isCompleted)
                assertTrue(runCatching { oldMutation.await() }.exceptionOrNull() is CancellationException)
                authenticationWrite.await()
                assertFreshFields(requireNotNull(newStore.fetchSessionById(dto.id)))
                assertTrue(scope.coroutineContext.job.isActive)
            } finally {
                gate.release.countDown()
            }
        }
    }

    private fun assertFiniteDatabaseOperation(databaseOperation: Job, originOperation: Job) {
        val roomScope = db.javaClass.getMethod("getCoroutineScope").invoke(db) as CoroutineScope
        assertNotSame(roomScope.coroutineContext.job, databaseOperation)
        assertNotSame(scope.coroutineContext.job, databaseOperation)
        assertNotSame(originOperation, databaseOperation)
    }

    private fun assertFreshFields(session: one.meshcore.shared.backup.RemoteNodeSessionDto) {
        assertTrue(session.isConnected)
        assertEquals(RoomPermissionLevel.ADMIN, session.permissionLevel)
        assertEquals(NEW_TIME, session.lastConnectedDate)
        assertTrue(session.isPinned)
        assertEquals(UNREAD_COUNT, session.unreadCount)
    }

    private data class ObservedWork(
        val context: CoroutineContext,
        val returned: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private class WriterObservingDispatcher : CoroutineDispatcher() {
        val oldTransaction = CompletableDeferred<ObservedWork>()
        val contendingWrite = CompletableDeferred<ObservedWork>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val frames = Thread.currentThread().stackTrace
            val transaction = frames.any {
                it.className.startsWith("androidx.room.util.DBUtil") &&
                    it.methodName == "performInTransactionSuspending"
            }
            val name = context[CoroutineName]?.name
            val signal = when {
                name == OLD_WRITER_NAME && transaction -> oldTransaction
                name == AUTH_WRITE_NAME && transaction -> contendingWrite
                name == REBUILD_NAME &&
                    frames.any {
                        it.className.endsWith("DeviceDao_Impl") && it.methodName == "upsert"
                    } -> contendingWrite
                else -> null
            }
            val work = ObservedWork(context)
            val observed = signal?.complete(work) == true
            Dispatchers.IO.dispatch(context) {
                try {
                    block.run()
                } finally {
                    if (observed) work.returned.complete(Unit)
                }
            }
        }
    }

    private class MutationGate {
        val armed = AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val successfulMutationSteps = AtomicInteger()
    }

    private class GatedDriver(private val gate: MutationGate) : SQLiteDriver {
        private val backing = BundledSQLiteDriver()

        override fun open(fileName: String): SQLiteConnection = GatedConnection(backing.open(fileName), gate)
    }

    private class GatedConnection(private val backing: SQLiteConnection, private val gate: MutationGate) :
        SQLiteConnection by backing {
        override fun prepare(sql: String): SQLiteStatement {
            val statement = backing.prepare(sql)
            val isMutation = (sql.startsWith("INSERT") || sql.startsWith("UPDATE")) &&
                sql.contains("remote_node_session")
            return if (isMutation) MutationStatement(statement, gate) else statement
        }
    }

    private class MutationStatement(private val backing: SQLiteStatement, private val gate: MutationGate) :
        SQLiteStatement by backing {
        override fun step(): Boolean {
            if (gate.armed.compareAndSet(true, false)) {
                gate.entered.complete(Unit)
                check(gate.release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "held writer gate timed out" }
            }
            return backing.step().also { gate.successfulMutationSteps.incrementAndGet() }
        }
    }

    private companion object {
        const val DEVICE = "AA:BB:CC:DD:EE:77"
        const val TIMEOUT_MS = 10_000L
        const val OLD_TIME = 1_700_000_000.0
        const val NEW_TIME = OLD_TIME + 60.0
        const val UNREAD_COUNT = 5
        const val OLD_WRITER_NAME = "old-held-disconnect-writer"
        const val REBUILD_NAME = "replacement-held-writer-rebuild"
        const val AUTH_WRITE_NAME = "replacement-held-writer-auth"
        const val TEST_PASSWORD = "test-only-password"
    }
}
