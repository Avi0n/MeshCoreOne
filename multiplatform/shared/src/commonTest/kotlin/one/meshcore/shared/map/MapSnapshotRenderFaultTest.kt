package one.meshcore.shared.map

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MapSnapshotRenderFaultTest {
    @Test
    fun aCancelledRenderIsNotAStickyFailure() = runTest {
        var renders = 0
        val png = byteArrayOf(1, 2)
        val store = MapSnapshotStore(
            renderer = MapSnapshotRendering {
                renders += 1
                if (renders == 1) throw CancellationException("stopped")
                png
            },
            scope = this,
        )
        val request = MapSnapshotRequest.rounded(10.0, 20.0, isDark = false, isOffline = false)
        store.request(request)
        advanceUntilIdle()
        assertFalse(store.isFailed(request))
        assertFalse(store.isResolved(request))
        store.request(request)
        advanceUntilIdle()
        assertEquals(2, renders)
        assertTrue(store.isReady(request))
        assertFalse(store.isFailed(request))
    }

    @Test
    fun aRenderExceptionStaysAFailure() = runTest {
        val store = MapSnapshotStore(
            renderer = MapSnapshotRendering { error("gl lost") },
            scope = this,
        )
        val request = MapSnapshotRequest.rounded(11.0, 21.0, isDark = false, isOffline = false)
        store.request(request)
        advanceUntilIdle()
        assertTrue(store.isFailed(request))
        assertFalse(store.isReady(request))
    }

    @Test
    fun aRenderErrorIsNotAStickyFailure() = runTest {
        val thrown = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() +
                UnconfinedTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> thrown += error },
        )
        val store = MapSnapshotStore(
            renderer = MapSnapshotRendering { throw RenderProbeError() },
            scope = scope,
        )
        val request = MapSnapshotRequest.rounded(12.0, 22.0, isDark = true, isOffline = false)
        try {
            store.request(request)
            assertIs<RenderProbeError>(thrown.single())
            assertFalse(store.isFailed(request))
            assertFalse(store.isResolved(request))
        } finally {
            scope.cancel()
        }
    }

    private class RenderProbeError : Error("probe")
}
