package one.meshcore.android

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.settings.DeviceGpsVerificationFailed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings → Location device-GPS seams the host exposes: the capability/state read that decides
 * whether the section appears at all, and the verified toggle write. Mirrors iOS
 * `SettingsService.getDeviceGPSState` / `setDeviceGPSEnabledVerified`.
 */
class DeviceGpsSeamTest {
    /** Answers `gps` from a mutable slot; a null slot is a radio with no on-board GPS. */
    private class GpsVarSession(private var gps: String?, private val acceptsWrites: Boolean = true) :
        FakeMeshSession() {
        override suspend fun getCustomVars(): Map<String, String> = gps?.let { mapOf(GPS_VAR to it) } ?: emptyMap()

        override suspend fun setCustomVar(key: String, value: String) {
            if (acceptsWrites && key == GPS_VAR) gps = value
        }
    }

    @Test
    fun aRadioWithoutTheGpsVarReportsNoOnBoardGps() = runTest {
        val state = GpsVarSession(gps = null).deviceGpsState()

        assertFalse(state.isSupported)
        assertFalse(state.isEnabled)
    }

    @Test
    fun aRadioReportingTheGpsOffIsSupportedButDisabled() = runTest {
        val state = GpsVarSession(gps = GPS_OFF).deviceGpsState()

        assertTrue(state.isSupported)
        assertFalse(state.isEnabled)
    }

    @Test
    fun aRadioReportingTheGpsOnIsSupportedAndEnabled() = runTest {
        val state = GpsVarSession(gps = GPS_ON).deviceGpsState()

        assertTrue(state.isSupported)
        assertTrue(state.isEnabled)
    }

    @Test
    fun theToggleWriteIsVisibleToTheFollowingRead() = runTest {
        val session = GpsVarSession(gps = GPS_OFF)

        session.setDeviceGpsEnabledVerified(true)

        assertTrue(session.deviceGpsState().isEnabled)
    }

    @Test
    fun aRefusedToggleThrowsRatherThanReportingSuccess() = runTest {
        val session = GpsVarSession(gps = GPS_OFF, acceptsWrites = false)

        val failure = assertThrows(DeviceGpsVerificationFailed::class.java) {
            kotlinx.coroutines.runBlocking { session.setDeviceGpsEnabledVerified(true) }
        }

        assertTrue(failure.expectedEnabled)
        assertFalse(failure.actualEnabled)
    }

    private companion object {
        const val GPS_VAR = "gps"
        const val GPS_ON = "1"
        const val GPS_OFF = "0"
    }
}
