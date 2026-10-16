package one.meshcore.shared.map.ui

import one.meshcore.shared.map.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the picker does with the phone's own fix, mirroring iOS `loadCurrentLocation` case 2 and the
 * late-fix `onChange`: a node that already holds a position wins, an authorized phone with a fix frames
 * it, an authorized phone without one asks for it, and a map the user has already moved is left alone.
 */
class LocationPickerDeviceFramingTest {
    private val phone = LatLng(47.62, -122.35)
    private val saved = LatLng(37.77, -122.41)

    @Test
    fun anUnsetPositionFramesThePhoneFix() {
        assertEquals(
            LocationPickerDeviceFraming.FRAME_ON_FIX,
            locationPickerDeviceFraming(
                known = null,
                phoneFix = phone,
                isAuthorized = true,
                hasCamera = false,
                isRequestingFix = false,
            ),
        )
    }

    @Test
    fun anAuthorizedPhoneWithNoFixYetAsksForOne() {
        assertEquals(
            LocationPickerDeviceFraming.REQUEST_FIX,
            locationPickerDeviceFraming(
                known = null,
                phoneFix = null,
                isAuthorized = true,
                hasCamera = false,
                isRequestingFix = false,
            ),
        )
    }

    @Test
    fun aRequestAlreadyInFlightIsNotRepeated() {
        assertEquals(
            LocationPickerDeviceFraming.NONE,
            locationPickerDeviceFraming(
                known = null,
                phoneFix = null,
                isAuthorized = true,
                hasCamera = false,
                isRequestingFix = true,
            ),
        )
    }

    @Test
    fun theNodesOwnPositionOutranksThePhoneFix() {
        assertEquals(
            LocationPickerDeviceFraming.NONE,
            locationPickerDeviceFraming(
                known = saved,
                phoneFix = phone,
                isAuthorized = true,
                hasCamera = true,
                isRequestingFix = false,
            ),
        )
    }

    @Test
    fun anUnauthorizedPhoneIsNeverAsked() {
        assertEquals(
            LocationPickerDeviceFraming.NONE,
            locationPickerDeviceFraming(
                known = null,
                phoneFix = null,
                isAuthorized = false,
                hasCamera = false,
                isRequestingFix = false,
            ),
        )
    }

    /** A late fix must not yank the map out from under a user who has already panned it somewhere. */
    @Test
    fun aMapTheUserHasMovedKeepsItsFraming() {
        assertEquals(
            LocationPickerDeviceFraming.NONE,
            locationPickerDeviceFraming(
                known = null,
                phoneFix = phone,
                isAuthorized = true,
                hasCamera = true,
                isRequestingFix = false,
            ),
        )
    }
}
