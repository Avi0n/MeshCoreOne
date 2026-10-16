package one.meshcore.shared.map.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MapLibreChromeTest {
    @Test
    fun marginsMatchIosMapViewChrome() {
        assertEquals(8, MapLibreChrome.COMPASS_MARGIN_DP)
        assertEquals(4, MapLibreChrome.ATTRIBUTION_MARGIN_START_DP)
        assertEquals(30, MapLibreChrome.ATTRIBUTION_MARGIN_BOTTOM_DP)
    }

    @Test
    fun pinChromeIsNotAppleSystemPalette() {
        assertNotEquals(MapLibreChrome.PinRgb(0, 122, 255), MapLibreChrome.PRIMARY)
        assertNotEquals(MapLibreChrome.PinRgb(52, 199, 89), MapLibreChrome.TERTIARY)
        assertNotEquals(MapLibreChrome.PinRgb(255, 59, 48), MapLibreChrome.ERROR)
        assertNotEquals(MapLibreChrome.PinRgb(50, 173, 230), MapLibreChrome.SECONDARY)
        assertNotEquals(MapLibreChrome.PinRgb(175, 82, 222), MapLibreChrome.SECONDARY)
    }

    @Test
    fun pinChromeUsesMaterialBaselineRoles() {
        assertEquals(MapLibreChrome.PinRgb(103, 80, 164), MapLibreChrome.PRIMARY)
        assertEquals(MapLibreChrome.PinRgb(98, 91, 113), MapLibreChrome.SECONDARY)
        assertEquals(MapLibreChrome.PinRgb(125, 82, 96), MapLibreChrome.TERTIARY)
        assertEquals(MapLibreChrome.PinRgb(179, 38, 30), MapLibreChrome.ERROR)
        assertEquals(MapLibreChrome.PinRgb(121, 116, 126), MapLibreChrome.MUTED)
    }
}
