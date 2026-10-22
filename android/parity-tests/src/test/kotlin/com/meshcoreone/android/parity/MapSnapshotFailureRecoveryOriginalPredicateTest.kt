// PortedFrom: MC1Tests/Services/MapSnapshotStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Adaptation: WP-501 Drive the same-key failure invalidation through the real App network consumer.
package com.meshcoreone.android.parity

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.container.AppChatMapPreviews
import com.meshcoreone.android.app.container.ChatMapNetwork
import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.core.maps.MapSnapshotRenderer
import com.meshcoreone.android.core.maps.MapSnapshotRequest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class MapSnapshotFailureRecoveryOriginalPredicateTest {
    @OriginalCase("MapSnapshotStoreTests::clearFailures() drops failed entries so the next request triggers a retry()")
    @Test fun originalFailedKeyEmitsInvalidationAndRendersAgainThroughTheAppOnlineEdge() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val request = MapSnapshotRequest(37.0, -122.0, isDark = false, isOffline = false)
        val requests = mutableListOf<MapSnapshotRequest>()
        var shouldFail = true
        var online = false
        val image = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val previews = AppChatMapPreviews(context, backgroundScope, object : MapSnapshotRenderer<Bitmap> {
            override suspend fun render(request: MapSnapshotRequest): Bitmap? {
                requests += request
                return if (shouldFail) null else image
            }
        }, online = { online })
        val store = previews.snapshots
        try {
            assertEquals(ChatMapNetwork.Offline, previews.network.value)
            val failed = async(start = CoroutineStart.UNDISPATCHED) { store.resolutions.first() }
            previews.request(request)
            runCurrent()
            assertEquals(request, withTimeout(1_000) { failed.await() })
            assertTrue(store.isResolved(request))
            assertNull(store.value(request))
            assertEquals(listOf(request), requests)

            val invalidated = async(start = CoroutineStart.UNDISPATCHED) { store.resolutions.first() }
            online = true
            previews.refreshNetwork()
            assertEquals(request, withTimeout(1_000) { invalidated.await() })
            assertEquals(ChatMapNetwork.Online, previews.network.value)
            assertFalse(store.isResolved(request))
            assertNull(store.value(request))
            assertEquals(listOf(request), requests, "Invalidation must not manufacture a render")

            shouldFail = false
            val retried = async(start = CoroutineStart.UNDISPATCHED) { store.resolutions.first() }
            previews.request(request)
            runCurrent()
            assertEquals(request, withTimeout(1_000) { retried.await() })
            val cached = assertNotNull(store.value(request))
            assertSame(image, cached)
            assertEquals(Color.RED, cached.getPixel(0, 0))
            assertTrue(store.isResolved(request))
            assertEquals(listOf(request, request), requests, "Retry must use the original key, not an online/offline replacement")
        } finally {
            previews.close()
            runCurrent()
        }
        assertFalse(store.isResolved(request))
        assertNull(store.value(request))
    }
}
