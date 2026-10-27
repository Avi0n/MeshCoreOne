// AndroidOnly: WP-218 real LocationManager-backed LocationProducing native adapter test.
// AndroidOnly: WP-404 Shared callback-cache ordering, retention and cancellation support.
// Robolectric's ShadowLocationManager.getCurrentLocation(...) shadows exactly the same 4-arg
// overload (`provider, CancellationSignal, Executor, Consumer<Location>`) this adapter calls, so
// `simulateLocation(Location)` genuinely exercises the real suspendCancellableCoroutine wiring
// below -- not a hand-mocked substitute for it.
package com.meshcoreone.android.app.content

import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import com.meshcoreone.android.core.services.content.GeoCoordinate
import com.meshcoreone.android.core.services.content.LocationAuthorizationStatus
import com.meshcoreone.android.core.services.content.LocationServiceError
import com.meshcoreone.android.core.services.content.LocationService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import java.time.Duration
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
class LocationManagerLocationProducingTest {
    private val context = org.robolectric.RuntimeEnvironment.getApplication()
    private val locationManager = context.getSystemService(LocationManager::class.java)!!

    private fun fix(latitude: Double, longitude: Double) = Location(LocationManager.FUSED_PROVIDER).apply {
        this.latitude = latitude
        this.longitude = longitude
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    @Test
    fun sharedCacheIsInitiallyAbsentAndPublishesEachOwnedFixBeforeResumption() = runTest {
        shadowOf(locationManager).setProviderEnabled(LocationManager.FUSED_PROVIDER, true)
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        LocationManagerLocationProducing(context).use { adapter ->
            assertNull(adapter.currentLocation)
            val service = LocationService(adapter)
            for (coordinate in listOf(GeoCoordinate(37.0, -122.0), GeoCoordinate(38.0, -121.0))) {
                // Age the framework shadow's own recent-fix shortcut, not the producer's shared cache.
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
                val pending = async { service.requestCurrentLocation() }
                testScheduler.runCurrent()
                assertFalse(pending.isCompleted)
                shadowOf(locationManager).simulateLocation(fix(coordinate.latitude, coordinate.longitude))
                assertEquals(coordinate, adapter.currentLocation)
                assertEquals(coordinate, pending.await())
            }
        }
    }

    @Test
    fun failedNullAndDeniedFixesRetainTheLastSuccessfulSharedCoordinate() = runTest {
        shadowOf(locationManager).setProviderEnabled(LocationManager.FUSED_PROVIDER, true)
        val application = context as android.app.Application
        shadowOf(application).grantPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        LocationManagerLocationProducing(context).use { adapter ->
            val first = async { adapter.requestLocation() }
            testScheduler.runCurrent()
            shadowOf(locationManager).simulateLocation(fix(37.0, -122.0))
            val saved = first.await()
            assertEquals(GeoCoordinate(37.0, -122.0), saved)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
            supervisorScope {
                val noFix = async { adapter.requestLocation() }
                testScheduler.runCurrent()
                assertFalse(noFix.isCompleted)
                // The real shadow's getCurrentLocation timeout delivers a null platform callback.
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
                assertFailsWith<LocationServiceError.RequestFailed> { noFix.await() }
            }
            assertEquals(saved, adapter.currentLocation)
            for (provider in listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                shadowOf(locationManager).setProviderEnabled(provider, false)
            }
            assertFailsWith<LocationServiceError.RequestFailed> { adapter.requestLocation() }
            assertEquals(saved, adapter.currentLocation)
            shadowOf(application).denyPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            adapter.refreshAuthorization()
            assertEquals(LocationAuthorizationStatus.DENIED, adapter.authorizationStatus.value)
            assertFailsWith<LocationServiceError.NotAuthorized> { adapter.requestLocation() }
            assertEquals(saved, adapter.currentLocation)
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun cancelledAndLatePlatformCallbacksCannotSeedOrReplaceTheSharedCache() = runTest {
        shadowOf(locationManager).setProviderEnabled(LocationManager.FUSED_PROVIDER, true)
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        LocationManagerLocationProducing(context).use { adapter ->
            val first = async { adapter.requestLocation() }
            testScheduler.runCurrent()
            val late = shadowOf(locationManager).getLocationUpdateListeners(LocationManager.FUSED_PROVIDER).single()
            first.cancel()
            assertFailsWith<CancellationException> { first.await() }
            late.onLocationChanged(fix(1.0, 2.0))
            assertNull(adapter.currentLocation)
            val next = async { adapter.requestLocation() }
            testScheduler.runCurrent()
            late.onLocationChanged(fix(8.0, 9.0))
            assertFalse(next.isCompleted)
            assertNull(adapter.currentLocation)
            shadowOf(locationManager).simulateLocation(fix(3.0, 4.0))
            assertEquals(GeoCoordinate(3.0, 4.0), adapter.currentLocation)
            assertEquals(GeoCoordinate(3.0, 4.0), next.await())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
            val cancelled = async { adapter.requestLocation() }
            testScheduler.runCurrent()
            val afterClose = shadowOf(locationManager).getLocationUpdateListeners(LocationManager.FUSED_PROVIDER).single()
            adapter.close()
            assertFailsWith<CancellationException> { cancelled.await() }
            afterClose.onLocationChanged(fix(5.0, 6.0))
            assertEquals(GeoCoordinate(3.0, 4.0), adapter.currentLocation)
        }
    }

    @Test
    fun `requestLocation resolves to the real coordinate delivered by the platform fix`() = runTest {
        shadowOf(locationManager).setProviderEnabled(LocationManager.FUSED_PROVIDER, true)
        shadowOf(context as android.app.Application).grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        )
        val adapter = LocationManagerLocationProducing(context)

        val deferred = async { adapter.requestLocation() }
        // `runTest`'s StandardTestDispatcher only *queues* `async`'s body rather than running it
        // inline, so without draining the scheduler here, `simulateLocation` below would fire
        // before `requestLocation()` ever reaches its real `getCurrentLocation` registration --
        // the fix would be delivered to no pending consumer and `deferred.await()` would hang.
        // `runCurrent()` genuinely executes the adapter up to its real suspension point first.
        testScheduler.runCurrent()
        val fix = Location(LocationManager.FUSED_PROVIDER).apply {
            latitude = 37.7749
            longitude = -122.4194
        }
        shadowOf(locationManager).simulateLocation(fix)
        val coordinate = deferred.await()

        assertEquals(37.7749, coordinate.latitude)
        assertEquals(-122.4194, coordinate.longitude)
    }

    @Test
    fun `requestLocation fails with a typed error when the provider is disabled`() = runTest {
        for (provider in listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            shadowOf(locationManager).setProviderEnabled(provider, false)
        }
        shadowOf(context as android.app.Application).grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        )
        val adapter = LocationManagerLocationProducing(context)

        assertFailsWith<LocationServiceError.RequestFailed> { adapter.requestLocation() }
    }

    @Test
    fun `requestLocation fails with NotAuthorized when no location permission is granted`() = runTest {
        shadowOf(locationManager).setProviderEnabled(LocationManager.FUSED_PROVIDER, true)
        val adapter = LocationManagerLocationProducing(context)

        val error = assertFailsWith<LocationServiceError.NotAuthorized> { adapter.requestLocation() }

        assertEquals(LocationAuthorizationStatus.NOT_DETERMINED, error.status)
        assertEquals(LocationAuthorizationStatus.NOT_DETERMINED, adapter.authorizationStatus.value)
    }

    @Test
    fun `authorizationStatus reports NOT_DETERMINED when no location permission is granted`() {
        val adapter = LocationManagerLocationProducing(context)

        assertEquals(LocationAuthorizationStatus.NOT_DETERMINED, adapter.authorizationStatus.value)
    }

    @Test
    fun `fine location grants foreground authorization rather than inventing background permission`() {
        // Simulates the real OS grant state the way Robolectric's shadow Application allows a
        // test to represent an already-granted permission, without going through an Activity
        // prompt -- distinct from this adapter's own disclosed requestPermissionIfNeeded gap.
        shadowOf(context as android.app.Application).grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        )
        val adapter = LocationManagerLocationProducing(context)

        assertEquals(LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE, adapter.authorizationStatus.value)
    }

    @Test
    fun `reportPermissionResult(false) surfaces DENIED when still not granted`() {
        val adapter = LocationManagerLocationProducing(context)

        adapter.reportPermissionResult(granted = false)

        assertEquals(LocationAuthorizationStatus.DENIED, adapter.authorizationStatus.value)
    }

    @Test
    fun `unwired permission requester fails explicitly without a 30 second permission spinner`() = runTest {
        val adapter = LocationManagerLocationProducing(context)
        val service = LocationService(adapter)

        assertFailsWith<LocationServiceError.PermissionRequestUnavailable> {
            service.requestCurrentLocation()
        }

        assertEquals(0L, testScheduler.currentTime)
        assertEquals(LocationAuthorizationStatus.NOT_DETERMINED, adapter.authorizationStatus.value)
    }

    @Test
    fun `explicit permission requester invokes the real capability contract once before its reported decision`() {
        var requests = 0
        val adapter = LocationManagerLocationProducing(
            context,
            permissionRequester = LocationPermissionRequesting { requests++ },
        )

        adapter.requestPermissionIfNeeded()
        assertEquals(1, requests)
        adapter.reportPermissionResult(granted = false)
        adapter.requestPermissionIfNeeded()
        assertEquals(1, requests)
        assertEquals(LocationAuthorizationStatus.DENIED, adapter.authorizationStatus.value)
    }

    @Test
    fun `permission revoked during a pending real fix is typed and releases the request`() = runTest {
        shadowOf(locationManager).setProviderEnabled(LocationManager.FUSED_PROVIDER, true)
        val application = context as android.app.Application
        shadowOf(application).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        LocationManagerLocationProducing(context).use { adapter ->
            supervisorScope {
                val pending = async { adapter.requestLocation() }
                testScheduler.runCurrent()
                shadowOf(application).denyPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
                adapter.reportPermissionResult(granted = false)
                val failure = assertFailsWith<LocationServiceError.NotAuthorized> { pending.await() }
                assertEquals(LocationAuthorizationStatus.DENIED, failure.status)
                assertEquals(LocationAuthorizationStatus.DENIED, adapter.authorizationStatus.value)
            }
            shadowOf(application).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
            adapter.reportPermissionResult(granted = true)
            val next = async { adapter.requestLocation() }
            testScheduler.runCurrent()
            shadowOf(locationManager).simulateLocation(Location(LocationManager.FUSED_PROVIDER).apply {
                latitude = 1.0
                longitude = 2.0
            })
            assertEquals(1.0, next.await().latitude)
        }
    }

    @Test
    fun `cancelling a pending platform fix does not complete a successor or leak its signal`() = runTest {
        shadowOf(locationManager).setProviderEnabled(LocationManager.FUSED_PROVIDER, true)
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        LocationManagerLocationProducing(context).use { adapter ->
            val first = async { adapter.requestLocation() }
            testScheduler.runCurrent()
            first.cancel()
            assertFailsWith<CancellationException> { first.await() }
            val next = async { adapter.requestLocation() }
            testScheduler.runCurrent()
            assertTrue(!next.isCompleted)
            shadowOf(locationManager).simulateLocation(Location(LocationManager.FUSED_PROVIDER).apply {
                latitude = 3.0
                longitude = 4.0
            })
            assertEquals(3.0, next.await().latitude)
        }
    }

    @Test
    fun `background authorization requires the actual background grant and close cancels the live request`() = runTest {
        shadowOf(locationManager).setProviderEnabled(LocationManager.FUSED_PROVIDER, true)
        shadowOf(context as android.app.Application).grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        val adapter = LocationManagerLocationProducing(context)
        assertEquals(LocationAuthorizationStatus.AUTHORIZED_ALWAYS, adapter.authorizationStatus.value)
        val pending = async { adapter.requestLocation() }
        testScheduler.runCurrent()
        adapter.close()
        adapter.close()
        assertFailsWith<CancellationException> { pending.await() }
        assertFailsWith<LocationServiceError.RequestFailed> { adapter.requestLocation() }
    }
}
