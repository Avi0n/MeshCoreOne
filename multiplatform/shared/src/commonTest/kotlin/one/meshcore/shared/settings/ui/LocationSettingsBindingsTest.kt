package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.settings.AdvertLocationPolicy
import one.meshcore.shared.settings.DeviceGpsState
import one.meshcore.shared.settings.GpsSource
import one.meshcore.shared.settings.LocationSettingsSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Location advert policy, radio GPS, and a picked coordinate. A failed write still reports the
 * settings text or the retry alert, and a failed coordinate rolls back. Cancellation rolls a
 * coordinate back and then propagates. An error propagates and does not record a failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationSettingsBindingsTest {
    @Test
    fun aShareWriteStoresTheStoredCoordinatePolicy() = runTest {
        val harness = LocationHarness(LocationScript())
        harness.bindings(locationScope()).writeAdvertPolicy(share = true, onFailure = { harness.failures += 1 })
        advanceUntilIdle()

        assertEquals(listOf(AdvertLocationPolicy.PREFS), harness.policies)
        assertEquals(AdvertLocationPolicy.PREFS.rawValue, harness.snapshot?.advertLocationPolicyRaw)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(0, harness.failures)
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.saving)
    }

    @Test
    fun anUnchangedPolicySkipsTheWrite() = runTest {
        val harness = LocationHarness(LocationScript(policy = AdvertLocationPolicy.PREFS))
        harness.bindings(locationScope()).writeAdvertPolicy(share = true, onFailure = { harness.failures += 1 })
        advanceUntilIdle()

        assertTrue(harness.policies.isEmpty())
        assertEquals(0, harness.failures)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(listOf(true, false), harness.saving)
    }

    @Test
    fun aMissingSnapshotSkipsThePolicyWrite() = runTest {
        val harness = LocationHarness(LocationScript(snapshot = null))
        harness.bindings(locationScope()).writeAdvertPolicy(share = true, onFailure = { harness.failures += 1 })
        advanceUntilIdle()

        assertTrue(harness.policies.isEmpty())
        assertEquals(0, harness.failures)
        assertEquals(listOf(true, false), harness.saving)
    }

    @Test
    fun aPolicyExceptionReportsTheSettingsText() = runTest {
        val harness =
            LocationHarness(LocationScript(policyWrite = { _ -> throw IllegalArgumentException("bad policy") }))
        harness.bindings(locationScope()).writeAdvertPolicy(share = true, onFailure = { harness.failures += 1 })
        advanceUntilIdle()

        assertEquals(1, harness.failures)
        assertTrue(harness.policies.isEmpty())
        assertEquals(AdvertLocationPolicy.NONE.rawValue, harness.snapshot?.advertLocationPolicyRaw)
        assertEquals(listOf(null, "bad policy"), harness.errors)
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.saving)
    }

    @Test
    fun aRetryablePolicyFailureShowsTheAlert() = runTest {
        val harness =
            LocationHarness(LocationScript(policyWrite = { _ -> throw IllegalStateException("No radio connected") }))
        harness.bindings(locationScope()).writeAdvertPolicy(share = true, onFailure = { harness.failures += 1 })
        advanceUntilIdle()

        assertEquals(1, harness.failures)
        assertTrue(harness.alert.isPresented)
        assertEquals("No radio connected", harness.alert.message)
        assertEquals(listOf<String?>(null), harness.errors)
        assertEquals(AdvertLocationPolicy.NONE.rawValue, harness.snapshot?.advertLocationPolicyRaw)
        assertEquals(listOf(true, false), harness.saving)
    }

    @Test
    fun aCancelledPolicyWritePropagatesWithoutAFailure() = runTest {
        val cancelled = CancellationException("superseded")
        val harness = LocationHarness(LocationScript(policyWrite = { _ -> throw cancelled }))
        val scope = locationScope()
        harness.bindings(scope).writeAdvertPolicy(share = true, onFailure = { harness.failures += 1 })
        val child = scope.coroutineContext[Job]!!.children.single()
        child.invokeOnCompletion { harness.cause = it }
        advanceUntilIdle()

        assertSame(cancelled, harness.cause)
        assertEquals(0, harness.failures)
        assertEquals(listOf<String?>(null), harness.errors)
        assertFalse(harness.alert.isPresented)
        assertEquals(AdvertLocationPolicy.NONE.rawValue, harness.snapshot?.advertLocationPolicyRaw)
        assertEquals(listOf(true, false), harness.saving)
    }

    @Test
    fun aPolicyErrorPropagatesWithoutAFailure() = runTest {
        var thrown: Throwable? = null
        val probe = LocationProbeError()
        val harness = LocationHarness(LocationScript(policyWrite = { _ -> throw probe }))
        harness.bindings(locationScope { thrown = it })
            .writeAdvertPolicy(share = true, onFailure = { harness.failures += 1 })
        advanceUntilIdle()

        assertSame(probe, thrown)
        assertEquals(0, harness.failures)
        assertEquals(listOf<String?>(null), harness.errors)
        assertFalse(harness.alert.isPresented)
        assertEquals(AdvertLocationPolicy.NONE.rawValue, harness.snapshot?.advertLocationPolicyRaw)
        assertEquals(listOf(true, false), harness.saving)
    }

    @Test
    fun aGpsWriteEnablesTheRadioAndClearsTheSpinner() = runTest {
        val harness = LocationHarness(LocationScript(gps = RADIO_GPS_OFF))
        harness.bindings(locationScope()).writeDeviceGps(enabled = true, onSuccess = { harness.successes += 1 })
        advanceUntilIdle()

        assertEquals(listOf(true), harness.gpsWrites)
        assertEquals(true, harness.gps.isEnabled)
        assertEquals(1, harness.successes)
        assertEquals(listOf<String?>(null), harness.errors)
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.saving)
    }

    @Test
    fun aMissingGpsWriterReturnsBeforeCallbacks() = runTest {
        val harness = LocationHarness(LocationScript(gpsWrite = null))
        harness.bindings(locationScope()).writeDeviceGps(enabled = true, onSuccess = { harness.successes += 1 })
        advanceUntilIdle()

        assertTrue(harness.saving.isEmpty())
        assertTrue(harness.errors.isEmpty())
        assertEquals(0, harness.successes)
        assertFalse(harness.gps.isEnabled)
    }

    @Test
    fun aGpsExceptionRollsBackAndReportsTheSettingsText() = runTest {
        val harness = LocationHarness(
            LocationScript(
                gps = RADIO_GPS_OFF,
                gpsWrite = { _ -> throw IllegalArgumentException("bad gps") },
            ),
        )
        harness.bindings(locationScope()).writeDeviceGps(
            enabled = true,
            onSuccess = { harness.successes += 1 },
            onFailure = { harness.failures += 1 },
        )
        advanceUntilIdle()

        assertEquals(1, harness.failures)
        assertEquals(0, harness.successes)
        assertFalse(harness.gps.isEnabled)
        assertEquals(listOf(RADIO_GPS_OFF), harness.gpsSets)
        assertEquals(listOf(null, "bad gps"), harness.errors)
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.saving)
    }

    @Test
    fun aRetryableGpsFailureShowsTheAlert() = runTest {
        val harness = LocationHarness(
            LocationScript(
                gps = RADIO_GPS_OFF,
                gpsWrite = { _ -> throw IllegalStateException("No radio connected") },
            ),
        )
        harness.bindings(locationScope()).writeDeviceGps(
            enabled = true,
            onFailure = { harness.failures += 1 },
        )
        advanceUntilIdle()

        assertEquals(1, harness.failures)
        assertTrue(harness.alert.isPresented)
        assertEquals("No radio connected", harness.alert.message)
        assertEquals(listOf<String?>(null), harness.errors)
        assertFalse(harness.gps.isEnabled)
        assertEquals(listOf(true, false), harness.saving)
    }

    @Test
    fun aCancelledGpsWritePropagatesWithoutAFailure() = runTest {
        val cancelled = CancellationException("superseded")
        val harness =
            LocationHarness(
                LocationScript(gps = RADIO_GPS_OFF, gpsWrite = { _ ->
                    throw cancelled
                }),
            )
        val scope = locationScope()
        harness.bindings(scope).writeDeviceGps(enabled = true, onFailure = { harness.failures += 1 })
        val child = scope.coroutineContext[Job]!!.children.single()
        child.invokeOnCompletion { harness.cause = it }
        advanceUntilIdle()

        assertSame(cancelled, harness.cause)
        assertEquals(0, harness.failures)
        assertTrue(harness.gpsSets.isEmpty())
        assertEquals(listOf<String?>(null), harness.errors)
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.saving)
    }

    @Test
    fun aGpsErrorPropagatesWithoutAFailure() = runTest {
        var thrown: Throwable? = null
        val probe = LocationProbeError()
        val harness =
            LocationHarness(LocationScript(gps = RADIO_GPS_OFF, gpsWrite = { _ -> throw probe }))
        harness.bindings(locationScope { thrown = it }).writeDeviceGps(
            enabled = true,
            onFailure = { harness.failures += 1 },
        )
        advanceUntilIdle()

        assertSame(probe, thrown)
        assertEquals(0, harness.failures)
        assertTrue(harness.gpsSets.isEmpty())
        assertEquals(listOf<String?>(null), harness.errors)
        assertFalse(harness.alert.isPresented)
        assertEquals(listOf(true, false), harness.saving)
    }

    @Test
    fun aPickedCoordinateDisablesTheRadioGps() = runTest {
        val harness = LocationHarness(LocationScript(gps = RADIO_GPS_ON))
        harness.bindings(locationScope()).persistManualCoordinate(PICKED)
        advanceUntilIdle()

        assertEquals(listOf(PICKED), harness.picks)
        assertEquals(PICKED.latitude, harness.snapshot?.latitude)
        assertEquals(PICKED.longitude, harness.snapshot?.longitude)
        assertFalse(harness.gps.isEnabled)
        assertTrue(harness.gps.isSupported)
        assertTrue(harness.policies.isEmpty())
    }

    @Test
    fun aFailedCoordinateRollsBack() = runTest {
        val failed = IllegalArgumentException("bad fix")
        val harness = LocationHarness(LocationScript(pick = { _ -> throw failed }))
        val bindings = harness.bindings(locationScope())

        val error = assertFailsWith<IllegalArgumentException> {
            bindings.persistManualCoordinate(PICKED)
        }

        assertSame(failed, error)
        assertEquals(START, harness.snapshot)
        assertTrue(harness.gpsSets.isEmpty())
    }

    @Test
    fun aCancelledCoordinateRollsBackAndPropagates() = runTest {
        val cancelled = CancellationException("superseded")
        val harness = LocationHarness(LocationScript(pick = { _ -> throw cancelled }))
        val bindings = harness.bindings(locationScope())

        val error = assertFailsWith<CancellationException> {
            bindings.persistManualCoordinate(PICKED)
        }

        assertSame(cancelled, error)
        assertEquals(START, harness.snapshot)
        assertTrue(harness.gpsSets.isEmpty())
    }

    @Test
    fun aCoordinateErrorKeepsTheOptimisticSnapshot() = runTest {
        val probe = LocationProbeError()
        val harness = LocationHarness(LocationScript(pick = { _ -> throw probe }))
        val bindings = harness.bindings(locationScope())

        val error = assertFailsWith<LocationProbeError> {
            bindings.persistManualCoordinate(PICKED)
        }

        assertSame(probe, error)

        assertEquals(PICKED.latitude, harness.snapshot?.latitude)
        assertEquals(PICKED.longitude, harness.snapshot?.longitude)
        assertEquals(AdvertLocationPolicy.NONE.rawValue, harness.snapshot?.advertLocationPolicyRaw)
        assertTrue(harness.gpsSets.isEmpty())
    }

    @Test
    fun aLiveFixPolicyBecomesTheStoredCoordinate() = runTest {
        val harness = LocationHarness(LocationScript(policy = AdvertLocationPolicy.SHARE, gps = RADIO_GPS_ON))
        harness.bindings(locationScope()).persistManualCoordinate(PICKED)
        advanceUntilIdle()

        assertEquals(listOf(AdvertLocationPolicy.PREFS), harness.policies)
        assertEquals(AdvertLocationPolicy.PREFS.rawValue, harness.snapshot?.advertLocationPolicyRaw)
        assertEquals(PICKED.latitude, harness.snapshot?.latitude)
        assertFalse(harness.gps.isEnabled)
    }

    @Test
    fun aFailedPolicyDemotionRollsTheCoordinateBack() = runTest {
        val failed = IllegalArgumentException("bad policy")
        val harness = LocationHarness(
            LocationScript(
                policy = AdvertLocationPolicy.SHARE,
                gps = RADIO_GPS_ON,
                policyWrite = { _ -> throw failed },
            ),
        )
        val bindings = harness.bindings(locationScope())

        val error = assertFailsWith<IllegalArgumentException> {
            bindings.persistManualCoordinate(PICKED)
        }

        assertSame(failed, error)

        assertEquals(START_SHARE, harness.snapshot)
        assertFalse(harness.gps.isEnabled)
        assertTrue(harness.policies.isEmpty())
    }

    @Test
    fun aDeviceFedAutoUpdateTurnsOffAfterAManualCoordinate() = runTest {
        val harness = LocationHarness(
            LocationScript(
                auto = true,
                source = GpsSource.DEVICE,
                gps = RADIO_GPS_ON,
            ),
        )
        harness.bindings(locationScope()).persistManualCoordinate(PICKED)
        advanceUntilIdle()

        assertFalse(harness.auto)
        assertFalse(harness.gps.isEnabled)
        assertTrue(harness.policies.isEmpty())
        assertEquals(PICKED.latitude, harness.snapshot?.latitude)
    }
}

private class LocationProbeError : Error("probe")

private val START = LocationSettingsSnapshot(
    latitude = 1.0,
    longitude = 2.0,
    advertLocationPolicyRaw = AdvertLocationPolicy.NONE.rawValue,
)

private val START_SHARE = START.copy(advertLocationPolicyRaw = AdvertLocationPolicy.SHARE.rawValue)

private val PICKED = LatLng(latitude = 10.0, longitude = 20.0)

private val RADIO_GPS_OFF = DeviceGpsState(isSupported = true, isEnabled = false)

private val RADIO_GPS_ON = DeviceGpsState(isSupported = true, isEnabled = true)

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.locationScope(onError: (Throwable) -> Unit = {}): CoroutineScope = CoroutineScope(
    SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, error -> onError(error) },
)

private data class LocationScript(
    val snapshot: LocationSettingsSnapshot? = START,
    val policyWrite: suspend (AdvertLocationPolicy) -> Unit = { _ -> },
    val gpsWrite: (suspend (Boolean) -> Unit)? = { _ -> },
    val pick: suspend (LatLng) -> Unit = { _ -> },
    val auto: Boolean = false,
    val source: GpsSource = GpsSource.PHONE,
    val gps: DeviceGpsState = DeviceGpsState.unsupported,
    val policy: AdvertLocationPolicy = AdvertLocationPolicy.NONE,
)

private class LocationHarness(script: LocationScript = LocationScript()) {
    var snapshot: LocationSettingsSnapshot? = script.snapshot?.copy(advertLocationPolicyRaw = script.policy.rawValue)
    var auto = script.auto
    var source = script.source
    var gps = script.gps
    private val policyWrite = script.policyWrite
    private val gpsWrite = script.gpsWrite
    private val pick = script.pick
    val saving = mutableListOf<Boolean>()
    val errors = mutableListOf<String?>()
    val policies = mutableListOf<AdvertLocationPolicy>()
    val gpsWrites = mutableListOf<Boolean>()
    val gpsSets = mutableListOf<DeviceGpsState>()
    val picks = mutableListOf<LatLng>()
    var failures = 0
    var successes = 0
    val alert = RetryAlertState()
    var cause: Throwable? = null

    fun bindings(scope: CoroutineScope) = LocationSettingsBindings(
        snapshot = { snapshot },
        setSnapshot = { snapshot = it },
        autoUpdateLocation = { auto },
        setAutoUpdateLocation = { auto = it },
        gpsSource = { source },
        setGpsSource = { source = it },
        deviceGps = { gps },
        setDeviceGps = {
            gps = it
            gpsSets += it
        },
        setSaving = { saving += it },
        setErrorMessage = { errors += it },
        setLocationDeniedAlert = {},
        deviceId = { null },
        preferences = { null },
        locationDenied = { false },
        onSetAdvertLocationPolicy = { value ->
            policyWrite(value)
            policies += value
        },
        onSetDeviceGpsEnabled = gpsWrite?.let { write ->
            { enabled ->
                write(enabled)
                gpsWrites += enabled
            }
        },
        onLocationPicked = { picked ->
            pick(picked)
            picks += picked
        },
        onRequestLocationPermission = {},
        onMaxRetriesExceeded = {},
        scope = scope,
        retryAlert = alert,
        settingsErrorText = { it.message ?: "settings failed" },
    )
}
