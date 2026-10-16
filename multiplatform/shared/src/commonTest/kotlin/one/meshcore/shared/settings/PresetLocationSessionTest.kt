package one.meshcore.shared.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.LocationAuthorizationStatus
import one.meshcore.shared.map.LocationService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Waiting for location permission. A miss still consults the status that came back. Cancellation
 * and an error propagate and do not open Settings or resolve a preset.
 */
class PresetLocationSessionTest {
    @Test
    fun aLocationMissThatEndsDeniedOpensSettings() = runTest {
        val harness = PresetLocationHarness(afterRequest = LocationAuthorizationStatus.DENIED) {
            throw IllegalStateException("no fix")
        }
        harness.session.useMyLocation(harness.host)

        assertTrue(harness.session.showOpenSettingsAlert.value)
        assertEquals(0, harness.resolves)
        assertFalse(harness.session.isResolving.value)
        assertNull(harness.session.errorMessage.value)
    }

    @Test
    fun aLocationMissThatEndsAuthorizedStillResolves() = runTest {
        val harness = PresetLocationHarness(
            afterRequest = LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE,
        ) {
            throw IllegalStateException("no fix")
        }
        harness.session.useMyLocation(harness.host)

        assertEquals(1, harness.resolves)
        assertFalse(harness.session.showOpenSettingsAlert.value)
        assertEquals("miss", harness.session.errorMessage.value)
        assertFalse(harness.session.isResolving.value)
    }

    @Test
    fun anAuthorizedFixResolvesThePreset() = runTest {
        val harness = PresetLocationHarness(
            afterRequest = LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE,
        ) {
            LatLng(latitude = 1.0, longitude = 2.0)
        }
        harness.session.useMyLocation(harness.host)

        assertEquals(1, harness.resolves)
        assertFalse(harness.session.showOpenSettingsAlert.value)
        assertFalse(harness.session.isResolving.value)
    }

    @Test
    fun aCancelledPermissionWaitPropagates() = runTest {
        val cancelled = CancellationException("superseded")
        val harness = PresetLocationHarness(afterRequest = LocationAuthorizationStatus.DENIED) {
            throw cancelled
        }

        val thrown = assertFailsWith<CancellationException> {
            harness.session.useMyLocation(harness.host)
        }

        assertSame(cancelled, thrown)
        assertFalse(harness.session.showOpenSettingsAlert.value)
        assertEquals(0, harness.resolves)
        assertFalse(harness.session.isResolving.value)
    }

    @Test
    fun aPermissionWaitErrorPropagates() = runTest {
        val probe = PresetLocationProbeError()
        val harness = PresetLocationHarness(afterRequest = LocationAuthorizationStatus.DENIED) {
            throw probe
        }

        val thrown = assertFailsWith<PresetLocationProbeError> {
            harness.session.useMyLocation(harness.host)
        }

        assertSame(probe, thrown)

        assertFalse(harness.session.showOpenSettingsAlert.value)
        assertEquals(0, harness.resolves)
        assertFalse(harness.session.isResolving.value)
    }
}

private class PresetLocationProbeError : Error("probe")

private class PresetLocationHarness(
    private val afterRequest: LocationAuthorizationStatus,
    private val request: suspend () -> LatLng,
) {
    val session = PresetLocationSession()
    var resolves = 0
    val host: PresetLocationHost

    init {
        val status = MutableStateFlow(LocationAuthorizationStatus.NOT_DETERMINED)
        val service = object : LocationService {
            override val authorizationStatus = status
            override val currentLocation = MutableStateFlow<LatLng?>(null)
            override val isRequestingLocation = MutableStateFlow(false)

            override fun requestPermissionIfNeeded() = Unit

            override fun requestLocation() = Unit

            override suspend fun requestCurrentLocation(timeout: Duration): LatLng {
                status.value = afterRequest
                return request()
            }
        }
        host = PresetLocationHost(
            locationService = service,
            current = { null },
            setSelection = {},
            resolve = {
                resolves += 1
                null
            },
            lookupMissMessage = "miss",
        )
    }
}
