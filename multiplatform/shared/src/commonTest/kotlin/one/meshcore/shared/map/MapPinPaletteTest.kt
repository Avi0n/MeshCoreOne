package one.meshcore.shared.map

import one.meshcore.shared.map.ui.MapLibreChrome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MapPinPaletteTest {
    @Test
    fun pinFillsMatchIosSpriteRenderer() {
        assertEquals(MapLibreChrome.PinRgb(204, 122, 92), MapPinPalette.CHAT)
        assertEquals(MapLibreChrome.PinRgb(50, 173, 230), MapPinPalette.REPEATER)
        assertEquals(MapLibreChrome.PinRgb(255, 136, 0), MapPinPalette.ROOM)
        assertEquals(MapLibreChrome.PinRgb(255, 45, 85), MapPinPalette.DROPPED)
        assertEquals(MapLibreChrome.PinRgb(0, 122, 255), MapPinPalette.BLUE)
        assertEquals(MapLibreChrome.PinRgb(52, 199, 89), MapPinPalette.GREEN)
    }

    @Test
    fun pinFillsAreNotMaterialBaselineChrome() {
        assertNotEquals(MapLibreChrome.PRIMARY, MapPinPalette.BLUE)
        assertNotEquals(MapLibreChrome.TERTIARY, MapPinPalette.REPEATER)
        assertNotEquals(MapLibreChrome.SECONDARY, MapPinPalette.DROPPED)
    }
}
