package one.meshcore.shared.connection

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.productionSchemaPolicy
import one.meshcore.shared.remotenode.handleBLEDisconnection
import one.meshcore.shared.remotenode.login
import one.meshcore.shared.remotenode.testSession
import one.meshcore.shared.transport.LinkDropReason
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectionRemoteCaptureOwnershipTest {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var gate: SnapshotGate
    private var now = OLD_TIME

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("remote-manager-owner", ".db").also { it.delete() }
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        now = OLD_TIME
    }

    @AfterTest
    fun tearDown() {
        if (::gate.isInitialized) gate.release.countDown()
        scope.cancelAndJoinBlocking()
        dispatcher.close()
        if (::db.isInitialized) db.close()
        dbFile.delete()
    }

    @Test
    fun anOldDropSnapshotCannotDisconnectTheNewGraphsAuthenticatedSession() = runBlocking(dispatcher) {
        verifyReplacement(entityRead = false)
    }

    @Test
    fun anOldDropSnapshotDuringRebuildCannotOverwriteTheNewGraphsAuthentication() = runBlocking(dispatcher) {
        verifyReplacement(entityRead = true)
    }

    private suspend fun verifyReplacement(entityRead: Boolean) {
        withTimeout(TIMEOUT_MS) {
            val manager = manager()
            manager.connect(DEVICE)
            assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
            val oldServices = requireNotNull(manager.services)
            val oldSession = requireNotNull(manager.session)
            val oldGeneration = manager.connectGeneration
            val dto = testSession(
                radioID = oldServices.radioId,
                isConnected = true,
                permissionLevel = RoomPermissionLevel.READ_WRITE,
            ).copy(lastConnectedDate = OLD_TIME)
            oldServices.remoteNodeService.store.saveSession(dto)
            gate.armed.set(true)
            val oldDrop = scope.async {
                manager.handleLinkDrop(DEVICE, LinkDropReason.CONNECTION_LOST, oldGeneration, oldSession)
            }
            try {
                gate.entered.await()
                assertFalse(oldDrop.isCompleted)
                if (entityRead) {
                    manager.rebuildConnectedBleAppStack(DEVICE)
                } else {
                    manager.disconnect()
                    assertEquals(ConnectionState.Disconnected, manager.state.value)
                    manager.connect(DEVICE)
                }
                assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
                val newServices = requireNotNull(manager.services)
                val newSession = manager.session as FakeMeshSession
                val newLink = requireNotNull(manager.link)
                val newGeneration = manager.connectGeneration
                assertNotSame(oldServices, newServices)
                assertNotSame(oldSession, newSession)
                if (entityRead) {
                    assertEquals(oldGeneration, newGeneration)
                } else {
                    assertTrue(newGeneration > oldGeneration)
                }
                assertEquals(oldServices.radioId, newServices.radioId)
                now = NEW_TIME
                assertEquals(
                    RoomPermissionLevel.ADMIN,
                    newServices.remoteNodeService.login(dto.id, password = TEST_PASSWORD).permissionLevel,
                )
                val fresh = requireNotNull(newServices.remoteNodeService.store.fetchSessionById(dto.id))
                assertTrue(fresh.isConnected)
                assertEquals(RoomPermissionLevel.ADMIN, fresh.permissionLevel)
                assertEquals(NEW_TIME, fresh.lastConnectedDate)
                assertEquals(1, newSession.loginDestinations.size)
                gate.release.countDown()
                val completionError = runCatching { oldDrop.await() }.exceptionOrNull()
                assertTrue(completionError == null || completionError is CancellationException)
                assertTrue(requireNotNull(scope.coroutineContext[Job]).isActive)
                assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
                assertEquals(newGeneration, manager.connectGeneration)
                assertSame(newServices, manager.services)
                assertSame(newSession, manager.session)
                assertSame(newLink, manager.link)
                assertFalse(newSession.closed)
                assertNull(manager.reconnectJob)
                assertTrue(manager.pendingReauthSessionIds.isEmpty())
                val final = requireNotNull(newServices.remoteNodeService.store.fetchSessionById(dto.id))
                assertEquals(dto.id, final.id)
                assertEquals(dto.radioID, final.radioID)
                assertTrue(dto.publicKey.contentEquals(final.publicKey))
                assertEquals(
                    ConnectionFields(true, RoomPermissionLevel.ADMIN, NEW_TIME),
                    ConnectionFields(final.isConnected, final.permissionLevel, final.lastConnectedDate),
                )
            } finally {
                gate.release.countDown()
            }
        }
    }

    @Test
    fun aQueuedCaptureCannotMutateSessionsAfterItsContainerRetires() = runBlocking(dispatcher) {
        withTimeout(TIMEOUT_MS) {
            val manager = manager()
            manager.connect(DEVICE)
            val oldServices = requireNotNull(manager.services)
            val dto = testSession(radioID = oldServices.radioId, isConnected = true)
            oldServices.remoteNodeService.store.saveSession(dto)
            val queued = scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                oldServices.remoteNodeService.handleBLEDisconnection()
            }
            manager.disconnect()
            manager.connect(DEVICE)
            val newServices = requireNotNull(manager.services)
            assertNotSame(oldServices, newServices)
            now = NEW_TIME
            newServices.remoteNodeService.login(dto.id, password = TEST_PASSWORD)
            val fresh = requireNotNull(newServices.remoteNodeService.store.fetchSessionById(dto.id))
            assertTrue(fresh.isConnected)
            assertEquals(RoomPermissionLevel.ADMIN, fresh.permissionLevel)
            assertEquals(NEW_TIME, fresh.lastConnectedDate)
            queued.start()
            val captured = queued.await()
            val final = requireNotNull(newServices.remoteNodeService.store.fetchSessionById(dto.id))
            assertEquals(
                ConnectionFields(true, RoomPermissionLevel.ADMIN, NEW_TIME),
                ConnectionFields(final.isConnected, final.permissionLevel, final.lastConnectedDate),
            )
            assertTrue(captured.isEmpty())
            assertTrue(requireNotNull(scope.coroutineContext[Job]).isActive)
        }
    }

    private fun manager(): ConnectionManager {
        gate = SnapshotGate()
        db = Room.databaseBuilder<MeshDatabase>(name = dbFile.absolutePath)
            .setDriver(SnapshotDriver(gate))
            .setQueryCoroutineContext(Dispatchers.IO)
            .productionSchemaPolicy()
            .build()
        return ConnectionManager(
            database = db,
            scope = scope,
            linkFactory = { FakeMeshLink() },
            sessionFactory = { FakeMeshSession() },
            nowEpochSeconds = { now },
        )
    }

    private data class ConnectionFields(val connected: Boolean, val permission: RoomPermissionLevel, val date: Double?)

    private class SnapshotGate {
        val armed = AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)

        fun matches(sql: String): Boolean = sql == "SELECT * FROM remote_node_session"

        fun park() {
            entered.complete(Unit)
            check(release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "snapshot gate timed out" }
        }
    }

    private class SnapshotDriver(private val gate: SnapshotGate) : SQLiteDriver {
        private val backing = BundledSQLiteDriver()

        override fun open(fileName: String): SQLiteConnection = SnapshotConnection(backing.open(fileName), gate)
    }

    private class SnapshotConnection(private val backing: SQLiteConnection, private val gate: SnapshotGate) :
        SQLiteConnection by backing {
        override fun prepare(sql: String): SQLiteStatement {
            val statement = backing.prepare(sql)
            return if (gate.matches(sql) && gate.armed.compareAndSet(true, false)) {
                GatedStatement(statement, gate)
            } else {
                statement
            }
        }
    }

    private class GatedStatement(private val backing: SQLiteStatement, private val gate: SnapshotGate) :
        SQLiteStatement by backing {
        private var parked = false

        override fun step(): Boolean {
            val hasRow = backing.step()
            if (hasRow && !parked) {
                parked = true
                gate.park()
            }
            return hasRow
        }
    }

    private companion object {
        const val DEVICE = "AA:BB:CC:DD:EE:75"
        const val TIMEOUT_MS = 10_000L
        const val OLD_TIME = 1_700_000_000.0
        const val NEW_TIME = OLD_TIME + 60.0
        const val TEST_PASSWORD = "test-only-password"
    }
}
