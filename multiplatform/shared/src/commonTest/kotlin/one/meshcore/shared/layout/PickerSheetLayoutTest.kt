package one.meshcore.shared.layout

import kotlin.test.Test
import kotlin.test.assertEquals

class PickerSheetLayoutTest {
    @Test
    fun compactWindowsKeepThePhoneDetentFraction() {
        assertEquals(0.9f, PickerSheetLayout.heightFraction(390f, compactFraction = 0.9f))
        assertEquals(0.85f, PickerSheetLayout.heightFraction(599f, compactFraction = 0.85f))
    }

    @Test
    fun mediumWindowsCapPickerHeight() {
        assertEquals(
            PickerSheetLayout.MEDIUM_HEIGHT_FRACTION,
            PickerSheetLayout.heightFraction(600f, compactFraction = 0.9f),
        )
        assertEquals(
            PickerSheetLayout.MEDIUM_HEIGHT_FRACTION,
            PickerSheetLayout.heightFraction(840f, compactFraction = 0.92f),
        )
    }
}
