// PortedFrom: MC1Tests/Intents/SendAdvertIntentTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/State/AppState+DeviceActions.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Services/LocationService.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.content.LocationManagerLocationProducing
import com.meshcoreone.android.app.content.LocationPermissionRequesting
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.datastore.DevicePreferenceSnapshot
import com.meshcoreone.android.core.datastore.GPSSource
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.content.GeoCoordinate
import com.meshcoreone.android.core.services.content.GeocodeResult
import com.meshcoreone.android.core.services.content.Geocoder
import com.meshcoreone.android.core.services.content.LocationAuthorizationStatus
import com.meshcoreone.android.core.services.content.LocationProducing
import com.meshcoreone.android.core.services.content.LocationServiceError
import com.meshcoreone.android.core.services.content.LocationService
import com.meshcoreone.android.core.services.content.RegionResolver
import com.meshcoreone.android.platform.shortcuts.AndroidShortcutText
import com.meshcoreone.android.platform.shortcuts.ShortcutError
import com.meshcoreone.android.platform.shortcuts.ShortcutException
import com.meshcoreone.android.platform.shortcuts.AdvertFlow
import com.meshcoreone.android.platform.shortcuts.AdvertReach
import com.meshcoreone.android.platform.shortcuts.AdvertResult
import com.meshcoreone.android.platform.shortcuts.ConfirmationRequest
import com.meshcoreone.android.platform.shortcuts.ShortcutConfirmationPort
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.robolectric.Shadows.shadowOf

class ShortcutAdvertLocationTest : RoomProcessTest() {
    private class Producing : LocationProducing {
        override val authorizationStatus = MutableStateFlow(LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE)
        var permissionRequests = 0
        var locationRequests = 0
        var obtain: suspend () -> GeoCoordinate = { GeoCoordinate(37.0, -122.0) }
        override fun requestPermissionIfNeeded() { permissionRequests++ }
        override suspend fun requestLocation(): GeoCoordinate {
            locationRequests++
            return obtain()
        }
    }

    private class Preferences {
        val values = mutableMapOf<UUID, DevicePreferenceSnapshot>()
        val reads = mutableListOf<UUID>()
        suspend fun read(id: UUID): DevicePreferenceSnapshot {
            reads += id
            return values[id] ?: DevicePreferenceSnapshot(false, null, GPSSource.PHONE)
        }
        fun enable(device: DeviceDTO, source: GPSSource) {
            values[device.id] = DevicePreferenceSnapshot(true, source.rawValue, source)
        }
    }

    private class RadioLocation(private val advertLocationPolicy: UByte = 0u) {
        var gpsEnabled = false
        var gpsFailure = false
        var latitudeE6 = 0L
        var longitudeE6 = 0L
        fun respond(frame: Bytes): List<Bytes>? = when (frame[0].toInt()) {
            7 -> listOf(Bytes.of(0))
            0x28 -> if (gpsFailure) listOf(Bytes.of(1, 1)) else {
                listOf(Bytes.of(0x15) + Bytes.utf8("gps:${if (gpsEnabled) 1 else 0}"))
            }
            0x29 -> {
                check(frame == Bytes.of(0x29) + Bytes.utf8("gps:1"))
                gpsEnabled = true
                listOf(Bytes.of(0))
            }
            0x0e -> {
                val data = ByteBuffer.wrap(frame.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
                data.get()
                latitudeE6 = data.int.toLong()
                longitudeE6 = data.int.toLong()
                listOf(Bytes.of(0))
            }
            1 -> listOf(
                Bytes.of(5, 1, 22, 22) + key(7) + le32(latitudeE6) + le32(longitudeE6) +
                    Bytes.of(0, advertLocationPolicy.toInt(), 2, 0) +
                    le32(915_000) + le32(250_000) + Bytes.of(10, 5) + Bytes.utf8("TestNode"),
            )
            else -> null
        }
    }

    private suspend fun connected(
        test: TestScope,
        preparation: AppAdvertLocation,
        radioLocation: RadioLocation = RadioLocation(),
    ): ContainerHarness {
        val h = ContainerHarness(test, store, advertLocation = preparation)
        h.links.configureRadio = { it.respond = radioLocation::respond }
        h.manager.connect(h.target())
        h.eventually("pre-advert fixture READY") { h.manager.connectionState == DeviceConnectionState.READY }
        h.settle()
        h.eventually("startup battery bootstrap complete") {
            !h.appState.batteryMonitor.isBootstrapActive && h.appState.batteryMonitor.isRefreshLoopActive
        }
        val startup = h.links.radios.single().sent.size
        h.appState.batteryMonitor.fetchDeviceBattery(assertNotNull(h.sessions.current).batteryServices, h.appState.connectedDevice)
        assertEquals(listOf(0x14), commands(h, startup), "Complete real startup battery phase before the advert baseline")
        val monitoringJobs = assertNotNull(test.backgroundScope.coroutineContext[Job]).children.toList()
        val stopping = h.links.radios.single().sent.size
        h.appState.batteryMonitor.stop()
        val cancelled = monitoringJobs.filter { it.isCancelled }
        assertTrue(cancelled.isNotEmpty(), "Stop must cancel the real background refresh job")
        cancelled.forEach { it.join() }
        assertFalse(h.appState.batteryMonitor.isBootstrapActive)
        assertFalse(h.appState.batteryMonitor.isRefreshLoopActive)
        assertTrue(commands(h, stopping).isEmpty(), "Joining unrelated polling must not send an action command")
        return h
    }

    private fun share(h: ContainerHarness, policy: UByte = 1u): DeviceDTO =
        assertNotNull(h.appState.connectedDevice).copy(advertLocationPolicy = policy).also {
            h.manager.replaceConnectedDevice(it)
        }

    private fun commands(h: ContainerHarness, start: Int): List<Int> =
        h.links.radios.single().sent.drop(start).map { it[0].toInt() }

    @OriginalCase("SendAdvertIntentTests::gps gate is nil without A device()")
    @Test fun noDeviceAndNonPublicPolicyNeverReadPreferencesOrRefresh() = runTest {
        val preferences = Preferences()
        val producing = Producing()
        val preparation = AppAdvertLocation(preferences::read, producing,
            currentLocation = { error("Non-public policy must not read a cached location") })
        assertNull(preparation.gpsSource(null))
        val h = connected(this, preparation)
        try {
            val device = share(h, 0u)
            preferences.enable(device, GPSSource.DEVICE)
            assertNull(preparation.gpsSource(device))
            val start = h.links.radios.single().sent.size
            AppShortcutRadio(h.container).sendAdvert(false)
            assertEquals(listOf(7), commands(h, start))
            assertTrue(preferences.reads.isEmpty())
            assertEquals(0, producing.locationRequests)
            assertEquals(0, producing.permissionRequests)
        } finally { h.container.close() }
    }

    @Test fun disconnectedAdvertDoesNotReadPreferencesOrRequestLocation() = runTest {
        val preferences = Preferences()
        val producing = Producing()
        val h = ContainerHarness(this, store, advertLocation = AppAdvertLocation(preferences::read, producing))
        try {
            assertEquals(ShortcutError.NOT_CONNECTED,
                assertFailsWith<ShortcutException> { AppShortcutRadio(h.container).sendAdvert(false) }.error)
            assertTrue(preferences.reads.isEmpty())
            assertTrue(h.links.radios.isEmpty())
            assertEquals(0, producing.locationRequests)
            assertEquals(0, producing.permissionRequests)
        } finally { h.container.close() }
    }

    @OriginalCase("SendAdvertIntentTests::gps gate respects the per device preference()")
    @Test fun originalPerDeviceGatePreservesAllThreePinnedPredicates() = runTest {
        val preferences = Preferences()
        val preparation = AppAdvertLocation(preferences::read, Producing())
        val h = connected(this, preparation)
        try {
            val device = share(h)
            assertNull(preparation.gpsSource(device))
            preferences.enable(device, GPSSource.PHONE)
            assertNull(preparation.gpsSource(device.copy(advertLocationPolicy = 0u)))
            preferences.enable(device, GPSSource.DEVICE)
            assertEquals(GPSSource.DEVICE, preparation.gpsSource(device))
        } finally { h.container.close() }
    }

    @OriginalCase("SendAdvertIntentTests::gps gate respects the per device preference()")
    @Test fun defaultOptOutAndOtherDeviceOptInNeverRefreshBeforeEitherReach() = runTest {
        val preferences = Preferences()
        val producing = Producing()
        val preparation = AppAdvertLocation(preferences::read, producing,
            currentLocation = { error("Opted-out or foreign-device policy must not read a cached location") })
        val h = connected(this, preparation)
        try {
            val device = share(h)
            preferences.enable(device.copy(id = UUID.randomUUID()), GPSSource.DEVICE)
            assertNull(preparation.gpsSource(device))
            for (flood in listOf(false, true)) {
                val start = h.links.radios.single().sent.size
                AppShortcutRadio(h.container).sendAdvert(flood)
                assertEquals(listOf(7), commands(h, start))
                assertEquals(if (flood) "0701" else "07", h.links.radios.single().sent.last().hexString)
            }
            assertEquals(setOf(device.id), preferences.reads.toSet())
            assertEquals(0, producing.locationRequests)
            assertEquals(0, producing.permissionRequests)
        } finally { h.container.close() }
    }

    @OriginalCase("SendAdvertIntentTests::gps gate respects the per device preference()")
    @Test fun optedInDeviceGpsIsReadEnabledAndVerifiedBeforeActualAdvertWithoutPhoneLocation() = runTest {
        for (flood in listOf(false, true)) {
            val preferences = Preferences()
            val producing = Producing()
            val preparation = AppAdvertLocation(preferences::read, producing)
            val radioLocation = RadioLocation()
            val h = connected(this, preparation, radioLocation)
            try {
                val device = share(h)
                preferences.enable(device, GPSSource.DEVICE)
                assertEquals(GPSSource.DEVICE, preparation.gpsSource(device))
                val start = h.links.radios.single().sent.size
                AppShortcutRadio(h.container).sendAdvert(flood)
                assertEquals(listOf(0x28, 0x29, 0x28, 1, 7), commands(h, start))
                assertTrue(radioLocation.gpsEnabled)
                assertEquals(if (flood) "0701" else "07", h.links.radios.single().sent.last().hexString)
                assertEquals(0, producing.locationRequests)
                assertEquals(0, producing.permissionRequests)
            } finally { h.container.close() }
        }
    }

    @Test fun alreadyEnabledDeviceGpsDoesNotWriteOrRequestPhoneLocation() = runTest {
        val preferences = Preferences()
        val producing = Producing()
        val radioLocation = RadioLocation().apply { gpsEnabled = true }
        val h = connected(this, AppAdvertLocation(preferences::read, producing), radioLocation)
        try {
            preferences.enable(share(h), GPSSource.DEVICE)
            val start = h.links.radios.single().sent.size
            AppShortcutRadio(h.container).sendAdvert(false)
            assertEquals(listOf(0x28, 7), commands(h, start))
            assertEquals(0, producing.locationRequests)
            assertEquals(0, producing.permissionRequests)
        } finally { h.container.close() }
    }

    @Test fun declinedFloodConfirmationDoesNotRefreshAndAcceptedFloodRefreshesBeforeSending() = runTest {
        val preferences = Preferences()
        val producing = Producing()
        val h = connected(this, AppAdvertLocation(preferences::read, producing))
        try {
            preferences.enable(share(h), GPSSource.DEVICE)
            val confirmations = mutableListOf<ConfirmationRequest>()
            var accepted = false
            val flow = AdvertFlow(AppShortcutRadio(h.container), object : ShortcutConfirmationPort {
                override suspend fun confirm(request: ConfirmationRequest): Boolean {
                    confirmations += request
                    return accepted
                }
            }, AndroidShortcutText(ApplicationProvider.getApplicationContext<Context>()))
            val start = h.links.radios.single().sent.size
            assertSame(AdvertResult.Declined, flow.execute(AdvertReach.FLOOD))
            assertTrue(commands(h, start).isEmpty())
            assertTrue(preferences.reads.isEmpty())
            assertEquals(0, producing.locationRequests)
            assertEquals(0, producing.permissionRequests)

            accepted = true
            assertIs<AdvertResult.Sent>(flow.execute(AdvertReach.FLOOD))
            assertEquals(listOf(0x28, 0x29, 0x28, 1, 7), commands(h, start))
            assertEquals("0701", h.links.radios.single().sent.last().hexString)
            assertEquals(2, confirmations.size)
        } finally { h.container.close() }
    }

    @Test fun authorizedPhoneFixIsVerifiedBeforeAdvertForBothReachesWithoutPrompting() = runTest {
        for (flood in listOf(false, true)) {
            val preferences = Preferences()
            val producing = Producing()
            val h = connected(this, AppAdvertLocation(preferences::read, producing))
            try {
                preferences.enable(share(h), GPSSource.PHONE)
                val start = h.links.radios.single().sent.size
                AppShortcutRadio(h.container).sendAdvert(flood)
                assertEquals(listOf(0x0e, 1, 7), commands(h, start))
                assertEquals(
                    Bytes.of(0x0e) + le32(37_000_000) + le32(-122_000_000) + le32(0),
                    h.links.radios.single().sent[start],
                )
                assertEquals(1, producing.locationRequests)
                assertEquals(0, producing.permissionRequests)
            } finally { h.container.close() }
        }
    }

    @Test fun undeterminedDeniedAndRestrictedPhoneLocationNeverPromptRequestOrWrite() = runTest {
        for (status in listOf(LocationAuthorizationStatus.NOT_DETERMINED,
            LocationAuthorizationStatus.DENIED, LocationAuthorizationStatus.RESTRICTED)) {
            val preferences = Preferences()
            val producing = Producing().apply { authorizationStatus.value = status }
            val h = connected(this, AppAdvertLocation(preferences::read, producing))
            try {
                preferences.enable(share(h), GPSSource.PHONE)
                val start = h.links.radios.single().sent.size
                AppShortcutRadio(h.container).sendAdvert(false)
                assertEquals(listOf(7), commands(h, start))
                assertEquals(0, producing.locationRequests)
                assertEquals(0, producing.permissionRequests)
                assertEquals(status, producing.authorizationStatus.value)
            } finally { h.container.close() }
        }
    }

    @Test fun typedLocationAndGpsFailuresRemainBestEffortButDoNotInventCoordinatesOrSuccessWrites() = runTest {
        for (source in GPSSource.entries) {
            val preferences = Preferences()
            val failure = LocationServiceError.RequestFailed("fixture unavailable")
            val producing = Producing().apply { obtain = { throw failure } }
            val h = connected(this, AppAdvertLocation(preferences::read, producing),
                RadioLocation().apply { gpsFailure = true })
            try {
                preferences.enable(share(h), source)
                val start = h.links.radios.single().sent.size
                AppShortcutRadio(h.container).sendAdvert(false)
                assertEquals(if (source == GPSSource.DEVICE) listOf(0x28, 7) else listOf(7), commands(h, start))
                assertEquals(if (source == GPSSource.PHONE) 1 else 0, producing.locationRequests)
                assertEquals(0, producing.permissionRequests)
            } finally { h.container.close() }
        }
    }

    @Test fun cancellationDuringPhoneFixPropagatesWithoutLocationWriteOrAdvert() = runTest {
        val preferences = Preferences()
        val cancelled = CancellationException("pre-advert cancellation")
        val producing = Producing().apply { obtain = { throw cancelled } }
        var cacheReads = 0
        val h = connected(this, AppAdvertLocation(preferences::read, producing,
            currentLocation = { cacheReads++; GeoCoordinate(1.0, 2.0) }))
        try {
            preferences.enable(share(h), GPSSource.PHONE)
            val start = h.links.radios.single().sent.size
            val failure = assertFailsWith<CancellationException> { AppShortcutRadio(h.container).sendAdvert(false) }
            assertEquals(cancelled.javaClass, failure.javaClass)
            assertEquals(cancelled.message, failure.message)
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === cancelled })
            assertTrue(commands(h, start).isEmpty())
            assertEquals(0, cacheReads)
            assertEquals(0, producing.permissionRequests)
        } finally { h.container.close() }
    }

    @Test fun optOutDuringPhoneFixSkipsRadioWriteAndStillSendsUnmodifiedAdvert() = runTest {
        val preferences = Preferences()
        val producing = Producing()
        val h = connected(this, AppAdvertLocation(preferences::read, producing))
        try {
            val device = share(h)
            preferences.enable(device, GPSSource.PHONE)
            producing.obtain = {
                preferences.values.remove(device.id)
                GeoCoordinate(37.0, -122.0)
            }
            val start = h.links.radios.single().sent.size
            AppShortcutRadio(h.container).sendAdvert(false)
            assertEquals(listOf(7), commands(h, start))
            assertEquals(1, producing.locationRequests)
            assertEquals(0, producing.permissionRequests)
        } finally { h.container.close() }
    }

    @Test fun publicPolicyRevokedDuringPhoneFixCannotWriteLocationToRadio() = runTest {
        val preferences = Preferences()
        val producing = Producing()
        val h = connected(this, AppAdvertLocation(preferences::read, producing))
        try {
            val device = share(h)
            preferences.enable(device, GPSSource.PHONE)
            producing.obtain = {
                share(h, 0u)
                GeoCoordinate(37.0, -122.0)
            }
            val start = h.links.radios.single().sent.size
            AppShortcutRadio(h.container).sendAdvert(false)
            assertEquals(listOf(7), commands(h, start))
            assertEquals(1, producing.locationRequests)
            assertEquals(0, producing.permissionRequests)
            assertEquals(0u.toUByte(), assertNotNull(h.appState.connectedDevice).advertLocationPolicy)
        } finally { h.container.close() }
    }

    @Test fun servicesDisappearingDuringPhoneFixCannotWriteOrAdvertThroughStaleSession() = runTest {
        val preferences = Preferences()
        val producing = Producing()
        val h = connected(this, AppAdvertLocation(preferences::read, producing))
        try {
            val device = share(h)
            preferences.enable(device, GPSSource.PHONE)
            producing.obtain = {
                assertNotNull(h.sessions.current).tearDown()
                GeoCoordinate(37.0, -122.0)
            }
            val start = h.links.radios.single().sent.size
            assertEquals(ShortcutError.NOT_CONNECTED,
                assertFailsWith<ShortcutException> { AppShortcutRadio(h.container).sendAdvert(false) }.error)
            assertTrue(commands(h, start).isEmpty())
            assertEquals(device.radioId, h.appState.currentRadioId)
            assertEquals(0, producing.permissionRequests)
        } finally { h.container.close() }
    }

    @Test fun existingCoarseLocationManagerProducerRefreshesBeforeActualRadioAdvertWithoutPermissionRequest() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager = assertNotNull(context.getSystemService(LocationManager::class.java))
        shadowOf(manager).setProviderEnabled(LocationManager.FUSED_PROVIDER, true)
        var prompts = 0
        val producing = LocationManagerLocationProducing(context, LocationPermissionRequesting { prompts++ })
        val preferences = Preferences()
        val h = connected(this, AppAdvertLocation(preferences::read, producing, producing::refreshAuthorization,
            currentLocation = { producing.currentLocation }))
        try {
            preferences.enable(share(h), GPSSource.PHONE)
            val start = h.links.radios.single().sent.size
            val sending = async { AppShortcutRadio(h.container).sendAdvert(false) }
            testScheduler.runCurrent()
            assertFalse(sending.isCompleted)
            assertTrue(commands(h, start).isEmpty())
            shadowOf(manager).simulateLocation(Location(LocationManager.FUSED_PROVIDER).apply {
                latitude = 37.0
                longitude = -122.0
            })
            sending.await()
            assertEquals(listOf(0x0e, 1, 7), commands(h, start))
            assertEquals(0, prompts)
        } finally {
            producing.close()
            h.container.close()
            shadowOf(context).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    @Test fun nativePermissionRevocationIsReReadBeforeShortcutWithoutPromptOrLocationWrite() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        var prompts = 0
        val producing = LocationManagerLocationProducing(context, LocationPermissionRequesting { prompts++ })
        assertEquals(LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE, producing.authorizationStatus.value)
        val preferences = Preferences()
        val h = connected(this, AppAdvertLocation(preferences::read, producing, producing::refreshAuthorization,
            currentLocation = { producing.currentLocation }))
        try {
            preferences.enable(share(h), GPSSource.PHONE)
            shadowOf(context).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            val start = h.links.radios.single().sent.size
            AppShortcutRadio(h.container).sendAdvert(false)
            assertEquals(listOf(7), commands(h, start))
            assertEquals(LocationAuthorizationStatus.DENIED, producing.authorizationStatus.value)
            assertNull(producing.currentLocation)
            assertEquals(0, prompts)
        } finally {
            producing.close()
            h.container.close()
            shadowOf(context).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

    }

    @Test fun regionResolverFixIsSharedWithAdvertFallbackAfterFailureOrDenialForBothReaches() = runTest {
        for (denied in listOf(false, true)) {
            val application = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            val manager = assertNotNull(application.getSystemService(LocationManager::class.java))
            shadowOf(manager).setProviderEnabled(LocationManager.FUSED_PROVIDER, true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
            var prompts = 0
            val producing = LocationManagerLocationProducing(application, LocationPermissionRequesting { prompts++ })
            val geocoded = mutableListOf<GeoCoordinate>()
            val region = RegionResolver(LocationService(producing), object : Geocoder {
                override suspend fun reverseGeocode(coordinate: GeoCoordinate, preferredLocale: java.util.Locale?): GeocodeResult {
                    geocoded += coordinate
                    return GeocodeResult("US", null, null)
                }
                override fun cancelGeocode(): Unit = error("No geocode cancellation expected")
            })
            var h: ContainerHarness? = null
            try {
                assertNull(producing.currentLocation)
                val resolving = async { region.resolve() }
                testScheduler.runCurrent()
                assertFalse(resolving.isCompleted)
                shadowOf(manager).simulateLocation(Location(LocationManager.FUSED_PROVIDER).apply {
                    latitude = 37.0
                    longitude = -122.0
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                })
                assertEquals(GeoCoordinate(37.0, -122.0), producing.currentLocation)
                assertEquals("US", assertNotNull(resolving.await()).countryCode)
                assertEquals(listOf(GeoCoordinate(37.0, -122.0)), geocoded)
                if (denied) shadowOf(application).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
                else for (provider in listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                    shadowOf(manager).setProviderEnabled(provider, false)
                }
                val preferences = Preferences()
                val preparation = AppAdvertLocation(preferences::read, producing, producing::refreshAuthorization,
                    currentLocation = { producing.currentLocation })
                val radioLocation = RadioLocation(advertLocationPolicy = 1u)
                val active = connected(this, preparation, radioLocation)
                h = active
                assertEquals(1.toUByte(), assertNotNull(active.appState.connectedDevice).advertLocationPolicy)
                preferences.enable(share(active), GPSSource.PHONE)
                for (flood in listOf(false, true)) {
                    assertEquals(1.toUByte(), assertNotNull(active.appState.connectedDevice).advertLocationPolicy)
                    val start = active.links.radios.single().sent.size
                    AppShortcutRadio(active.container).sendAdvert(flood)
                    active.eventually("public policy and coordinates retained after verified location readback") {
                        active.appState.connectedDevice?.let {
                            it.advertLocationPolicy == 1.toUByte() && it.latitude == 37.0 && it.longitude == -122.0
                        } == true
                    }
                    assertEquals(listOf(0x0e, 1, 7), commands(active, start))
                    assertEquals(Bytes.of(0x0e) + le32(37_000_000) + le32(-122_000_000) + le32(0),
                        active.links.radios.single().sent[start])
                    assertEquals(if (flood) "0701" else "07", active.links.radios.single().sent.last().hexString)
                    assertEquals(37_000_000L, radioLocation.latitudeE6)
                    assertEquals(-122_000_000L, radioLocation.longitudeE6)
                }
                assertEquals(GeoCoordinate(37.0, -122.0), producing.currentLocation)
                assertEquals(0, prompts)
                assertTrue(store.fetchPendingSends(assertNotNull(active.appState.currentRadioId)).isEmpty())
            } finally {
                h?.container?.close()
                producing.close()
                shadowOf(application).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
    }

    @Test fun cachedFallbackRechecksOptOutPublicPolicyAndSessionBeforeAnyWrite() = runTest {
        for (gate in listOf("opt-out", "private", "session")) {
            val preferences = Preferences()
            val producing = Producing()
            var cacheReads = 0
            val preparation = AppAdvertLocation(preferences::read, producing,
                currentLocation = { cacheReads++; GeoCoordinate(37.0, -122.0) })
            val h = connected(this, preparation)
            try {
                val device = share(h)
                preferences.enable(device, GPSSource.PHONE)
                producing.obtain = {
                    when (gate) {
                        "opt-out" -> preferences.values.remove(device.id)
                        "private" -> share(h, 0u)
                        "session" -> assertNotNull(h.sessions.current).tearDown()
                    }
                    throw LocationServiceError.RequestFailed("fixture refresh unavailable")
                }
                val start = h.links.radios.single().sent.size
                if (gate == "session") {
                    assertEquals(ShortcutError.NOT_CONNECTED,
                        assertFailsWith<ShortcutException> { AppShortcutRadio(h.container).sendAdvert(false) }.error)
                    assertTrue(commands(h, start).isEmpty())
                } else {
                    AppShortcutRadio(h.container).sendAdvert(false)
                    assertEquals(listOf(7), commands(h, start))
                }
                assertEquals(1, cacheReads)
                assertEquals(1, producing.locationRequests)
                assertEquals(0, producing.permissionRequests)
                assertTrue(store.fetchPendingSends(device.radioId).isEmpty())
            } finally { h.container.close() }
        }
    }

    @Test fun declinedFloodDoesNotReadAnAvailableSharedCacheOrStartRefresh() = runTest {
        val preferences = Preferences()
        val producing = Producing()
        var cacheReads = 0
        val h = connected(this, AppAdvertLocation(preferences::read, producing,
            currentLocation = { cacheReads++; GeoCoordinate(37.0, -122.0) }))
        try {
            preferences.enable(share(h), GPSSource.PHONE)
            val start = h.links.radios.single().sent.size
            val flow = AdvertFlow(AppShortcutRadio(h.container), object : ShortcutConfirmationPort {
                override suspend fun confirm(request: ConfirmationRequest): Boolean = false
            }, AndroidShortcutText(ApplicationProvider.getApplicationContext<Context>()))
            assertEquals(AdvertResult.Declined, flow.execute(AdvertReach.FLOOD))
            assertTrue(commands(h, start).isEmpty())
            assertTrue(preferences.reads.isEmpty())
            assertEquals(0, cacheReads)
            assertEquals(0, producing.locationRequests)
            assertEquals(0, producing.permissionRequests)
            assertTrue(store.fetchPendingSends(assertNotNull(h.appState.currentRadioId)).isEmpty())
        } finally { h.container.close() }
    }

    @Test fun actualSavedPerDevicePreferencesControlTheRealAdvertAndSurviveStorageReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = MeshCoreStorage.get(context)
        val producing = Producing()
        val preparation = AppAdvertLocation({ id -> storage.devicePreferences.observe(id).first() }, producing)
        var savedDeviceId: UUID? = null
        try {
            val h = connected(this, preparation)
            try {
                val device = share(h)
                savedDeviceId = device.id
                val foreignId = UUID.randomUUID()
                storage.devicePreferences.setAutoUpdateLocationEnabled(true, foreignId)
                storage.devicePreferences.setGPSSource(GPSSource.DEVICE, foreignId)
                assertNull(preparation.gpsSource(device))
                val start = h.links.radios.single().sent.size
                AppShortcutRadio(h.container).sendAdvert(false)
                assertEquals(listOf(7), commands(h, start))

                storage.devicePreferences.setAutoUpdateLocationEnabled(true, device.id)
                storage.devicePreferences.setGPSSource(GPSSource.DEVICE, device.id)
                assertEquals(GPSSource.DEVICE, preparation.gpsSource(device))
                val enabledStart = h.links.radios.single().sent.size
                AppShortcutRadio(h.container).sendAdvert(false)
                assertEquals(listOf(0x28, 0x29, 0x28, 1, 7), commands(h, enabledStart))
                assertEquals(0, producing.locationRequests)
                assertEquals(0, producing.permissionRequests)
            } finally { h.container.close() }
        } finally { storage.close() }
        val reopened = MeshCoreStorage.get(context)
        try {
            val saved = reopened.devicePreferences.observe(assertNotNull(savedDeviceId)).first()
            assertTrue(saved.autoUpdateLocation)
            assertEquals(GPSSource.DEVICE, saved.gpsSource)
            assertEquals("device", saved.gpsSourceRaw)
        } finally { reopened.close() }
    }
}
