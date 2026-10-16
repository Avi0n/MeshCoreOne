package one.meshcore.shared.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferencesStore
import one.meshcore.shared.transport.LinkDropReason
import one.meshcore.shared.transport.MeshLink
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionLinkDropOwnershipTest {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-drop-ownership", ".db").also { it.delete() }
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
    fun aBondLostReadCannotRearmAfterAForcedConnection() = runBlocking(dispatcher) {
        verifySuspendedDrop(GateRead.LAST_DEVICE, LinkDropReason.BOND_LOST, Replacement.SAME_RADIO)
    }

    @Test
    fun aLinkLostReadCannotInterfereWithAnotherRadio() = runBlocking(dispatcher) {
        verifySuspendedDrop(GateRead.LAST_DEVICE, LinkDropReason.CONNECTION_LOST, Replacement.OTHER_RADIO)
    }

    @Test
    fun aBondLostReadCannotRearmAfterUserDisconnect() = runBlocking(dispatcher) {
        verifySuspendedDrop(GateRead.LAST_DEVICE, LinkDropReason.BOND_LOST, Replacement.DISCONNECT)
    }

    @Test
    fun aBondLostReadCannotInterfereWithAnotherRadio() = runBlocking(dispatcher) {
        verifySuspendedDrop(GateRead.LAST_DEVICE, LinkDropReason.BOND_LOST, Replacement.OTHER_RADIO)
    }

    @Test
    fun aSuspendedDiagnosticCannotTearDownAnotherRadio() = runBlocking(dispatcher) {
        verifySuspendedDrop(GateRead.DIAGNOSTIC, LinkDropReason.BOND_LOST, Replacement.OTHER_RADIO)
    }

    @Test
    fun anUnsupersededBondLossStillArmsTheWatchdog() = runBlocking(dispatcher) {
        verifySuspendedDrop(GateRead.LAST_DEVICE, LinkDropReason.BOND_LOST, Replacement.NONE)
    }

    @Test
    fun anUnsupersededLinkLossStillArmsTheWatchdog() = runBlocking(dispatcher) {
        verifySuspendedDrop(GateRead.LAST_DEVICE, LinkDropReason.CONNECTION_LOST, Replacement.NONE)
    }

    private suspend fun verifySuspendedDrop(gateRead: GateRead, reason: LinkDropReason, replacement: Replacement) {
        withTimeout(TIMEOUT_MS) {
            val prefs = GatedDropPreferences(gateRead)
            val store = LastConnectionStore(prefs)
            val links = mutableListOf<FakeMeshLink>()
            val linkHandles = mutableMapOf<MeshLink, String>()
            val sessions = mutableListOf<FakeMeshSession>()
            var reconnectCalls = 0
            val reconnectEntered = CompletableDeferred<Unit>()
            val unexpectedReconnect = FakeMeshLink().apply { connectGate = CompletableDeferred() }
            val manager = ConnectionManager(
                database = db,
                scope = scope,
                linkFactory = { handle ->
                    FakeMeshLink().also {
                        links += it
                        linkHandles[it] = handle
                    }
                },
                sessionFactory = { link ->
                    val keyByte = if (linkHandles[link] == DEVICE_B) OTHER_KEY_BYTE else INITIAL_KEY_BYTE
                    FakeMeshSession(selfInfo = defaultSelfInfo(ByteArray(PUBLIC_KEY_SIZE) { keyByte }))
                        .also(sessions::add)
                },
                nowEpochSeconds = { NOW_SECONDS },
                ports = ConnectionManagerPorts(
                    lastConnectionStore = store,
                    reconnectLinkFactory = {
                        reconnectCalls++
                        reconnectEntered.complete(Unit)
                        unexpectedReconnect
                    },
                ),
            )
            manager.connect(DEVICE_A)
            assertEquals(ConnectionState.Ready(DEVICE_A), manager.state.value)
            assertNotNull(store.deviceId())
            val originalLink = links.single()
            val originalSession = sessions.single()
            prefs.armed = true
            originalLink.drop(reason)
            val dropJob = prefs.entered.await()
            if (gateRead == GateRead.LAST_DEVICE) {
                assertTrue(originalSession.closed)
                assertTrue(originalLink.disconnected)
                assertNull(manager.services)
                if (reason == LinkDropReason.BOND_LOST) {
                    assertEquals(ConnectionState.BondLost(DEVICE_A), manager.state.value)
                }
            }
            val parkedGeneration = manager.connectGeneration
            when (replacement) {
                Replacement.SAME_RADIO -> manager.connect(DEVICE_A, forceReconnect = true)
                Replacement.OTHER_RADIO -> manager.connect(DEVICE_B, forceReconnect = true)
                Replacement.DISCONNECT -> manager.disconnect()
                Replacement.NONE -> Unit
            }
            if (replacement != Replacement.NONE) assertTrue(manager.connectGeneration > parkedGeneration)
            val replacementState = if (replacement == Replacement.DISCONNECT) {
                ConnectionState.Disconnected
            } else if (replacement == Replacement.NONE) {
                manager.state.value
            } else {
                ConnectionState.Ready(if (replacement == Replacement.SAME_RADIO) DEVICE_A else DEVICE_B)
            }
            assertEquals(replacementState, manager.state.value)
            assertNull(manager.reconnectJob)
            val newServices = manager.services
            val newSession = manager.session
            val newLink = manager.link
            val newGeneration = manager.connectGeneration
            prefs.release.complete(Unit)
            dropJob.join()

            if (replacement == Replacement.NONE) {
                reconnectEntered.await()
                assertEquals(1, reconnectCalls)
                assertTrue(manager.isBleReconnectInProgress())
                assertTrue(originalSession.closed)
                assertTrue(originalLink.disconnected)
                return@withTimeout
            }

            val actual = DropOutcome(
                state = manager.state.value,
                generation = manager.connectGeneration,
                sameServices = manager.services === newServices,
                sameSession = manager.session === newSession,
                sameLink = manager.link === newLink,
                watchdogPresent = manager.reconnectJob != null,
                reconnectCalls = reconnectCalls,
                replacementClosed = (newSession as? FakeMeshSession)?.closed ?: false,
                replacementStopped = (newSession as? FakeMeshSession)?.stopped ?: false,
                replacementDisconnected = (newLink as? FakeMeshLink)?.disconnected ?: false,
            )
            assertEquals(DropOutcome(replacementState, newGeneration), actual)
            assertFalse(manager.isBleReconnectInProgress())
        }
    }

    private data class DropOutcome(
        val state: ConnectionState,
        val generation: Long,
        val sameServices: Boolean = true,
        val sameSession: Boolean = true,
        val sameLink: Boolean = true,
        val watchdogPresent: Boolean = false,
        val reconnectCalls: Int = 0,
        val replacementClosed: Boolean = false,
        val replacementStopped: Boolean = false,
        val replacementDisconnected: Boolean = false,
    )

    private enum class Replacement { SAME_RADIO, OTHER_RADIO, DISCONNECT, NONE }

    private companion object {
        const val DEVICE_A = "AA:BB:CC:DD:EE:71"
        const val DEVICE_B = "AA:BB:CC:DD:EE:72"
        const val NOW_SECONDS = 1_700_000_000.0
        const val TIMEOUT_MS = 10_000L
        const val PUBLIC_KEY_SIZE = 32
        const val INITIAL_KEY_BYTE: Byte = 1
        const val OTHER_KEY_BYTE: Byte = 2
    }
}

private enum class GateRead { LAST_DEVICE, DIAGNOSTIC }

private class GatedDropPreferences(
    private val gateRead: GateRead,
    private val delegate: PreferencesStore = InMemoryPreferencesStore(),
) : PreferencesStore by delegate {
    var armed = false
    val entered = CompletableDeferred<Job>()
    val release = CompletableDeferred<Unit>()

    override suspend fun getString(key: String): String? {
        if (gateRead == GateRead.LAST_DEVICE && key == PersistenceKeys.LAST_CONNECTED_DEVICE_ID) pauseOnce()
        return delegate.getString(key)
    }

    override suspend fun putString(key: String, value: String) {
        if (gateRead == GateRead.DIAGNOSTIC && key == PersistenceKeys.LAST_DISCONNECT_DIAGNOSTIC) pauseOnce()
        delegate.putString(key, value)
    }

    private suspend fun pauseOnce() {
        if (armed) {
            armed = false
            entered.complete(requireNotNull(currentCoroutineContext()[Job]))
            release.await()
        }
    }
}
