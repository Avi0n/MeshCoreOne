package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteNodeCaptureLifecycleTest {
    @Test
    fun aBackgroundKeepAliveStopLeavesTheServiceReusable() = runTest {
        val store = InMemoryRemoteNodeStore()
        val dto = testSession(isConnected = true)
        store.saveSession(dto)
        val session = FakeNodeSession()
        val service = RemoteNodeService(session, store, InMemoryNodePasswordStore(), backgroundScope)
        service.stopAllKeepAlives()
        session.onSendLogin = { session.emitLoginSuccess(testLoginInfo(dto.publicKey, isAdmin = true)) }
        runCurrent()
        val login = backgroundScope.async { service.login(dto.id, password = TEST_PASSWORD) }
        runCurrent()
        assertEquals(RoomPermissionLevel.ADMIN, login.await().permissionLevel)
        assertTrue(store.fetchSessionById(dto.id)!!.isConnected)

        assertEquals(setOf(dto.id), service.handleBLEDisconnection())
        assertFalse(store.fetchSessionById(dto.id)!!.isConnected)
    }

    @Test
    fun aMonitorRestartLeavesDisconnectionCaptureUsable() = runTest {
        val store = InMemoryRemoteNodeStore()
        val dto = testSession(isConnected = true)
        store.saveSession(dto)
        val service = RemoteNodeService(FakeNodeSession(), store, InMemoryNodePasswordStore(), backgroundScope)
        service.stopEventMonitoring()
        service.startEventMonitoring()
        runCurrent()

        assertTrue(requireNotNull(service.eventMonitorJob).isActive)
        assertEquals(setOf(dto.id), service.handleBLEDisconnection())
        assertFalse(store.fetchSessionById(dto.id)!!.isConnected)
    }

    @Test
    fun callerCancellationStopsASuspendedCaptureWithoutDisconnectingItsRow() = runTest {
        val backing = InMemoryRemoteNodeStore()
        val dto = testSession(isConnected = true)
        backing.saveSession(dto)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = object : RemoteNodeStore by backing {
            override suspend fun markSessionDisconnected(id: Uuid) {
                entered.complete(Unit)
                release.await()
                backing.markSessionDisconnected(id)
            }
        }
        val service = RemoteNodeService(FakeNodeSession(), store, InMemoryNodePasswordStore(), backgroundScope)
        val operation = backgroundScope.async { service.handleBLEDisconnection() }
        val completion = CompletableDeferred<Throwable?>()
        operation.invokeOnCompletion { completion.complete(it) }
        runCurrent()
        assertTrue(entered.isCompleted)
        val cancellation = CancellationException("caller stopped")
        operation.cancel(cancellation)
        runCurrent()
        operation.join()
        assertSame(cancellation, completion.await())
        release.complete(Unit)
        runCurrent()

        assertTrue(backing.fetchSessionById(dto.id)!!.isConnected)
        assertTrue(backgroundScope.coroutineContext.job.isActive)
        assertTrue(requireNotNull(service.eventMonitorJob).isActive)
    }

    @Test
    fun aTerminalLateCaptureDoesNotReadWriteOrEmit() = runTest {
        val backing = InMemoryRemoteNodeStore()
        val dto = testSession(isConnected = true)
        backing.saveSession(dto)
        var reads = 0
        var writes = 0
        val store = object : RemoteNodeStore by backing {
            override suspend fun fetchConnectedSessions() = backing.fetchConnectedSessions().also { reads += 1 }

            override suspend fun markSessionDisconnected(id: Uuid) {
                writes += 1
                backing.markSessionDisconnected(id)
            }
        }
        val service = RemoteNodeService(FakeNodeSession(), store, InMemoryNodePasswordStore(), backgroundScope)
        val events = mutableListOf<RemoteNodeEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { service.events.collect { events += it } }
        service.retireConnectionOperations()
        service.retireConnectionOperations()

        assertEquals(emptySet(), service.handleBLEDisconnection())
        runCurrent()

        assertEquals(0, reads)
        assertEquals(0, writes)
        assertTrue(events.isEmpty())
        assertTrue(backing.fetchSessionById(dto.id)!!.isConnected)
        assertTrue(backgroundScope.coroutineContext.job.isActive)
        assertTrue(requireNotNull(service.eventMonitorJob).isActive)
    }

    @Test
    fun callerCancellationPrecedesACompletedTerminalSignal() = runTest {
        val backing = InMemoryRemoteNodeStore()
        var reads = 0
        val store = object : RemoteNodeStore by backing {
            override suspend fun fetchConnectedSessions() = backing.fetchConnectedSessions().also { reads += 1 }
        }
        val service = RemoteNodeService(FakeNodeSession(), store, InMemoryNodePasswordStore(), backgroundScope)
        service.retireConnectionOperations()
        val cancellation = CancellationException("caller already stopped")
        val observed = CompletableDeferred<Throwable?>()
        val operation = backgroundScope.async {
            currentCoroutineContext().job.cancel(cancellation)
            val failure = try {
                service.handleBLEDisconnection()
                null
            } catch (cancelled: CancellationException) {
                cancelled
            }
            observed.complete(failure)
        }
        runCurrent()
        operation.join()

        assertSame(cancellation, observed.await())
        assertTrue(operation.isCancelled)
        assertEquals(0, reads)
        assertTrue(backgroundScope.coroutineContext.job.isActive)
        assertTrue(requireNotNull(service.eventMonitorJob).isActive)
    }

    @Test
    fun retirementDuringTheFirstFetchRejectsItsLateRowsWithoutWritingOrEmitting() = runTest {
        val backing = InMemoryRemoteNodeStore()
        val dto = testSession(isConnected = true)
        backing.saveSession(dto)
        lateinit var service: RemoteNodeService
        lateinit var originatingOperation: Job
        var reads = 0
        var writes = 0
        val store = object : RemoteNodeStore by backing {
            override suspend fun fetchConnectedSessions(): List<RemoteNodeSessionDto> {
                reads += 1
                originatingOperation = currentCoroutineContext().job
                service.retireConnectionOperations()
                return listOf(dto)
            }

            override suspend fun markSessionDisconnected(id: Uuid) {
                writes += 1
                backing.markSessionDisconnected(id)
            }
        }
        service = RemoteNodeService(FakeNodeSession(), store, InMemoryNodePasswordStore(), backgroundScope)
        val events = mutableListOf<RemoteNodeEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { service.events.collect { events += it } }
        val operation = backgroundScope.async { service.handleBLEDisconnection() }
        runCurrent()
        operation.join()

        assertTrue(operation.isCancelled)
        assertTrue(originatingOperation.isCancelled)
        assertEquals(1, reads)
        assertEquals(0, writes)
        assertTrue(events.isEmpty())
        assertTrue(backing.fetchSessionById(dto.id)!!.isConnected)
        assertTrue(backgroundScope.coroutineContext.job.isActive)
        assertTrue(requireNotNull(service.eventMonitorJob).isActive)
    }

    private companion object {
        const val TEST_PASSWORD = "test-only-password"
    }
}
