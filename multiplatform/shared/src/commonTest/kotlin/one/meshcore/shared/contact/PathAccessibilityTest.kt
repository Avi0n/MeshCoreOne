package one.meshcore.shared.contact

import kotlin.test.Test
import kotlin.test.assertEquals

class PathAccessibilityTest {
    private val copy = PathAccessibilityCopy(
        floodLabel = "Route: Flood",
        directLabel = "Route: Direct",
        prefixTemplate = "Route: %s",
    )

    @Test
    fun floodUsesTheFloodLabel() {
        assertEquals(
            "Route: Flood",
            pathAccessibilityLabel(
                isFloodRouted = true,
                pathHopCount = 3,
                pathDisplay = "Alpha → Beta",
                copy = copy,
            ),
        )
    }

    @Test
    fun zeroHopUsesTheDirectLabel() {
        assertEquals(
            "Route: Direct",
            pathAccessibilityLabel(
                isFloodRouted = false,
                pathHopCount = 0,
                pathDisplay = "",
                copy = copy,
            ),
        )
    }

    @Test
    fun populatedPathUsesThePrefixTemplate() {
        assertEquals(
            "Route: Alpha → Beta",
            pathAccessibilityLabel(
                isFloodRouted = false,
                pathHopCount = 2,
                pathDisplay = "Alpha → Beta",
                copy = copy,
            ),
        )
    }
}
