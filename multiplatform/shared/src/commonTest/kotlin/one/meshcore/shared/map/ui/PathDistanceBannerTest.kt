package one.meshcore.shared.map.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class PathDistanceBannerTest {
    @Test
    fun joinOmitsBulletWhenDistanceIsMissing() {
        assertEquals("3 hops", joinPathDistanceBanner("3 hops", formattedDistance = null))
    }

    @Test
    fun joinPutsCommaBetweenHopsAndDistance() {
        assertEquals("3 hops, 1.2 km", joinPathDistanceBanner("3 hops", "1.2 km"))
    }

    @Test
    fun incompleteLabelIsIncludedForScreenReaders() {
        assertEquals(
            "3 hops, 1.2 km, Path distance may be incomplete",
            joinPathDistanceBanner("3 hops", "1.2 km", "Path distance may be incomplete"),
        )
    }

    @Test
    fun compactPreviewCanShowDistanceWithoutRepeatingHopCount() {
        assertEquals("1.2 km", joinPathDistanceBanner(null, "1.2 km"))
    }

    @Test
    fun traceBannerHopCountExcludesStartAndEnd() {
        assertEquals(0, tracePathBannerHopCount(2))
        assertEquals(3, tracePathBannerHopCount(5))
    }
}
