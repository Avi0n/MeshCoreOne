package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.NeighborSnapshotEntry
import one.meshcore.shared.backup.TelemetrySnapshotEntry
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.FakeNodeSession
import one.meshcore.shared.remotenode.InMemoryNodeSnapshotStore
import one.meshcore.shared.remotenode.NodeLocationFix
import one.meshcore.shared.remotenode.NodeSnapshotService
import one.meshcore.shared.remotenode.NodeSnapshotStore
import one.meshcore.shared.remotenode.NodeStatusMetrics
import one.meshcore.shared.remotenode.testSession
import one.meshcore.shared.remotenode.testTelemetryResponse
import one.meshcore.shared.remotenode.voltagePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSectionSnapshotPublicationTest {
    @Test
    fun telemetryHistoryRefreshIsPublishedAfterTheSnapshotCommits() = runTest {
        val release = CompletableDeferred<Unit>()
        val backing = InMemoryNodeSnapshotStore()
        val snapshots = NodeSnapshotService(object : NodeSnapshotStore by backing {
            override suspend fun recordSnapshot(
                nodePublicKey: ByteArray,
                status: NodeStatusMetrics?,
                telemetry: List<TelemetrySnapshotEntry>?,
                neighbors: List<NeighborSnapshotEntry>?,
                location: NodeLocationFix?,
            ): Uuid {
                release.await()
                return backing.recordSnapshot(nodePublicKey, status, telemetry, neighbors, location)
            }
        })
        val node = FakeNodeSession().apply {
            telemetryResponse = testTelemetryResponse(points = listOf(voltagePoint()))
        }
        val harness = RemoteAdminTestHarness(backgroundScope, nodeSession = node)
        harness.currentSnapshots = snapshots
        val session = testSession(isConnected = true)
        harness.store.seedSession(session)
        val vm = harness.workspaces.family(session).repeaterStatus()
        assertEquals(0L, vm.state.value.snapshotVersion)
        vm.setTelemetryExpanded(true)
        runCurrent()
        assertTrue(vm.state.value.telemetryLoaded)
        assertTrue(snapshots.fetchSnapshots(session.publicKey).isEmpty())
        assertEquals(0L, vm.state.value.snapshotVersion)

        release.complete(Unit)
        runCurrent()
        assertEquals(1L, vm.state.value.snapshotVersion)
        assertEquals(1, snapshots.fetchSnapshots(session.publicKey).size)
        harness.workspaces.reset()
    }
}
