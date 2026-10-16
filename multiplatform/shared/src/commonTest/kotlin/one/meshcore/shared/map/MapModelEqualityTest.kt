package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The diffing contracts the renderer relies on: [MapPoint] compares field-by-field, while [MapLine]
 * compares id + style + opacity + coordinates but ignores the draw-order [MapLine.pathIndex].
 */
class MapModelEqualityTest {
    private fun point(label: String? = "A", hop: Int? = null) = MapPoint(
        id = "p1",
        coordinate = LatLng(1.0, 2.0),
        pinStyle = PinStyle.CONTACT_CHAT,
        label = label,
        isClusterable = true,
        hopIndex = hop,
    )

    @Test
    fun mapPointComparesEveryField() {
        assertEquals(point(), point())
        assertNotEquals(point(label = "A"), point(label = "B"))
        assertNotEquals(point(hop = null), point(hop = 1))
        assertNotEquals(
            point(),
            point().copy(coordinate = LatLng(9.0, 9.0)),
        )
    }

    @Test
    fun mapLineIgnoresPathIndexButComparesCoordinates() {
        val a = MapLine("l1", listOf(LatLng(0.0, 0.0), LatLng(1.0, 1.0)), LineStyle.LOS, opacity = 1.0, pathIndex = 0)
        val b = MapLine("l1", listOf(LatLng(0.0, 0.0), LatLng(1.0, 1.0)), LineStyle.LOS, opacity = 1.0, pathIndex = 9)
        val different =
            MapLine("l1", listOf(LatLng(0.0, 0.0), LatLng(2.0, 2.0)), LineStyle.LOS, opacity = 1.0, pathIndex = 0)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, different)
    }

    @Test
    fun mapLineComparesStyleAndOpacity() {
        val base = MapLine("l1", listOf(LatLng(0.0, 0.0)), LineStyle.LOS, opacity = 1.0)

        assertNotEquals(base, MapLine("l1", listOf(LatLng(0.0, 0.0)), LineStyle.MESSAGE_PATH, opacity = 1.0))
        assertNotEquals(base, MapLine("l1", listOf(LatLng(0.0, 0.0)), LineStyle.LOS, opacity = 0.5))
    }
}
