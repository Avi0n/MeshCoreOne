package one.meshcore.shared.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phone-GPS writes that iOS `LocationSettingsSection` rejects once location is denied.
 */
class PhoneGpsPreferenceTest {
    @Test
    fun turningAutoUpdateOnWhileTheSourceIsPhoneIsBlockedWhenDenied() {
        assertTrue(
            shouldBlockPhoneGpsPreference(
                wantsPhoneGps = true,
                locationDenied = true,
            ),
        )
    }

    @Test
    fun turningAutoUpdateOnIsAllowedWhenLocationIsNotDenied() {
        assertFalse(
            shouldBlockPhoneGpsPreference(
                wantsPhoneGps = true,
                locationDenied = false,
            ),
        )
    }

    @Test
    fun turningAutoUpdateOffIsNeverBlocked() {
        assertFalse(
            shouldBlockPhoneGpsPreference(
                wantsPhoneGps = false,
                locationDenied = true,
            ),
        )
    }

    @Test
    fun switchingTheSourceToDeviceIsNeverBlocked() {
        assertFalse(
            shouldBlockPhoneGpsPreference(
                wantsPhoneGps = GpsSource.DEVICE == GpsSource.PHONE,
                locationDenied = true,
            ),
        )
    }
}
