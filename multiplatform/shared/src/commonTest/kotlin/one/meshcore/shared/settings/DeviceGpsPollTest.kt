package one.meshcore.shared.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Polling waits for a first device fix and stops as soon as any input to that wait is gone. */
class DeviceGpsPollTest {
    @Test
    fun pollsWhileAutoUpdateUsesTheDeviceGpsAndARefreshExists() {
        assertTrue(
            shouldPollDeviceGps(
                autoUpdateLocation = true,
                gpsSource = GpsSource.DEVICE,
                deviceGpsEnabled = true,
                hasRefresh = true,
            ),
        )
    }

    @Test
    fun doesNotPollWhenAutoUpdateIsOff() {
        assertFalse(polling(autoUpdateLocation = false))
    }

    @Test
    fun doesNotPollWhenTheSourceIsThePhone() {
        assertFalse(polling(gpsSource = GpsSource.PHONE))
    }

    @Test
    fun doesNotPollWhenDeviceGpsIsOff() {
        assertFalse(polling(deviceGpsEnabled = false))
    }

    @Test
    fun doesNotPollWithoutARefreshCallback() {
        assertFalse(polling(hasRefresh = false))
    }

    private fun polling(
        autoUpdateLocation: Boolean = true,
        gpsSource: GpsSource = GpsSource.DEVICE,
        deviceGpsEnabled: Boolean = true,
        hasRefresh: Boolean = true,
    ): Boolean = shouldPollDeviceGps(
        autoUpdateLocation = autoUpdateLocation,
        gpsSource = gpsSource,
        deviceGpsEnabled = deviceGpsEnabled,
        hasRefresh = hasRefresh,
    )
}
