package one.meshcore.shared.chat

import one.meshcore.shared.map.MapSnapshotRequest
import one.meshcore.shared.map.MapSnapshotStore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResolveMapPreviewReadyTest {
    @Test
    fun `disabled previews never ready`() {
        val store = MapSnapshotStore()
        val ready = resolveMapPreviewReady(
            latitude = 1.0,
            longitude = 2.0,
            envInputs = EnvInputs.DEFAULT.copy(showMapPreviews = false),
            store = store,
        )
        assertFalse(ready)
    }

    @Test
    fun `null coordinate never ready`() {
        assertFalse(
            resolveMapPreviewReady(
                latitude = null,
                longitude = 2.0,
                envInputs = EnvInputs.DEFAULT.copy(showMapPreviews = true),
                store = MapSnapshotStore(),
            ),
        )
    }

    @Test
    fun `without store coordinate is ready when previews on`() {
        assertTrue(
            resolveMapPreviewReady(
                latitude = 1.0,
                longitude = 2.0,
                envInputs = EnvInputs.DEFAULT.copy(showMapPreviews = true),
                store = null,
            ),
        )
    }

    @Test
    fun `with store request resolves immediately and reports ready`() {
        val store = MapSnapshotStore()
        val env = EnvInputs.DEFAULT.copy(
            showMapPreviews = true,
            isDark = true,
            mapBasemapIsDark = true,
            isOffline = false,
        )
        val ready = resolveMapPreviewReady(
            latitude = 37.77,
            longitude = -122.42,
            envInputs = env,
            store = store,
        )
        assertTrue(ready)
        val request = MapSnapshotRequest.rounded(37.77, -122.42, isDark = true, isOffline = false)
        assertTrue(store.isResolved(request))
        assertTrue(store.isReady(request))
    }

    @Test
    fun `snapshot readiness uses map appearance independently of chrome`() {
        for (mapDark in listOf(false, true)) {
            val store = MapSnapshotStore()
            val env = EnvInputs.DEFAULT.copy(showMapPreviews = true, isDark = !mapDark, mapBasemapIsDark = mapDark)
            assertTrue(resolveMapPreviewReady(37.77, -122.42, env, store))
            assertTrue(store.isReady(MapSnapshotRequest.rounded(37.77, -122.42, mapDark, false)))
            assertFalse(store.isResolved(MapSnapshotRequest.rounded(37.77, -122.42, !mapDark, false)))
        }
    }

    @Test
    fun `offline readiness uses the shared light offline cache key`() {
        val store = MapSnapshotStore()
        val env = EnvInputs.DEFAULT.copy(showMapPreviews = true, mapBasemapIsDark = true, isOffline = true)
        assertTrue(resolveMapPreviewReady(37.77, -122.42, env, store))
        assertTrue(store.isReady(MapSnapshotRequest.rounded(37.77, -122.42, false, true)))
        assertFalse(store.isResolved(MapSnapshotRequest.rounded(37.77, -122.42, true, false)))
    }
}
