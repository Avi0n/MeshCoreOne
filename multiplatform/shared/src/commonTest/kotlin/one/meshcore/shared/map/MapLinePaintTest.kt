package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapLinePaintTest {
    @Test
    fun losAndUnprovenHopsStayDashedSoTheyCannotBeReadAsAMeasuredRoute() {
        val los = LineStyle.LOS.mapLinePaint()
        val untraced = LineStyle.TRACE_UNTRACED.mapLinePaint()
        val weak = LineStyle.TRACE_WEAK.mapLinePaint()
        assertEquals(listOf(1.4f, 2.6f), los.dash)
        assertEquals(listOf(1.75f, 3.25f), untraced.dash)
        assertEquals(los.dash, weak.dash)
        assertEquals(listOf(0.7f, 1.3f), los.casingDash)
        assertNotEquals(los.dash, untraced.dash, "an untraced hop must not share the LoS dash")
    }

    @Test
    fun goodHopsAndMessagePathsStaySolid() {
        val good = LineStyle.TRACE_GOOD.mapLinePaint()
        val message = LineStyle.MESSAGE_PATH.mapLinePaint()
        assertNull(good.dash)
        assertNull(message.dash)
        assertNull(good.casingDash)
        assertNull(message.casingDash)
        assertEquals(4f, good.width)
        assertEquals(7f, good.casingWidth)
        assertEquals(3f, message.width)
        assertEquals(6f, message.casingWidth)
    }

    @Test
    fun locationTrailKeepsItsOwnDashAndDoesNotBorrowTheRouteCasing() {
        val trail = LineStyle.LOCATION_TRAIL.mapLinePaint()
        assertEquals(2.5f, trail.width)
        assertEquals(listOf(1.6f, 2.8f), trail.dash)
        assertEquals(listOf(0.8f, 1.4f), trail.casingDash)
        assertNotEquals(
            LineStyle.MESSAGE_PATH.mapLinePaint().dash,
            trail.dash,
            "a sampled trail must not draw as a proven node-to-node path",
        )
    }

    @Test
    fun everyStyleGetsACasingWiderThanItsStroke() {
        for (style in LineStyle.entries) {
            val paint = style.mapLinePaint()
            assertTrue(paint.casingWidth > paint.width, "$style casing must outrun the stroke")
        }
    }
}
