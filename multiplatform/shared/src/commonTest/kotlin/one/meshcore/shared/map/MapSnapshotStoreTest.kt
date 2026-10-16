package one.meshcore.shared.map

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MapSnapshotStoreTest {
    @Test
    fun `request without renderer marks ready and isResolved`() {
        val store = MapSnapshotStore()
        val req = MapSnapshotRequest.rounded(37.77, -122.42, isDark = false, isOffline = false)
        assertFalse(store.isResolved(req))
        store.request(req)
        assertTrue(store.isReady(req))
        assertTrue(store.isResolved(req))
        assertFalse(store.isFailed(req))
        assertNull(store.imageBytes(req))
    }

    @Test
    fun `markFailed overrides ready`() {
        val store = MapSnapshotStore()
        val req = MapSnapshotRequest.rounded(1.0, 2.0, isDark = true, isOffline = true)
        store.markReady(req)
        store.markFailed(req)
        assertTrue(store.isFailed(req))
        assertFalse(store.isReady(req))
        assertTrue(store.isResolved(req))
    }

    @Test
    fun `rounded coordinates collapse jitter inside one metre and keep farther pins apart`() {
        val a = MapSnapshotRequest.rounded(37.7749101, -122.4194101, isDark = false, isOffline = false)
        val b = MapSnapshotRequest.rounded(37.7749104, -122.4194104, isDark = false, isOffline = false)
        assertEquals(a, b)
        val farther = MapSnapshotRequest.rounded(37.77501, -122.41941, isDark = false, isOffline = false)
        assertTrue(a != farther)
    }

    @Test
    fun `offline snapshots share one slot regardless of the dark preference`() {
        val dark = MapSnapshotRequest.rounded(1.0, 2.0, isDark = true, isOffline = true)
        val light = MapSnapshotRequest.rounded(1.0, 2.0, isDark = false, isOffline = true)
        assertEquals(dark, light)
        assertEquals(false, dark.isDark)
    }

    @Test
    fun `negative zero collapses so it does not shard the cache`() {
        val negative = MapSnapshotRequest.rounded(-0.0, -0.0, isDark = false, isOffline = false)
        val positive = MapSnapshotRequest.rounded(0.0, 0.0, isDark = false, isOffline = false)
        assertEquals(positive, negative)
        assertEquals(0.0, negative.latitude)
        assertEquals(0.0, negative.longitude)
    }

    @Test
    fun `clear empties caches`() {
        val store = MapSnapshotStore()
        val req = MapSnapshotRequest.rounded(0.0, 0.0, isDark = false, isOffline = false)
        store.request(req)
        store.clear()
        assertFalse(store.isResolved(req))
    }

    @Test
    fun `async renderer caches image bytes and resolves`() = runTest {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val store = MapSnapshotStore(
            renderer = MapSnapshotRendering { png },
            scope = this,
        )
        val req = MapSnapshotRequest.rounded(10.0, 20.0, isDark = false, isOffline = false)
        store.request(req)
        assertFalse(store.isResolved(req))
        advanceUntilIdle()
        assertTrue(store.isReady(req))
        assertTrue(store.isResolved(req))
        assertContentEquals(png, store.imageBytes(req))
    }

    @Test
    fun `async renderer null marks failed`() = runTest {
        val store = MapSnapshotStore(
            renderer = MapSnapshotRendering { null },
            scope = this,
        )
        val req = MapSnapshotRequest.rounded(11.0, 21.0, isDark = true, isOffline = false)
        store.request(req)
        advanceUntilIdle()
        assertTrue(store.isFailed(req))
        assertFalse(store.isReady(req))
        assertNull(store.imageBytes(req))
    }

    @Test
    fun `cancelled render can be requested again`() = runTest {
        var renders = 0
        val store = MapSnapshotStore(
            renderer = MapSnapshotRendering {
                renders++
                if (renders == 1) throw CancellationException("render cancelled")
                byteArrayOf(1, 2, 3)
            },
            scope = this,
        )
        val req = MapSnapshotRequest.rounded(11.0, 21.0, isDark = false, isOffline = false)
        store.request(req)
        advanceUntilIdle()
        assertFalse(store.isResolved(req))
        assertFalse(store.isFailed(req))

        store.request(req)
        advanceUntilIdle()
        assertEquals(2, renders)
        assertTrue(store.isReady(req))
    }

    @Test
    fun `retry clears failed so request can re-enqueue`() = runTest {
        val store = MapSnapshotStore(
            renderer = MapSnapshotRendering { null },
            scope = this,
        )
        val req = MapSnapshotRequest.rounded(12.0, 22.0, isDark = false, isOffline = true)
        store.request(req)
        advanceUntilIdle()
        assertTrue(store.isFailed(req))
        store.retry(req)
        assertFalse(store.isResolved(req))
        store.request(req)
        advanceUntilIdle()
        assertTrue(store.isFailed(req))
    }

    @Test
    fun `clearFailures drops failed so the next request can retry`() {
        val store = MapSnapshotStore()
        val req = MapSnapshotRequest.rounded(5.0, 6.0, isDark = false, isOffline = true)
        store.markFailed(req)
        assertTrue(store.isFailed(req))
        store.clearFailures()
        assertFalse(store.isResolved(req))
        store.request(req)
        assertTrue(store.isReady(req))
    }

    @Test
    fun `shouldClearMapSnapshotFailures is true only on the offline-to-online edge`() {
        assertTrue(shouldClearMapSnapshotFailures(wasOffline = true, isOffline = false))
        assertFalse(shouldClearMapSnapshotFailures(wasOffline = false, isOffline = false))
        assertFalse(shouldClearMapSnapshotFailures(wasOffline = true, isOffline = true))
        assertFalse(shouldClearMapSnapshotFailures(wasOffline = false, isOffline = true))
    }

    @Test
    fun `markReady with bytes stores image`() {
        val store = MapSnapshotStore()
        val req = MapSnapshotRequest.rounded(3.0, 4.0, isDark = false, isOffline = false)
        val bytes = byteArrayOf(1, 2, 3)
        store.markReady(req, bytes)
        assertTrue(store.isReady(req))
        assertNotNull(store.imageBytes(req))
        assertContentEquals(bytes, store.imageBytes(req))
    }
}
