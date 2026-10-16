package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MapFeaturePropertiesTest {
    private val here = LatLng(47.6, -122.3)
    private val there = LatLng(47.7, -122.2)

    @Test
    fun aHopPointCarriesItsOrdinalAndItsNumberedSprite() {
        val point = MapPoint(
            id = "path-hop-2",
            coordinate = here,
            pinStyle = PinStyle.REPEATER_HOP,
            label = "Ridge",
            isClusterable = false,
            hopIndex = 2,
        )
        val properties = point.featureProperties()
        assertEquals(2, properties.hopIndex, "the hop ordinal must reach the renderer")
        assertEquals("${MapPinIcon.REPEATER_HOP_PREFIX}2", properties.icon)
        assertEquals("Ridge", properties.label)
        assertNull(properties.badgeText)
    }

    @Test
    fun anSnrBadgePointCarriesItsPillTextAndNoOrdinal() {
        val badge = snrBadge(
            id = "trace-badge-1",
            from = here,
            to = there,
            snr = 5.5,
            formatter = MetricSnrBadgeFormatter(),
        )
        val properties = badge.featureProperties()
        assertEquals(badge.badgeText, properties.badgeText, "the pill label must reach the renderer")
        assertEquals(MapPinIcon.BADGE, properties.icon)
        assertNull(properties.hopIndex)
    }

    @Test
    fun aPlainContactCarriesNeitherOrdinalNorBadge() {
        val point = MapPoint(
            id = "contact-1",
            coordinate = here,
            pinStyle = PinStyle.CONTACT_CHAT,
            label = "Base",
            isClusterable = true,
        )
        val properties = point.featureProperties()
        assertNull(properties.hopIndex)
        assertNull(properties.badgeText)
        assertEquals(PinStyle.CONTACT_CHAT.rawValue, properties.pinStyle)
    }

    /**
     * A teardrop centred on its coordinate points at a spot half an icon away from the node it marks,
     * which on a map is a wrong position rather than a styling difference.
     */
    @Test
    fun aTeardropAnchorsOnItsTipAndAFlatMarkerOnItsCentre() {
        fun anchorOf(style: PinStyle) = MapPoint(
            id = "p",
            coordinate = here,
            pinStyle = style,
            label = null,
            isClusterable = false,
        ).featureProperties().anchor

        assertEquals(MapPinAnchor.BOTTOM.rawValue, anchorOf(PinStyle.CONTACT_CHAT))
        assertEquals(MapPinAnchor.BOTTOM.rawValue, anchorOf(PinStyle.DROPPED_PIN))
        assertEquals(MapPinAnchor.BOTTOM.rawValue, anchorOf(PinStyle.LOCATION_FIX_LATEST))
        assertEquals(MapPinAnchor.CENTER.rawValue, anchorOf(PinStyle.CROSSHAIR))
        assertEquals(MapPinAnchor.CENTER.rawValue, anchorOf(PinStyle.OBSTRUCTION))
        assertEquals(MapPinAnchor.CENTER.rawValue, anchorOf(PinStyle.LOCATION_FIX))
    }

    @Test
    fun everyLineCarriesItsOwnOpacity() {
        val dim = MapLine(id = "los-ar", coordinates = listOf(here, there), style = LineStyle.LOS, opacity = 0.25)
        val active = MapLine(id = "los-rb", coordinates = listOf(here, there), style = LineStyle.LOS, opacity = 0.9)
        assertEquals(0.25, dim.featureProperties().opacity, "the dimmed segment must reach the renderer dimmed")
        assertEquals(0.9, active.featureProperties().opacity)
        assertEquals(LineStyle.LOS.rawValue, dim.featureProperties().lineStyle)
    }

    @Test
    fun anUnusableOpacityNormalizesBeforeItReachesTheNativeRenderer() {
        val nan = MapLine(id = "l", coordinates = listOf(here, there), style = LineStyle.LOS, opacity = Double.NaN)
        val over = MapLine(id = "l", coordinates = listOf(here, there), style = LineStyle.LOS, opacity = 4.0)
        val under = MapLine(id = "l", coordinates = listOf(here, there), style = LineStyle.LOS, opacity = -1.0)
        assertEquals(1.0, nan.featureProperties().opacity)
        assertEquals(1.0, over.featureProperties().opacity)
        assertEquals(0.0, under.featureProperties().opacity)
    }
}
