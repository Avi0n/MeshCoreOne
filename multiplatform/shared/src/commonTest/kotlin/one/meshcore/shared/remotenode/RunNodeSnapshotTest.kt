package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class RunNodeSnapshotTest {
    @Test
    fun cancelledSnapshotReadKeepsTheOriginalCancellation() = runTest {
        val stopped = CancellationException("superseded")
        val thrown = assertFailsWith<CancellationException> {
            runNodeSnapshot(onFailure = { null }) { throw stopped }
        }
        assertSame(stopped, thrown)
    }

    @Test
    fun snapshotStoreFailureUsesTheCallersFallback() = runTest {
        val value = runNodeSnapshot(onFailure = { "unavailable" }) { error("db down") }
        assertEquals("unavailable", value)
    }

    @Test
    fun aThrownErrorStillPropagates() = runTest {
        val probe = SnapshotProbeError()
        val thrown = assertFailsWith<SnapshotProbeError> {
            runNodeSnapshot(onFailure = { Unit }) { throw probe }
        }
        assertSame(probe, thrown)
    }

    @Test
    fun aMissingSnapshotServiceHasNoPreviousStatus() = runTest {
        assertNull(previousStatusSnapshotOrNull(snapshots = null, nodePublicKey = ByteArray(0), before = 0.0))
    }

    private class SnapshotProbeError : Error("probe")
}
