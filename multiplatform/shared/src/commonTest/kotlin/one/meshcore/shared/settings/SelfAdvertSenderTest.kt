package one.meshcore.shared.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.map.FakeLocationService
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.LocationAuthorizationStatus
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val DEVICE_ID = "8C4A1E2F-0000-4000-8000-000000000001"
private const val PHONE_LATITUDE = 52.5163
private const val PHONE_LONGITUDE = 13.3777
private const val CACHED_LATITUDE = 48.8584
private const val CACHED_LONGITUDE = 2.2945

/** Records every radio call in order so a test can assert what did *not* happen as well as what did. */
private class RecordingRadio(
    private val sharesLocation: Boolean = true,
    private val rowId: String? = DEVICE_ID,
    private val gpsEnabled: Boolean = false,
    private val onWriteCoordinate: () -> Unit = {},
) : SelfAdvertRadio {
    val calls = mutableListOf<String>()

    override suspend fun deviceId(): String? = rowId

    override suspend fun sharesLocationPublicly(): Boolean = sharesLocation

    override suspend fun writeCoordinate(latitude: Double, longitude: Double) {
        calls += "writeCoordinate($latitude, $longitude)"
        onWriteCoordinate()
    }

    override suspend fun deviceGpsState(): DeviceGpsState {
        calls += "deviceGpsState"
        return DeviceGpsState(isSupported = true, isEnabled = gpsEnabled)
    }

    override suspend fun enableDeviceGps() {
        calls += "enableDeviceGps"
    }

    override suspend fun sendAdvertisement(flood: Boolean) {
        calls += "sendAdvertisement($flood)"
    }
}

/**
 * Covers the pre-advert position refresh against Swift `AppState.sendSelfAdvert`: the three-way privacy
 * gate in both directions, the two GPS sources, the no-prompt mode, and the rule that a refresh failure
 * never blocks the advert.
 */
class SelfAdvertSenderTest {
    private val preferences = DeviceLocationPreferences(InMemoryPreferencesStore())

    private fun sender(
        location: FakeLocationService = FakeLocationService(),
        onFailure: suspend (Throwable) -> Unit = {},
    ) = SelfAdvertSender(preferences, { location }, onFailure)

    private suspend fun enableAutoUpdate(source: GpsSource) {
        preferences.setAutoUpdateLocationEnabled(true, DEVICE_ID)
        preferences.setGpsSource(source, DEVICE_ID)
    }

    @Test
    fun writesThePhoneFixBeforeTheAdvertGoesOut() = runTest {
        enableAutoUpdate(GpsSource.PHONE)
        val location = FakeLocationService(locationResult = { LatLng(PHONE_LATITUDE, PHONE_LONGITUDE) })
        val radio = RecordingRadio()

        sender(location).send(radio, flood = false, allowLocationPrompt = true)

        assertEquals(
            listOf("writeCoordinate($PHONE_LATITUDE, $PHONE_LONGITUDE)", "sendAdvertisement(false)"),
            radio.calls,
        )
    }

    @Test
    fun switchesTheDeviceGpsOnWhenThatIsTheChosenSource() = runTest {
        enableAutoUpdate(GpsSource.DEVICE)
        val radio = RecordingRadio(gpsEnabled = false)

        sender().send(radio, flood = true, allowLocationPrompt = true)

        assertEquals(listOf("deviceGpsState", "enableDeviceGps", "sendAdvertisement(true)"), radio.calls)
    }

    @Test
    fun leavesTheDeviceGpsAloneWhenItIsAlreadyOn() = runTest {
        enableAutoUpdate(GpsSource.DEVICE)
        val radio = RecordingRadio(gpsEnabled = true)

        sender().send(radio, flood = false, allowLocationPrompt = true)

        assertEquals(listOf("deviceGpsState", "sendAdvertisement(false)"), radio.calls)
    }

    // The negative direction of the gate: the radio's advert policy is the privacy control, so a radio
    // that does not share its position must see no location work at all, whatever the user's preference.
    @Test
    fun writesNoLocationWhenTheRadioDoesNotShareItPublicly() = runTest {
        enableAutoUpdate(GpsSource.PHONE)
        val location = FakeLocationService(locationResult = { LatLng(PHONE_LATITUDE, PHONE_LONGITUDE) })
        val radio = RecordingRadio(sharesLocation = false)

        sender(location).send(radio, flood = false, allowLocationPrompt = true)

        assertEquals(listOf("sendAdvertisement(false)"), radio.calls)
        assertEquals(0, location.requestCount)
    }

    // The other half of the gate: sharing is on, but the user never asked for auto-update, so the
    // manually placed position must survive the advert untouched.
    @Test
    fun writesNoLocationWhenAutoUpdateIsOff() = runTest {
        preferences.setGpsSource(GpsSource.PHONE, DEVICE_ID)
        val location = FakeLocationService(locationResult = { LatLng(PHONE_LATITUDE, PHONE_LONGITUDE) })
        val radio = RecordingRadio()

        sender(location).send(radio, flood = false, allowLocationPrompt = true)

        assertEquals(listOf("sendAdvertisement(false)"), radio.calls)
        assertEquals(0, location.requestCount)
    }

    @Test
    fun writesNoLocationForADeviceGpsSourceWhenTheRadioDoesNotShareItPublicly() = runTest {
        enableAutoUpdate(GpsSource.DEVICE)
        val radio = RecordingRadio(sharesLocation = false, gpsEnabled = false)

        sender().send(radio, flood = false, allowLocationPrompt = true)

        assertEquals(listOf("sendAdvertisement(false)"), radio.calls)
    }

    @Test
    fun writesNoLocationWhenTheDeviceRowIsUnknown() = runTest {
        enableAutoUpdate(GpsSource.PHONE)
        val location = FakeLocationService(locationResult = { LatLng(PHONE_LATITUDE, PHONE_LONGITUDE) })
        val radio = RecordingRadio(rowId = null)

        sender(location).send(radio, flood = false, allowLocationPrompt = true)

        assertEquals(listOf("sendAdvertisement(false)"), radio.calls)
        assertEquals(0, location.requestCount)
    }

    @Test
    fun fallsBackToTheCachedFixWhenTheOneShotFails() = runTest {
        enableAutoUpdate(GpsSource.PHONE)
        val location = FakeLocationService(locationResult = { error("no fix") })
        location.currentLocation.value = LatLng(CACHED_LATITUDE, CACHED_LONGITUDE)
        val radio = RecordingRadio()

        sender(location).send(radio, flood = false, allowLocationPrompt = true)

        assertEquals(
            listOf("writeCoordinate($CACHED_LATITUDE, $CACHED_LONGITUDE)", "sendAdvertisement(false)"),
            radio.calls,
        )
    }

    @Test
    fun aCancelledOneShotSkipsTheAdvertEvenWhenAFixIsCached() = runTest {
        enableAutoUpdate(GpsSource.PHONE)
        val cancelled = CancellationException("superseded")
        val location = FakeLocationService(locationResult = { throw cancelled })
        location.currentLocation.value = LatLng(CACHED_LATITUDE, CACHED_LONGITUDE)
        val radio = RecordingRadio()
        var reported: Throwable? = null

        val thrown = assertFailsWith<CancellationException> {
            sender(location) { reported = it }.send(radio, flood = false, allowLocationPrompt = true)
        }

        assertSame(cancelled, thrown)
        assertNull(reported)
        assertEquals(1, location.requestCount)
        assertEquals(emptyList(), radio.calls)
    }

    @Test
    fun anErrorFromTheOneShotSkipsTheAdvertEvenWhenAFixIsCached() = runTest {
        enableAutoUpdate(GpsSource.PHONE)
        val fault = SelfAdvertRefreshProbeFault()
        val location = FakeLocationService(locationResult = { throw fault })
        location.currentLocation.value = LatLng(CACHED_LATITUDE, CACHED_LONGITUDE)
        val radio = RecordingRadio()
        var reported: Throwable? = null

        val thrown = assertFailsWith<SelfAdvertRefreshProbeFault> {
            sender(location) { reported = it }.send(radio, flood = false, allowLocationPrompt = true)
        }

        assertSame(fault, thrown)
        assertNull(reported)
        assertEquals(1, location.requestCount)
        assertEquals(emptyList(), radio.calls)
    }

    // A caller that cannot present a permission dialog must never raise one: with permission still
    // undecided the refresh uses the cached fix only, and advertises the old position rather than stall.
    @Test
    fun neverPromptsForPermissionWhenPromptingIsDisallowed() = runTest {
        enableAutoUpdate(GpsSource.PHONE)
        val location = FakeLocationService(
            status = LocationAuthorizationStatus.NOT_DETERMINED,
            locationResult = { LatLng(PHONE_LATITUDE, PHONE_LONGITUDE) },
        )
        val radio = RecordingRadio()

        sender(location).send(radio, flood = false, allowLocationPrompt = false)

        assertEquals(listOf("sendAdvertisement(false)"), radio.calls)
        assertEquals(0, location.requestCount)
    }

    @Test
    fun usesTheCachedFixWithoutPromptingWhenPromptingIsDisallowed() = runTest {
        enableAutoUpdate(GpsSource.PHONE)
        val location = FakeLocationService(
            status = LocationAuthorizationStatus.NOT_DETERMINED,
            locationResult = { LatLng(PHONE_LATITUDE, PHONE_LONGITUDE) },
        )
        location.currentLocation.value = LatLng(CACHED_LATITUDE, CACHED_LONGITUDE)
        val radio = RecordingRadio()

        sender(location).send(radio, flood = false, allowLocationPrompt = false)

        assertEquals(
            listOf("writeCoordinate($CACHED_LATITUDE, $CACHED_LONGITUDE)", "sendAdvertisement(false)"),
            radio.calls,
        )
        assertEquals(0, location.requestCount)
    }

    @Test
    fun asksForAFreshFixWhenAlreadyAuthorizedEvenWithoutPrompting() = runTest {
        enableAutoUpdate(GpsSource.PHONE)
        val location = FakeLocationService(
            status = LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE,
            locationResult = { LatLng(PHONE_LATITUDE, PHONE_LONGITUDE) },
        )
        val radio = RecordingRadio()

        sender(location).send(radio, flood = false, allowLocationPrompt = false)

        assertEquals(1, location.requestCount)
        assertEquals(
            listOf("writeCoordinate($PHONE_LATITUDE, $PHONE_LONGITUDE)", "sendAdvertisement(false)"),
            radio.calls,
        )
    }

    // A radio that rejects the coordinate write must still advertise: a stale position beats silence.
    @Test
    fun stillAdvertisesWhenTheCoordinateWriteFails() = runTest {
        enableAutoUpdate(GpsSource.PHONE)
        val location = FakeLocationService(locationResult = { LatLng(PHONE_LATITUDE, PHONE_LONGITUDE) })
        var reported: Throwable? = null
        val radio = RecordingRadio(onWriteCoordinate = { error("radio rejected the write") })

        sender(location) { reported = it }.send(radio, flood = false, allowLocationPrompt = true)

        assertTrue(radio.calls.contains("sendAdvertisement(false)"), "advert was blocked by the refresh")
        assertEquals("radio rejected the write", reported?.message)
    }

    @Test
    fun stillAdvertisesWhenNoFixIsAvailableAtAll() = runTest {
        enableAutoUpdate(GpsSource.PHONE)
        val location = FakeLocationService(locationResult = { error("no fix") })
        var reported: Throwable? = null
        val radio = RecordingRadio()

        sender(location) { reported = it }.send(radio, flood = false, allowLocationPrompt = true)

        assertEquals(listOf("sendAdvertisement(false)"), radio.calls)
        assertEquals("no fix", reported?.message)
    }

    @Test
    fun aCancelledCoordinateWriteSkipsTheAdvert() = runTest {
        enableAutoUpdate(GpsSource.PHONE)
        val location = FakeLocationService(locationResult = { LatLng(PHONE_LATITUDE, PHONE_LONGITUDE) })
        val cancelled = CancellationException("superseded")
        val radio = RecordingRadio(onWriteCoordinate = { throw cancelled })
        var reported: Throwable? = null

        val thrown = assertFailsWith<CancellationException> {
            sender(location) { reported = it }.send(radio, flood = false, allowLocationPrompt = true)
        }

        assertSame(cancelled, thrown)
        assertNull(reported)
        assertTrue(radio.calls.none { it.startsWith("sendAdvertisement") })
    }

    @Test
    fun anErrorFromTheCoordinateWriteSkipsTheAdvert() = runTest {
        enableAutoUpdate(GpsSource.PHONE)
        val location = FakeLocationService(locationResult = { LatLng(PHONE_LATITUDE, PHONE_LONGITUDE) })
        val fault = SelfAdvertRefreshProbeFault()
        val radio = RecordingRadio(onWriteCoordinate = { throw fault })
        var reported: Throwable? = null

        val thrown = assertFailsWith<SelfAdvertRefreshProbeFault> {
            sender(location) { reported = it }.send(radio, flood = false, allowLocationPrompt = true)
        }

        assertSame(fault, thrown)
        assertNull(reported)
        assertTrue(radio.calls.none { it.startsWith("sendAdvertisement") })
    }

    @Test
    fun reportsNoFailureOnACleanRefresh() = runTest {
        enableAutoUpdate(GpsSource.PHONE)
        var reported: Throwable? = null

        sender(onFailure = { reported = it }).send(RecordingRadio(), flood = false, allowLocationPrompt = true)

        assertNull(reported)
    }
}

private class SelfAdvertRefreshProbeFault : Error("probe")
