package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import one.meshcore.shared.meshcore.NeighboursResponse
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.remotenode.FakeNodeSession
import one.meshcore.shared.remotenode.MeshError
import one.meshcore.shared.remotenode.NodeSession
import one.meshcore.shared.remotenode.testNeighbour
import one.meshcore.shared.remotenode.testNeighboursResponse
import one.meshcore.shared.remotenode.testSession
import one.meshcore.shared.room.FakeRoomAdminSession
import one.meshcore.shared.room.RoomAdminService
import one.meshcore.shared.room.roomSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteStatusLifetimeTest {
    @Test
    fun hidingKeepsPendingNeighborPollButSendsNoMorePolls() = runTest {
        val node = FakeNodeSession()
        val reply = CompletableDeferred<NeighboursResponse>()
        val pending = PendingNeighborSession(node)
        val harness = RemoteAdminTestHarness(backgroundScope, nodeSession = pending)
        val session = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        harness.store.seedSession(session)
        val vm = NodeStatusViewModel(
            backgroundScope,
            session,
            NodeStatusViewModel.Dependencies(
                repeaterAdminService = { harness.currentRepeater },
                nowEpochSeconds = { testScheduler.currentTime / 1_000.0 },
            ),
        )
        vm.setNeighborsExpanded(true)
        runCurrent()
        pending.reply = reply
        val initial = pending.calls
        vm.startDiscovery()
        runCurrent()
        node.emitCliResponse("OK")
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(initial + 1, pending.calls)
        vm.setNeighborsExpanded(false)
        reply.complete(testNeighboursResponse(totalCount = 1, neighbours = listOf(testNeighbour())))
        runCurrent()
        assertEquals(1, vm.state.value.neighbors.size)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(initial + 1, pending.calls)
        assertEquals(45, vm.state.value.discoverySecondsRemaining)
        vm.stopDiscovery()
    }

    @Test
    fun discoveryExpiresFromAbsolutePostAckDeadlineOnForeground() = runTest {
        val node = FakeNodeSession()
        val harness = RemoteAdminTestHarness(backgroundScope, nodeSession = node)
        val session = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        harness.store.seedSession(session)
        var now = 0.0
        val vm = NodeStatusViewModel(
            backgroundScope,
            session,
            NodeStatusViewModel.Dependencies(
                repeaterAdminService = { harness.currentRepeater },
                nowEpochSeconds = { now },
            ),
        )
        runCurrent()
        vm.startDiscovery()
        runCurrent()
        now = 30.0
        node.emitCliResponse("OK")
        runCurrent()
        vm.pauseDiscoveryPolls()
        now = 89.0
        vm.expireDiscoveryDeadline()
        assertTrue(vm.state.value.isDiscovering)
        now = 90.0
        vm.expireDiscoveryDeadline()
        assertFalse(vm.state.value.isDiscovering)
        assertEquals(0, vm.state.value.discoverySecondsRemaining)
    }

    @Test
    fun providerReplacementDiscardsPendingPollAndKeepsCountdown() = runTest {
        val node = FakeNodeSession()
        val reply = CompletableDeferred<NeighboursResponse>()
        val pending = PendingNeighborSession(node)
        val harness = RemoteAdminTestHarness(backgroundScope, nodeSession = pending)
        val session = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        harness.store.seedSession(session)
        val vm = NodeStatusViewModel(
            backgroundScope,
            session,
            NodeStatusViewModel.Dependencies(
                repeaterAdminService = { harness.currentRepeater },
                nowEpochSeconds = { testScheduler.currentTime / 1_000.0 },
            ),
        )
        runCurrent()
        pending.reply = reply
        vm.startDiscovery()
        runCurrent()
        node.emitCliResponse("OK")
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        harness.currentRepeater = null
        reply.complete(testNeighboursResponse(totalCount = 1, neighbours = listOf(testNeighbour())))
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(vm.state.value.isDiscovering)
        assertEquals(50, vm.state.value.discoverySecondsRemaining)
        assertTrue(vm.state.value.neighbors.isEmpty())
        assertNull(vm.state.value.neighborsSectionError)
        vm.stopDiscovery()
    }

    @Test
    fun repeaterRetryDoesNotReissueOnReplacedService() = runTest {
        val node = FakeNodeSession().apply { statusError = MeshError.DeviceError(10) }
        val harness = RemoteAdminTestHarness(backgroundScope, nodeSession = node)
        val session = testSession(isConnected = true)
        harness.store.seedSession(session)
        val vm = harness.workspaces.family(session).repeaterStatus()
        vm.setStatusExpanded(true)
        runCurrent()
        assertEquals(1, node.requestStatusCalls.size)
        harness.currentRepeater = null
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, node.requestStatusCalls.size)
        assertFalse(vm.state.value.isLoadingStatus)
        assertNull(vm.state.value.statusSectionError)
    }

    @Test
    fun roomRetryDoesNotReissueOnReplacedService() = runTest {
        val transport = FakeRoomAdminSession().apply { statusError = MeshError.DeviceError(10) }
        val harness = RemoteAdminTestHarness(backgroundScope, roomSession = transport)
        val session = roomSession()
        harness.store.seedSession(session)
        val vm = harness.workspaces.family(session).roomStatus()
        vm.requestStatus()
        runCurrent()
        assertEquals(1, transport.statusCalls.size)
        harness.currentRoom = RoomAdminService(FakeRoomAdminSession(), harness.store)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, transport.statusCalls.size)
        assertFalse(vm.state.value.isLoadingStatus)
        assertNull(vm.state.value.statusSectionError)
    }
}

private class PendingNeighborSession(private val backing: FakeNodeSession) : NodeSession by backing {
    var reply: CompletableDeferred<NeighboursResponse>? = null
    var calls = 0
    override suspend fun requestNeighbours(
        publicKey: ByteArray,
        count: Int,
        offset: Int,
        orderBy: Int,
        pubkeyPrefixLength: Int,
    ): NeighboursResponse {
        calls++
        val pending = reply
        return if (pending == null) {
            backing.requestNeighbours(publicKey, count, offset, orderBy, pubkeyPrefixLength)
        } else {
            withContext(NonCancellable) { pending.await() }
        }
    }
}
