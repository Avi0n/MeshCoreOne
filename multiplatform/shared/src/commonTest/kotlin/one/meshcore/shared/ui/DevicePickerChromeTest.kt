package one.meshcore.shared.ui

import one.meshcore.shared.connection.BleSignalTier
import kotlin.test.Test
import kotlin.test.assertEquals

class DevicePickerChromeTest {
    @Test
    fun strongerRssiFillsMoreBars() {
        assertEquals(4, BleSignalTier.STRONG.filledBarCount)
        assertEquals(3, BleSignalTier.MEDIUM.filledBarCount)
        assertEquals(2, BleSignalTier.WEAK.filledBarCount)
    }

    @Test
    fun transportGlyphUsesMaterialListItemIconSize() {
        assertEquals(24, DEVICE_TRANSPORT_GLYPH_SIZE.value.toInt())
    }
}
