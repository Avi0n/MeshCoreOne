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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.chat.awaitDrainCompletion
import one.meshcore.shared.contact.handleDeviceContactDeleted
import one.meshcore.shared.contact.hasPendingDeletedKey
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.productionSchemaPolicy
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectionDisconnectCaptureTest {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val gate = CaptureGate()
    private lateinit var file: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope
    private var ownedManager: ConnectionManager? = null

    @BeforeTest
    fun setUp() {
        file = File.createTempFile("disconnect-capture", ".db").also { it.delete() }
        db = Room.databaseBuilder<MeshDatabase>(name = file.absolutePath)
            .setDriver(CaptureDriver(gate))
            .setQueryCoroutineContext(Dispatchers.IO)
            .productionSchemaPolicy()
            .build()
        scope = CoroutineScope(SupervisorJob() + dispatcher)
    }

    @AfterTest
    fun tearDown() {
        gate.release.countDown()
        try {
            runBlocking(dispatcher) {
                withTimeout(TIMEOUT_MS) {
                    ownedManager?.takeIf { it.services != null }?.disconnect()
                }
            }
        } finally {
            try {
                runBlocking {
                    withTimeout(TIMEOUT_MS) { requireNotNull(scope.coroutineContext[Job]).cancelAndJoin() }
                }
            } finally {
                try {
                    dispatcher.close()
                } finally {
                    try {
                        db.close()
                    } finally {
                        listOf(file, File("${file.path}-wal"), File("${file.path}-shm")).forEach { it.delete() }
                    }
                }
            }
        }
    }

    @Test
    fun emptyRemoteCaptureDisconnectsNormally() = runBlocking(dispatcher) {
        verifyDisconnect(Mode.NORMAL)
    }

    @Test
    fun ordinaryRemoteReadFailureStillDisconnects() = runBlocking(dispatcher) {
        verifyDisconnect(Mode.READ_FAILURE)
    }

    @Test
    fun remoteReadCancellationCleansConnectionBeforeRethrowing() = runBlocking(dispatcher) {
        verifyDisconnect(Mode.READ_CANCELLATION)
    }

    @Test
    fun cancelledDisconnectCallerCleansItsConnection() = runBlocking(dispatcher) {
        verifyDisconnect(Mode.CALLER_CANCELLATION)
    }

    @Test
    fun anOldRemoteCaptureCannotDisconnectAReplacement() = runBlocking(dispatcher) {
        withTimeout(TIMEOUT_MS) {
            val intents = ConnectionIntentStore(InMemoryPreferencesStore())
            val manager = ConnectionManager(
                database = db,
                scope = scope,
                linkFactory = { FakeMeshLink() },
                sessionFactory = { FakeMeshSession() },
                nowEpochSeconds = { NOW_SECONDS },
                ports = ConnectionManagerPorts(intentStore = intents),
            ).also { ownedManager = it }
            manager.connect(DEVICE)
            val originalServices = requireNotNull(manager.services)
            val originalSession = manager.session as FakeMeshSession
            val originalLink = manager.link as FakeMeshLink
            gate.hold = true
            gate.armed.set(true)
            val operation = scope.async { manager.disconnect() }
            gate.entered.await()
            assertSame(originalServices, manager.services)
            assertFalse(originalServices.sendQueue.isRetired)
            assertNull(manager.currentTransportType)
            manager.connect(OTHER_DEVICE, forceReconnect = true)
            assertEquals(ConnectionState.Ready(OTHER_DEVICE), manager.state.value)
            val replacementServices = requireNotNull(manager.services)
            val replacementSession = manager.session as FakeMeshSession
            val replacementLink = manager.link as FakeMeshLink
            val generation = manager.connectGeneration
            gate.release.countDown()
            val failure = runCatching { operation.await() }.exceptionOrNull()
            assertTrue(failure == null || failure is CancellationException)
            assertTrue(requireNotNull(scope.coroutineContext[Job]).isActive)
            assertEquals(ConnectionState.Ready(OTHER_DEVICE), manager.state.value)
            assertEquals(generation, manager.connectGeneration)
            assertSame(replacementServices, manager.services)
            assertSame(replacementSession, manager.session)
            assertSame(replacementLink, manager.link)
            assertFalse(replacementServices.sendQueue.isRetired)
            assertTrue(replacementServices.eventMonitoringActive)
            assertEquals(0, replacementSession.closeCount)
            assertEquals(0, replacementLink.disconnectCount)
            assertTrue(manager.wantsConnection)
            assertFalse(intents.restore() == ConnectionIntent.UserDisconnected)
            assertTrue(originalServices.sendQueue.isRetired)
            assertFalse(originalServices.eventMonitoringActive)
            originalServices.sendQueue.awaitDrainCompletion()
            assertEquals(1, originalSession.closeCount)
            assertEquals(1, originalLink.disconnectCount)
            assertEquals(1, gate.reads.get())
        }
    }

    @Test
    fun captureCancellationPreservesFailedCleanupForRetry() = runBlocking(dispatcher) {
        verifyDisconnect(Mode.CLEANUP_FAILURE)
    }

    private suspend fun verifyDisconnect(mode: Mode) {
        withTimeout(TIMEOUT_MS) {
            val preferences = InMemoryPreferencesStore()
            val intents = ConnectionIntentStore(preferences)
            val link = FakeMeshLink()
            val cleanupFailure = IllegalStateException("test contact-change callback failure")
            val key = ByteArray(PUBLIC_KEY_SIZE) { CONTACT_KEY_BYTE }
            var failNextCallback = false
            val session = FakeMeshSession(
                contacts = if (mode == Mode.CLEANUP_FAILURE) listOf(sampleContact(key, "Node")) else emptyList(),
            )
            val manager = ConnectionManager(
                database = db,
                scope = scope,
                linkFactory = { link },
                sessionFactory = { session },
                nowEpochSeconds = { NOW_SECONDS },
                ports = ConnectionManagerPorts(
                    intentStore = intents,
                    onContactsChanged = {
                        if (failNextCallback) {
                            failNextCallback = false
                            throw cleanupFailure
                        }
                    },
                ),
            ).also { ownedManager = it }
            manager.connect(DEVICE)
            assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
            val services = requireNotNull(manager.services)
            assertFalse(services.sendQueue.isRetired)
            assertTrue(services.eventMonitoringActive)
            if (mode == Mode.CLEANUP_FAILURE) {
                manager.setAppActive(false)
                assertFalse(services.contactService.handleDeviceContactDeleted(services.radioId, key))
                assertTrue(services.contactService.hasPendingDeletedKey(key))
                failNextCallback = true
            }
            val cancellation = CancellationException("test remote capture cancellation")
            gate.failure = when (mode) {
                Mode.READ_CANCELLATION, Mode.CLEANUP_FAILURE -> cancellation
                Mode.READ_FAILURE -> IllegalStateException("test remote read failure")
                else -> null
            }
            gate.hold = mode == Mode.CALLER_CANCELLATION
            gate.armed.set(true)
            val completion = CompletableDeferred<Throwable?>()
            val operation = scope.async { manager.disconnect() }
            operation.invokeOnCompletion { completion.complete(it) }
            gate.entered.await()
            if (mode == Mode.CALLER_CANCELLATION) {
                assertFalse(operation.isCompleted)
                assertSame(services, manager.services)
                assertNull(manager.currentTransportType)
                operation.cancel(cancellation)
                gate.release.countDown()
            }
            operation.join()
            val failure = completion.await()
            if (mode == Mode.CALLER_CANCELLATION || mode == Mode.READ_CANCELLATION || mode == Mode.CLEANUP_FAILURE) {
                assertTrue(failure is CancellationException)
                assertTrue(failure === cancellation || failure.cause === cancellation)
                assertEquals(cancellation.message, failure.message)
            } else {
                assertNull(failure)
                operation.await()
            }
            assertEquals(1, gate.reads.get())
            assertTrue(requireNotNull(scope.coroutineContext[Job]).isActive)
            if (mode == Mode.CLEANUP_FAILURE) {
                val original = if (failure === cancellation) failure else failure?.cause
                assertSame(cancellation, original)
                assertTrue(
                    requireNotNull(failure).suppressedExceptions.any {
                        it === cleanupFailure ||
                            (
                                it.javaClass == cleanupFailure.javaClass &&
                                    it.message == cleanupFailure.message &&
                                    it.cause === cleanupFailure &&
                                    it.stackTrace.any { frame -> frame.className.startsWith("_COROUTINE.") }
                                )
                    },
                    "cleanup failure must retain its identity or original cause; " +
                        "suppressed=${failure.suppressedExceptions.map { it.toString() to it.cause?.toString() }}",
                )
                assertFalse(failNextCallback)
                assertSame(services, manager.services)
                assertSame(session, manager.session)
                assertSame(link, manager.link)
                assertEquals(0, session.closeCount)
                assertEquals(0, link.disconnectCount)
                manager.disconnect()
            }
            assertTrue(services.sendQueue.isRetired, "disconnect must retire its original queue")
            assertFalse(services.eventMonitoringActive)
            services.sendQueue.awaitDrainCompletion()
            assertEquals(ConnectionState.Disconnected, manager.state.value)
            assertNull(manager.services)
            assertNull(manager.session)
            assertNull(manager.link)
            assertEquals(1, session.closeCount)
            assertTrue(session.stopped)
            assertEquals(1, link.disconnectCount)
            assertFalse(manager.wantsConnection)
            assertEquals(ConnectionIntent.UserDisconnected, intents.restore())
        }
    }

    private enum class Mode { NORMAL, READ_FAILURE, READ_CANCELLATION, CALLER_CANCELLATION, CLEANUP_FAILURE }

    private class CaptureGate {
        val armed = AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        var hold = false
        var failure: Throwable? = null

        fun intercept() {
            reads.incrementAndGet()
            entered.complete(Unit)
            if (hold) check(release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "capture gate timed out" }
            failure?.let { throw it }
        }
    }

    private class CaptureDriver(private val gate: CaptureGate) : SQLiteDriver {
        private val backing = BundledSQLiteDriver()

        override fun open(fileName: String): SQLiteConnection = CaptureConnection(backing.open(fileName), gate)
    }

    private class CaptureConnection(private val backing: SQLiteConnection, private val gate: CaptureGate) :
        SQLiteConnection by backing {
        override fun prepare(sql: String): SQLiteStatement {
            val statement = backing.prepare(sql)
            return if (sql == "SELECT * FROM remote_node_session" && gate.armed.compareAndSet(true, false)) {
                CaptureStatement(statement, gate)
            } else {
                statement
            }
        }
    }

    private class CaptureStatement(private val backing: SQLiteStatement, private val gate: CaptureGate) :
        SQLiteStatement by backing {
        private var intercepted = false

        override fun step(): Boolean {
            val hasRow = backing.step()
            if (!intercepted) {
                intercepted = true
                gate.intercept()
            }
            return hasRow
        }
    }

    private companion object {
        const val DEVICE = "AA:BB:CC:DD:EE:76"
        const val OTHER_DEVICE = "AA:BB:CC:DD:EE:77"
        const val PUBLIC_KEY_SIZE = 32
        const val CONTACT_KEY_BYTE: Byte = 9
        const val TIMEOUT_MS = 10_000L
        const val NOW_SECONDS = 1_700_000_000.0
    }
}
