package one.meshcore.android

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.SelfInfo
import one.meshcore.shared.settings.AdvertLocationPolicy
import one.meshcore.shared.settings.LocationSettingsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the radio side of the pre-advert location refresh: the coordinate write mirrors iOS
 * `SettingsService.setLocationVerified` rather than the manual-picker variant, so a phone-sourced refresh
 * never switches off an on-board GPS the user chose.
 */
class SelfAdvertRadioTest {
    /** Records the command order and answers `getSelfInfo` with whatever was written. */
    private class RecordingSession(private val initialGps: String? = null) : FakeMeshSession() {
        val calls = mutableListOf<String>()
        private var gps = initialGps
        private var written: Pair<Double, Double>? = null

        override suspend fun getCustomVars(): Map<String, String> {
            calls += "getCustomVars"
            return gps?.let { mapOf(GPS_VAR to it) } ?: emptyMap()
        }

        override suspend fun setCustomVar(key: String, value: String) {
            calls += "setCustomVar($key=$value)"
            if (key == GPS_VAR) gps = value
        }

        override suspend fun setCoordinates(latitude: Double, longitude: Double) {
            calls += "setCoordinates($latitude, $longitude)"
            written = latitude to longitude
        }

        override suspend fun getSelfInfo(): SelfInfo {
            calls += "getSelfInfo"
            val (lat, lon) = written ?: (0.0 to 0.0)
            return selfInfoAt(lat, lon)
        }

        override suspend fun sendAdvertisement(flood: Boolean) {
            calls += "sendAdvertisement($flood)"
        }
    }

    private fun radio(
        session: RecordingSession,
        deviceId: String? = DEVICE_ID,
        policy: Int = AdvertLocationPolicy.PREFS.rawValue,
        persist: (Double, Double) -> Unit = { _, _ -> },
    ) = SessionSelfAdvertRadio(
        session = session,
        resolveDeviceId = { deviceId },
        readLocationSettings = { LocationSettingsSnapshot(0.0, 0.0, policy) },
        persistCoordinates = persist,
    )

    @Test
    fun writesTheCoordinateWithoutTouchingTheOnBoardGps() = runTest {
        val session = RecordingSession(initialGps = GPS_ON)

        radio(session).writeCoordinate(LATITUDE, LONGITUDE)

        assertEquals(listOf("setCoordinates($LATITUDE, $LONGITUDE)", "getSelfInfo"), session.calls)
    }

    @Test
    fun persistsTheVerifiedCoordinateOnTheDeviceRow() = runTest {
        val session = RecordingSession()
        val persisted = mutableListOf<Pair<Double, Double>>()

        radio(session, persist = { lat, lon -> persisted += lat to lon })
            .writeCoordinate(LATITUDE, LONGITUDE)

        assertEquals(listOf(LATITUDE to LONGITUDE), persisted)
    }

    @Test
    fun switchesTheOnBoardGpsOnAndProvesIt() = runTest {
        val session = RecordingSession(initialGps = GPS_OFF)

        radio(session).enableDeviceGps()

        assertEquals(listOf("setCustomVar($GPS_VAR=$GPS_ON)", "getCustomVars"), session.calls)
    }

    @Test
    fun readsTheOnBoardGpsState() = runTest {
        assertTrue(radio(RecordingSession(initialGps = GPS_ON)).deviceGpsState().isEnabled)
        assertFalse(radio(RecordingSession(initialGps = GPS_OFF)).deviceGpsState().isEnabled)
        assertFalse(radio(RecordingSession(initialGps = null)).deviceGpsState().isSupported)
    }

    @Test
    fun readsTheSharingGateFromTheDeviceRowPolicy() = runTest {
        val session = RecordingSession()
        assertFalse(radio(session, policy = AdvertLocationPolicy.NONE.rawValue).sharesLocationPublicly())
        assertTrue(radio(session, policy = AdvertLocationPolicy.PREFS.rawValue).sharesLocationPublicly())
        assertTrue(radio(session, policy = AdvertLocationPolicy.SHARE.rawValue).sharesLocationPublicly())
    }

    @Test
    fun passesTheDeviceRowIdThePreferencesAreKeyedBy() = runTest {
        assertEquals(DEVICE_ID, radio(RecordingSession()).deviceId())
    }

    @Test
    fun forwardsTheAdvertReachToTheRadio() = runTest {
        val session = RecordingSession()

        radio(session).sendAdvertisement(flood = true)

        assertEquals(listOf("sendAdvertisement(true)"), session.calls)
    }

    private companion object {
        const val GPS_VAR = "gps"
        const val GPS_ON = "1"
        const val GPS_OFF = "0"
        const val LATITUDE = 52.5163
        const val LONGITUDE = 13.3777
        const val DEVICE_ID = "8C4A1E2F-0000-4000-8000-000000000001"
    }
}
