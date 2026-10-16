package one.meshcore.android

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.SelfInfo
import one.meshcore.shared.settings.DeviceGpsVerificationFailed
import one.meshcore.shared.settings.DeviceWriteVerificationFailed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Covers the manual map-picker write against iOS `SettingsService.setManualLocationVerified`: on-board
 * GPS off first, then a read-back that must agree with what was sent.
 */
class ManualLocationVerifiedWriteTest {
    /** Records the command order and answers `getSelfInfo` through [readBack]. */
    private class RecordingSession(
        initialGps: String?,
        private val readBack: (Double, Double) -> SelfInfo = { lat, lon -> FakeMeshSession.selfInfoAt(lat, lon) },
    ) : FakeMeshSession() {
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
            calls += "setCoordinates"
            written = latitude to longitude
        }

        override suspend fun getSelfInfo(): SelfInfo {
            calls += "getSelfInfo"
            val (lat, lon) = written ?: (0.0 to 0.0)
            return readBack(lat, lon)
        }
    }

    @Test
    fun disablesDeviceGpsBeforeWritingTheCoordinate() = runTest {
        val session = RecordingSession(initialGps = GPS_ON)

        session.setManualCoordinatesVerified(LATITUDE, LONGITUDE)

        val gpsOff = session.calls.indexOf("setCustomVar($GPS_VAR=$GPS_OFF)")
        val write = session.calls.indexOf("setCoordinates")
        assertTrue("device GPS was never switched off: ${session.calls}", gpsOff >= 0)
        assertTrue("coordinate written before the GPS was switched off: ${session.calls}", gpsOff < write)
    }

    @Test
    fun leavesGpsAloneWhenTheRadioReportsItOff() = runTest {
        val session = RecordingSession(initialGps = GPS_OFF)

        session.setManualCoordinatesVerified(LATITUDE, LONGITUDE)

        assertEquals(listOf("getCustomVars", "setCoordinates", "getSelfInfo"), session.calls)
    }

    @Test
    fun leavesGpsAloneWhenTheRadioDoesNotSupportIt() = runTest {
        val session = RecordingSession(initialGps = null)

        session.setManualCoordinatesVerified(LATITUDE, LONGITUDE)

        assertEquals(listOf("getCustomVars", "setCoordinates", "getSelfInfo"), session.calls)
    }

    @Test
    fun throwsWhenTheRadioReadsBackADifferentPosition() = runTest {
        // A radio still taking its own fix answers with its position, not the picked one.
        val session = RecordingSession(
            initialGps = GPS_OFF,
            readBack = { _, _ -> FakeMeshSession.selfInfoAt(OTHER_LATITUDE, OTHER_LONGITUDE) },
        )

        val failure = expectVerificationFailure { session.setManualCoordinatesVerified(LATITUDE, LONGITUDE) }

        assertEquals("($LATITUDE, $LONGITUDE)", failure.expected)
        assertEquals("($OTHER_LATITUDE, $OTHER_LONGITUDE)", failure.actual)
    }

    @Test
    fun acceptsAReadBackInsideTheScaledTolerance() = runTest {
        val session = RecordingSession(
            initialGps = GPS_OFF,
            readBack = { lat, lon -> FakeMeshSession.selfInfoAt(lat + ONE_SCALED_UNIT, lon) },
        )

        session.setManualCoordinatesVerified(LATITUDE, LONGITUDE)

        assertTrue(session.calls.contains("getSelfInfo"))
    }

    @Test
    fun throwsWhenTheRadioRejectsTheGpsToggle() = runTest {
        val session = object : FakeMeshSession() {
            override suspend fun getCustomVars(): Map<String, String> = mapOf(GPS_VAR to GPS_ON)

            override suspend fun setCustomVar(key: String, value: String) = Unit
        }

        try {
            session.setManualCoordinatesVerified(LATITUDE, LONGITUDE)
            fail("expected DeviceGpsVerificationFailed")
        } catch (failure: DeviceGpsVerificationFailed) {
            assertEquals(false, failure.expectedEnabled)
            assertEquals(true, failure.actualEnabled)
        }
    }

    private suspend fun expectVerificationFailure(body: suspend () -> Unit): DeviceWriteVerificationFailed {
        try {
            body()
        } catch (failure: DeviceWriteVerificationFailed) {
            return failure
        }
        fail("expected DeviceWriteVerificationFailed")
        error("unreachable")
    }

    private companion object {
        const val GPS_VAR = "gps"
        const val GPS_ON = "1"
        const val GPS_OFF = "0"
        const val LATITUDE = 52.5163
        const val LONGITUDE = 13.3777
        const val OTHER_LATITUDE = 1.0
        const val OTHER_LONGITUDE = 2.0

        /** One unit at the 1e-6 coordinate scale, inside the two-unit iOS tolerance. */
        const val ONE_SCALED_UNIT = 0.000001
    }
}
