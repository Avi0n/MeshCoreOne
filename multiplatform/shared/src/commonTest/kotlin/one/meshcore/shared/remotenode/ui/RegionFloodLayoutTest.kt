package one.meshcore.shared.remotenode.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** Mirrors iOS `RegionFloodToggleRowLayoutTests`. */
class RegionFloodLayoutTest {
    @Test
    fun visibleDepthIsZeroForTheRoot() {
        assertEquals(0, visibleRegionDepth(0))
        assertEquals(0, visibleRegionDepth(-1))
    }

    @Test
    fun visibleDepthCapsAtFourWhenIndentIsTheDefault() {
        assertEquals(3, visibleRegionDepth(3))
        assertEquals(4, visibleRegionDepth(5))
    }

    @Test
    fun visibleDepthCapsByGutterWidthAtLargeType() {
        assertEquals(2, visibleRegionDepth(5, indentPerLevelDp = 24))
        assertEquals(1, visibleRegionDepth(5, indentPerLevelDp = 48))
    }
}
