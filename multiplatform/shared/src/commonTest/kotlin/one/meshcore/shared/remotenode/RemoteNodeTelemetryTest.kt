package one.meshcore.shared.remotenode

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.RemoteNodeSessionDto
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Telemetry and owner-info request-response on [RemoteNodeService]: success, timeout, and error mapping. */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteNodeTelemetryTest {
    @Test
    fun requestTelemetryReturnsResponseAndSendsToPublicKey() = runTestService { service, session, dto ->
        val response = service.requestTelemetry(dto.id)
        assertContentEquals(dto.publicKey.copyOf(6), response.publicKeyPrefix)
        assertContentEquals(dto.publicKey, session.requestTelemetryCalls.single())
    }

    @Test
    fun requestTelemetryMapsSeamErrorToSessionError() = runTestService(
        configure = { it.telemetryError = MeshError.DeviceError(5) },
    ) { service, _, dto ->
        val error = assertFailsWith<RemoteNodeError.SessionError> { service.requestTelemetry(dto.id) }
        assertEquals(MeshError.DeviceError(5), error.error)
    }

    @Test
    fun requestTelemetryUnknownSessionThrowsSessionNotFound() = runTestService { service, _, _ ->
        assertFailsWith<RemoteNodeError.SessionNotFound> { service.requestTelemetry(testUuid(404)) }
    }

    @Test
    fun requestOwnerInfoReturnsResponse() = runTestService(
        configure = { it.ownerInfoResponse = testOwnerInfoResponse(nodeName = "Repeater One") },
    ) { service, session, dto ->
        val response = service.requestOwnerInfo(dto.id)
        assertEquals("Repeater One", response.nodeName)
        assertContentEquals(dto.publicKey, session.requestOwnerInfoCalls.single())
    }

    @Test
    fun requestOwnerInfoMapsSeamErrorToSessionError() = runTestService(
        configure = { it.ownerInfoError = MeshError.NotConnected },
    ) { service, _, dto ->
        val error = assertFailsWith<RemoteNodeError.SessionError> { service.requestOwnerInfo(dto.id) }
        assertEquals(MeshError.NotConnected, error.error)
    }

    @Test
    fun requestTelemetryTimesOut() = runTest {
        val session = FakeNodeSession().apply { telemetryDelayMs = 100_000 }
        val store = InMemoryRemoteNodeStore()
        val dto = testSession()
        store.saveSession(dto)
        val service = RemoteNodeService(session, store, InMemoryNodePasswordStore(), backgroundScope)

        var thrown: Throwable? = null
        val job = launch { thrown = runCatching { service.requestTelemetry(dto.id) }.exceptionOrNull() }
        advanceUntilIdle()
        job.join()

        assertTrue(thrown is RemoteNodeError.Timeout)
    }

    private fun runTestService(
        configure: (FakeNodeSession) -> Unit = {},
        body: suspend (RemoteNodeService, FakeNodeSession, RemoteNodeSessionDto) -> Unit,
    ) = runTest {
        val session = FakeNodeSession().also(configure)
        val store = InMemoryRemoteNodeStore()
        val dto = testSession()
        store.saveSession(dto)
        val service = RemoteNodeService(session, store, InMemoryNodePasswordStore(), backgroundScope)
        body(service, session, dto)
    }
}
