package one.meshcore.shared.map

import one.meshcore.shared.remotenode.LocalizedLengthSymbols
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapScaleBarTest {
    @Test
    fun equatorDegreeIsTwentyKilometersMetric() {
        val model = mapScaleBarModel(equatorDegree(), useMetric = true)
        assertNotNull(model)
        assertEquals("20 km", model.label)
        assertTrue(model.fractionOfViewport in 0.15f..0.25f)
    }

    @Test
    fun equatorDegreeUsesInjectedKilometerToken() {
        val cyrillic = LocalizedLengthSymbols(
            kilometer = "км",
            meter = "м",
            mile = "мили",
            foot = "фут",
        )
        val model = mapScaleBarModel(equatorDegree(), useMetric = true, symbols = cyrillic)
        assertNotNull(model)
        assertEquals("20 км", model.label)
    }

    @Test
    fun equatorDegreeIsTwentyMilesImperial() {
        val model = mapScaleBarModel(equatorDegree(), useMetric = false)
        assertNotNull(model)
        assertEquals("20 mi", model.label)
        assertTrue(model.fractionOfViewport in 0.25f..0.35f)
    }

    @Test
    fun shortImperialViewportUsesFeet() {
        val model = mapScaleBarModel(
            MapCameraRegion(center = LatLng(0.0, 0.0), spanLatDeg = 0.01, spanLonDeg = 0.01),
            useMetric = false,
        )
        assertNotNull(model)
        assertEquals("1000 ft", model.label)
        assertTrue(model.fractionOfViewport in 0.25f..0.30f)
    }

    @Test
    fun emptySpanHasNoBar() {
        assertNull(
            mapScaleBarModel(
                MapCameraRegion(center = LatLng(0.0, 0.0), spanLatDeg = 0.0, spanLonDeg = 0.0),
                useMetric = true,
            ),
        )
    }

    @Test
    fun usAndGbUseImperial() {
        assertFalse(localeUsesMetric("US"))
        assertFalse(localeUsesMetric("GB"))
        assertTrue(localeUsesMetric("DE"))
        assertTrue(localeUsesMetric(""))
    }

    @Test
    fun niceNumberDoesNotExceedMax() {
        assertEquals(5.0, niceNumberAtMost(7.0))
        assertEquals(20.0, niceNumberAtMost(27.0))
    }

    private fun equatorDegree() = MapCameraRegion(
        center = LatLng(0.0, 0.0),
        spanLatDeg = 1.0,
        spanLonDeg = 1.0,
    )
}
