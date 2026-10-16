package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.remotenode.FakeNodeSession
import one.meshcore.shared.remotenode.testSession
import one.meshcore.shared.room.FakeRoomAdminSession
import one.meshcore.shared.room.roomSession
import one.meshcore.shared.room.ui.loadOpenSections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSectionLazyLoadTest {
    @Test
    fun managementModelsAndMountLoadsLeaveCollapsedSectionsQuiet() = runTest {
        val node = FakeNodeSession()
        val room = FakeRoomAdminSession()
        val harness = RemoteAdminTestHarness(backgroundScope, nodeSession = node, roomSession = room)
        val repeaterSession = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        val roomSession = roomSession(permissionLevel = RoomPermissionLevel.ADMIN)
        harness.store.seedSession(repeaterSession)
        harness.store.seedSession(roomSession)
        val repeater = harness.workspaces.family(repeaterSession)
        val roomFamily = harness.workspaces.family(roomSession)
        repeater.repeaterSettings().loadOpenSections()
        roomFamily.roomSettings().loadOpenSections()
        val nodeStatus = repeater.repeaterStatus()
        val roomStatus = roomFamily.roomStatus()
        runCurrent()

        assertFalse(nodeStatus.state.value.statusExpanded)
        assertFalse(nodeStatus.state.value.telemetryExpanded)
        assertFalse(roomStatus.state.value.statusExpanded)
        assertFalse(roomStatus.state.value.telemetryExpanded)
        assertEquals(0, node.requestOwnerInfoCalls.size)
        assertEquals(0, node.requestStatusCalls.size)
        assertEquals(0, node.requestTelemetryCalls.size)
        assertEquals(0, node.requestNeighboursCalls.size)
        assertEquals(0, node.sendCommandCalls.size)
        assertEquals(0, room.statusCalls.size)
        assertEquals(0, room.telemetryCalls.size)
        assertEquals(0, room.commandCalls.size)
        harness.workspaces.reset()
    }

    @Test
    fun expandingRepeaterStatusLeavesItsRemoteSiblingsQuiet() = runTest {
        val node = FakeNodeSession()
        val harness = RemoteAdminTestHarness(backgroundScope, nodeSession = node)
        val session = testSession(isConnected = true)
        harness.store.seedSession(session)
        val vm = harness.workspaces.family(session).repeaterStatus()
        vm.setStatusExpanded(true)
        runCurrent()
        assertTrue(vm.state.value.statusLoaded)
        assertEquals(1, node.requestStatusCalls.size)
        assertEquals(0, node.requestTelemetryCalls.size)
        assertEquals(0, node.requestOwnerInfoCalls.size)
        assertEquals(0, node.requestNeighboursCalls.size)
        vm.setStatusExpanded(false)
        vm.setStatusExpanded(true)
        runCurrent()
        assertEquals(1, node.requestStatusCalls.size)
        harness.workspaces.reset()
    }

    @Test
    fun expandingRoomTelemetryLeavesStatusQuiet() = runTest {
        val room = FakeRoomAdminSession()
        val harness = RemoteAdminTestHarness(backgroundScope, roomSession = room)
        val session = roomSession()
        harness.store.seedSession(session)
        val vm = harness.workspaces.family(session).roomStatus()
        vm.setTelemetryExpanded(true)
        runCurrent()
        assertTrue(vm.state.value.telemetryLoaded)
        assertEquals(1, room.telemetryCalls.size)
        assertEquals(0, room.statusCalls.size)
        vm.setTelemetryExpanded(false)
        vm.setTelemetryExpanded(true)
        runCurrent()
        assertEquals(1, room.telemetryCalls.size)
        harness.workspaces.reset()
    }

    @Test
    fun contactTelemetryStartsCollapsedAndRequestsOnlyAfterExpansion() = runTest {
        var calls = 0
        val vm = NodeTelemetryViewModel(
            backgroundScope,
            ByteArray(32),
            NodeTelemetryViewModel.Dependencies(requestTelemetry = {
                {
                    calls++
                    one.meshcore.shared.remotenode.testTelemetryResponse()
                }
            }),
        )
        runCurrent()
        assertFalse(vm.state.value.expanded)
        assertFalse(vm.state.value.loaded)
        assertEquals(0, calls)
        vm.setExpanded(true)
        runCurrent()
        assertTrue(vm.state.value.loaded)
        assertEquals(1, calls)
        vm.setExpanded(false)
        vm.setExpanded(true)
        runCurrent()
        assertEquals(1, calls)
        vm.close()
    }
}
