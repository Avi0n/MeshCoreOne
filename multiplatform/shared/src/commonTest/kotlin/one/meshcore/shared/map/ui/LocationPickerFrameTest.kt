package one.meshcore.shared.map.ui

import kotlinx.coroutines.flow.MutableStateFlow
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.PinStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LocationPickerFrameTest {
    private val phone = LatLng(47.62, -122.35)

    @Test
    fun aKnownPositionOpensFramedAndAnEmptyOneDoesNot() {
        assertNull(locationPickerInitialCamera(null))
        assertEquals(
            MapCameraRegion(center = phone, spanLatDeg = DEFAULT_SPAN_DEG, spanLonDeg = DEFAULT_SPAN_DEG),
            locationPickerInitialCamera(phone),
        )
    }

    @Test
    fun thePhoneFixUsesTheServiceFlowOrTheEmptyFallback() {
        val fallback = MutableStateFlow<LatLng?>(null)
        val serviceFlow = MutableStateFlow<LatLng?>(phone)
        assertSame(fallback, locationPickerPhoneFixFlow(null, fallback))
        assertSame(serviceFlow, locationPickerPhoneFixFlow(serviceFlow, fallback))
    }

    @Test
    fun framingRequestsAFixOrFramesThePhoneAndOtherwiseDoesNeither() {
        assertEquals(emptyList(), framingEffects(LocationPickerDeviceFraming.NONE, phone))
        assertEquals(listOf("request"), framingEffects(LocationPickerDeviceFraming.REQUEST_FIX, null))
        assertEquals(listOf("frame"), framingEffects(LocationPickerDeviceFraming.FRAME_ON_FIX, phone))
        assertEquals(emptyList(), framingEffects(LocationPickerDeviceFraming.FRAME_ON_FIX, null))
    }

    @Test
    fun aSelectedPinIsTheOnlyMarker() {
        assertEquals(emptyList(), locationPickerPoints(null))
        val points = locationPickerPoints(phone)
        assertEquals(1, points.size)
        assertEquals(PICKER_MARKER_ID, points.single().id)
        assertEquals(phone, points.single().coordinate)
        assertEquals(PinStyle.CROSSHAIR, points.single().pinStyle)
        assertNull(points.single().label)
        assertFalse(points.single().isClusterable)
    }

    @Test
    fun anEmptyPinSavesTheClearedPosition() {
        assertEquals(CLEARED_LOCATION, locationPickerSaveCoordinate(null))
        assertEquals(phone, locationPickerSaveCoordinate(phone))
    }

    @Test
    fun aSuccessfulWriteDismissesAndARefusalStays() {
        var dismissed = false
        var stayed: Throwable? = null
        locationPickerAfterSave(LocationPickerSaveResult.Dismiss, { dismissed = true }) { stayed = it }
        assertTrue(dismissed)
        assertNull(stayed)

        val error = IllegalStateException("full")
        dismissed = false
        locationPickerAfterSave(LocationPickerSaveResult.Stay(error), { dismissed = true }) { stayed = it }
        assertFalse(dismissed)
        assertSame(error, stayed)
    }

    private fun framingEffects(framing: LocationPickerDeviceFraming, phoneFix: LatLng?): List<String> {
        val effects = mutableListOf<String>()
        applyLocationPickerFraming(
            framing = framing,
            phoneFix = phoneFix,
            requestFix = { effects += "request" },
            frameOn = { region ->
                effects += "frame"
                assertEquals(locationPickerInitialCamera(phoneFix), region)
            },
        )
        return effects
    }
}
