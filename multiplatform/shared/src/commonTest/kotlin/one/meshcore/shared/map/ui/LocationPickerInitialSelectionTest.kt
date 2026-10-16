package one.meshcore.shared.map.ui

import one.meshcore.shared.map.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The picker's three opening cases, mirroring iOS `LocationPickerView.loadCurrentLocation`. Only a
 * usable saved position selects a pin; everything else opens with nothing chosen, so the picker never
 * offers a position the node does not hold.
 */
class LocationPickerInitialSelectionTest {
    @Test
    fun aUsableSavedPositionOpensSelected() {
        val saved = LatLng(47.62, -122.35)
        assertEquals(saved, locationPickerInitialSelection(saved))
    }

    /**
     * The radio writes all-zero coordinates for "no manual location". Treating them as a real position
     * drops a pin on null island and hands it back on the next save.
     */
    @Test
    fun anUnsetPositionOpensWithNothingSelected() {
        assertNull(locationPickerInitialSelection(null))
        assertNull(locationPickerInitialSelection(LatLng(0.0, 0.0)))
    }

    @Test
    fun anOutOfRangePositionOpensWithNothingSelected() {
        assertNull(locationPickerInitialSelection(LatLng(91.0, 10.0)))
        assertNull(locationPickerInitialSelection(LatLng(10.0, 181.0)))
    }

    @Test
    fun coordinateTextRequiresBothFieldsBeforeSeedingThePicker() {
        assertNull(locationPickerInitialFromText("", "12.5"))
        assertNull(locationPickerInitialFromText("48.1", ""))
        assertNull(locationPickerInitialFromText("invalid", "12.5"))
        assertEquals(LatLng(48.1, 12.5), locationPickerInitialFromText("48.1", "12.5"))
    }
}
