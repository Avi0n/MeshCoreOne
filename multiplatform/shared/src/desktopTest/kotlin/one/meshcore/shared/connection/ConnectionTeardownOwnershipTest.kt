package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.transport.LinkDropReason
import one.meshcore.shared.transport.MeshLink
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectionTeardownOwnershipTest {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-teardown-ownership", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        scope = CoroutineScope(SupervisorJob() + dispatcher)
    }

    @AfterTest
    fun tearDown() {
        scope.cancelAndJoinBlocking()
        dispatcher.close()
        db.close()
        dbFile.delete()
    }

    @Test
    fun anOldLinkDropTeardownCannotCloseAReplacement() = runBlocking(dispatcher) {
        verifySuspendedTeardown(Operation.DROP, cancelledStop = false)
    }

    @Test
    fun anOldUserDisconnectCannotCloseOrDisableAReplacement() = runBlocking(dispatcher) {
        verifySuspendedTeardown(Operation.DISCONNECT, cancelledStop = false)
    }

    @Test
    fun anOldConnectTeardownCannotCloseAReplacement() = runBlocking(dispatcher) {
        verifySuspendedTeardown(Operation.CONNECT, cancelledStop = false)
    }

    @Test
    fun aCancelledOldTeardownCleansOnlyItsOriginalResources() = runBlocking(dispatcher) {
        verifySuspendedTeardown(Operation.DROP, cancelledStop = true)
    }

    @Test
    fun anOldSessionRebuildTeardownCannotCloseAReplacement() = runBlocking(dispatcher) {
        verifySuspendedTeardown(Operation.KEEP_LINK, cancelledStop = false)
    }

    @Test
    fun anOldAppStackRebuildCannotInstallOverAReplacement() = runBlocking(dispatcher) {
        verifySuspendedTeardown(Operation.REBUILD, cancelledStop = false)
    }

    private suspend fun verifySuspendedTeardown(operation: Operation, cancelledStop: Boolean) {
        withTimeout(TIMEOUT_MS) {
            val prefs = InMemoryPreferencesStore()
            val intentStore = ConnectionIntentStore(prefs)
            val originalSession = GatedStopSession(cancelledStop)
            val links = mutableListOf<FakeMeshLink>()
            val handles = mutableMapOf<MeshLink, String>()
            var originalSessionCreated = false
            val manager = ConnectionManager(
                database = db,
                scope = scope,
                linkFactory = { handle ->
                    FakeMeshLink().also {
                        links += it
                        handles[it] = handle
                    }
                },
                sessionFactory = { link ->
                    if (handles[link] == DEVICE_A && !originalSessionCreated) {
                        originalSessionCreated = true
                        originalSession
                    } else {
                        FakeMeshSession(
                            selfInfo = defaultSelfInfo(
                                ByteArray(PUBLIC_KEY_SIZE) {
                                    if (handles[link] == DEVICE_A) ORIGINAL_KEY_BYTE else OTHER_KEY_BYTE
                                },
                            ),
                        )
                    }
                },
                nowEpochSeconds = { NOW_SECONDS },
                ports = ConnectionManagerPorts(
                    lastConnectionStore = LastConnectionStore(prefs),
                    intentStore = intentStore,
                ),
            )
            manager.connect(DEVICE_A)
            assertEquals(ConnectionState.Ready(DEVICE_A), manager.state.value)
            val originalLink = links.single()
            originalSession.armed = true
            val oldOperation: Deferred<Unit>? = when (operation) {
                Operation.DROP -> {
                    originalLink.drop(LinkDropReason.CONNECTION_LOST)
                    null
                }
                Operation.DISCONNECT -> scope.async { manager.disconnect() }
                Operation.CONNECT -> scope.async { manager.connect(DEVICE_B, forceReconnect = true) }
                Operation.KEEP_LINK -> scope.async { manager.dropSessionKeepingLink() }
                Operation.REBUILD -> scope.async { manager.rebuildConnectedBleAppStack(DEVICE_A) }
            }
            val oldJob = originalSession.entered.await()
            assertFalse(oldJob.isCompleted)
            val completion = CompletableDeferred<Throwable?>()
            oldJob.invokeOnCompletion { completion.complete(it) }
            if (oldOperation != null) assertSame(oldOperation, oldJob)
            val parkedGeneration = manager.connectGeneration
            manager.connect(DEVICE_C, forceReconnect = true)
            assertEquals(ConnectionState.Ready(DEVICE_C), manager.state.value)
            assertTrue(manager.connectGeneration > parkedGeneration)
            assertNull(manager.reconnectJob)
            val newServices = requireNotNull(manager.services)
            val newSession = manager.session as FakeMeshSession
            val newLink = manager.link as FakeMeshLink
            val newGeneration = manager.connectGeneration
            originalSession.release.complete(Unit)
            oldJob.join()
            if (operation == Operation.CONNECT) {
                assertFailsWith<CancellationException> { requireNotNull(oldOperation).await() }
            } else if (operation != Operation.REBUILD) {
                oldOperation?.await()
            }
            if (cancelledStop) {
                assertTrue(oldJob.isCancelled)
                assertSame(originalSession.stopCancellation, completion.await())
            } else if (operation == Operation.DROP) {
                assertFalse(oldJob.isCancelled)
                assertNull(completion.await())
            }

            val actual = TeardownOutcome(
                state = manager.state.value,
                generation = manager.connectGeneration,
                sameServices = manager.services === newServices,
                sameSession = manager.session === newSession,
                sameLink = manager.link === newLink,
                replacementClosed = newSession.closed,
                replacementStopped = newSession.stopped,
                replacementDisconnected = newLink.disconnected,
                wantsConnection = manager.wantsConnection,
                persistedUserDisconnect = intentStore.restore() == ConnectionIntent.UserDisconnected,
                originalClosed = originalSession.closed,
                originalDisconnected = originalLink.disconnected,
            )
            assertEquals(TeardownOutcome(ConnectionState.Ready(DEVICE_C), newGeneration), actual)
            assertEquals(1, originalSession.closeCount)
            assertEquals(1, originalLink.disconnectCount)
            assertEquals(0, newSession.closeCount)
            assertEquals(0, newLink.disconnectCount)
            if (operation == Operation.REBUILD) {
                assertFailsWith<CancellationException> { requireNotNull(oldOperation).await() }
            }
        }
    }

    private data class TeardownOutcome(
        val state: ConnectionState,
        val generation: Long,
        val sameServices: Boolean = true,
        val sameSession: Boolean = true,
        val sameLink: Boolean = true,
        val replacementClosed: Boolean = false,
        val replacementStopped: Boolean = false,
        val replacementDisconnected: Boolean = false,
        val wantsConnection: Boolean = true,
        val persistedUserDisconnect: Boolean = false,
        val originalClosed: Boolean = true,
        val originalDisconnected: Boolean = true,
    )

    private enum class Operation { DROP, DISCONNECT, CONNECT, KEEP_LINK, REBUILD }

    private companion object {
        const val DEVICE_A = "AA:BB:CC:DD:EE:71"
        const val DEVICE_B = "AA:BB:CC:DD:EE:72"
        const val DEVICE_C = "AA:BB:CC:DD:EE:73"
        const val PUBLIC_KEY_SIZE = 32
        const val ORIGINAL_KEY_BYTE: Byte = 1
        const val OTHER_KEY_BYTE: Byte = 2
        const val NOW_SECONDS = 1_700_000_000.0
        const val TIMEOUT_MS = 10_000L
    }
}

private class GatedStopSession(private val cancelledStop: Boolean) : FakeMeshSession() {
    var armed = false
    val entered = CompletableDeferred<Job>()
    val release = CompletableDeferred<Unit>()
    val stopCancellation = CancellationException("auto-fetch cancelled")

    override suspend fun stopAutoMessageFetching() {
        super.stopAutoMessageFetching()
        if (armed) {
            armed = false
            entered.complete(requireNotNull(currentCoroutineContext()[Job]))
            release.await()
            if (cancelledStop) throw stopCancellation
        }
    }
}
