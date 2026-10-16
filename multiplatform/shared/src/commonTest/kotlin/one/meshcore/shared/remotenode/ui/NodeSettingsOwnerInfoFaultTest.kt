package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.remotenode.FakeNodeSession
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.InMemoryRemoteNodeStore
import one.meshcore.shared.remotenode.NodeCliCoordinator
import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.remotenode.RepeaterAdminService
import one.meshcore.shared.remotenode.testContact
import one.meshcore.shared.remotenode.testOwnerInfoResponse
import one.meshcore.shared.remotenode.testSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Owner-info prefetch failures stay on [NodeSettingsModel.fetchNodeInfo]. An ordinary failure leaves
 * the fields blank. Cancellation and an error propagate, and a later fetch can still fill them.
 */
class NodeSettingsOwnerInfoFaultTest {
    @Test
    fun anOrdinaryOwnerInfoFailureLeavesFirmwareUnset() = runTest {
        val harness = connectedHarness(backgroundScope)
        harness.nodeSession.ownerInfoError = IllegalStateException("offline")
        val model = settingsModel(backgroundScope, harness)

        model.fetchNodeInfo()

        assertNull(model.state.value.firmwareVersion)
        harness.nodeSession.ownerInfoError = null
        harness.nodeSession.ownerInfoResponse = testOwnerInfoResponse(firmwareVersion = "v1.15.0")
        model.fetchNodeInfo()
        assertEquals("v1.15.0", model.state.value.firmwareVersion)
    }

    @Test
    fun aCancelledOwnerInfoReadPropagates() = runTest {
        val cancelled = CancellationException("superseded")
        val harness = connectedHarness(backgroundScope)
        harness.nodeSession.ownerInfoError = cancelled
        val model = settingsModel(backgroundScope, harness)

        val thrown = assertFailsWith<CancellationException> { model.fetchNodeInfo() }

        // The binary timeout copies a cancelled owner-info read before this catch sees it.
        assertEquals(cancelled.message, thrown.message)
        assertNull(model.state.value.firmwareVersion)
        harness.nodeSession.ownerInfoError = null
        harness.nodeSession.ownerInfoResponse = testOwnerInfoResponse(firmwareVersion = "v1.15.0")
        model.fetchNodeInfo()
        assertEquals("v1.15.0", model.state.value.firmwareVersion)
    }

    @Test
    fun anOwnerInfoErrorPropagates() = runTest {
        val probe = OwnerInfoFetchProbeError()
        val harness = connectedHarness(backgroundScope)
        harness.nodeSession.ownerInfoError = probe
        val model = settingsModel(backgroundScope, harness)

        val thrown = assertFailsWith<OwnerInfoFetchProbeError> { model.fetchNodeInfo() }

        assertSame(probe, thrown)
        assertNull(model.state.value.firmwareVersion)
        harness.nodeSession.ownerInfoError = null
        harness.nodeSession.ownerInfoResponse = testOwnerInfoResponse(firmwareVersion = "v1.15.0")
        model.fetchNodeInfo()
        assertEquals("v1.15.0", model.state.value.firmwareVersion)
    }

    private fun settingsModel(scope: CoroutineScope, harness: OwnerInfoHarness) = NodeSettingsModel(
        scope = scope,
        sessionId = harness.session.id,
        dependencies = NodeSettingsViewModel.Dependencies { harness.admin },
        nowEpochSeconds = { 0 },
    )
}

private class OwnerInfoFetchProbeError : Error("probe")

private suspend fun TestScope.connectedHarness(serviceScope: CoroutineScope): OwnerInfoHarness {
    val nodeSession = FakeNodeSession()
    val store = InMemoryRemoteNodeStore()
    val passwords = InMemoryNodePasswordStore()
    val session = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
    store.saveSession(session)
    store.setContact(session.radioID, testContact(publicKey = session.publicKey))
    store.setContactRouting(session.radioID, session.publicKey, isFloodRouted = true)
    val admin = RepeaterAdminService(
        RemoteNodeService(nodeSession, store, passwords, serviceScope),
        NodeCliCoordinator(nodeSession, store, passwords, serviceScope),
        nodeSession,
        store,
        passwords,
    )
    return OwnerInfoHarness(nodeSession, admin, session)
}

private class OwnerInfoHarness(
    val nodeSession: FakeNodeSession,
    val admin: RepeaterAdminService,
    val session: one.meshcore.shared.backup.RemoteNodeSessionDto,
)
