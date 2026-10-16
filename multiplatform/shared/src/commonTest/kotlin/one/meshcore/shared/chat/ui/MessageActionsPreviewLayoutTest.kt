package one.meshcore.shared.chat.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageActionsPreviewLayoutTest {
    @Test
    fun accessibilityDynamicTypeMatchesIosAx1() {
        assertFalse(isAccessibilityDynamicType(1.0f))
        assertFalse(isAccessibilityDynamicType(1.59f))
        assertTrue(isAccessibilityDynamicType(1.6f))
        assertTrue(isAccessibilityDynamicType(2.0f))
    }

    @Test
    fun pathDetailsScrollDistanceIsTopOffsetForShortAndTallBlocks() {
        val spec = PathDetailsBringIntoViewSpec(reduceMotion = true)
        assertEquals(120f, spec.calculateScrollDistance(offset = 120f, size = 80f, containerSize = 400f))
        assertEquals(120f, spec.calculateScrollDistance(offset = 120f, size = 900f, containerSize = 400f))
    }

    @Test
    fun skipPartialExpandMatchesIosRegularOrAccessibility() {
        assertFalse(shouldSkipMessageActionsPartialExpand(isAccessibilitySize = false, containerWidthDp = 599f))
        assertTrue(shouldSkipMessageActionsPartialExpand(isAccessibilitySize = false, containerWidthDp = 600f))
        assertTrue(shouldSkipMessageActionsPartialExpand(isAccessibilitySize = true, containerWidthDp = 390f))
    }

    @Test
    fun previewLineLimitGrowsAtAccessibilitySize() {
        assertEquals(1, messageActionsPreviewLineLimit(isAccessibilitySize = false))
        assertEquals(2, messageActionsPreviewLineLimit(isAccessibilitySize = true))
    }

    @Test
    fun previewMetaStacksWhenTheRowDoesNotFit() {
        assertFalse(shouldStackMessageActionsPreview(probeWidthPx = 200, availableWidthPx = 200))
        assertTrue(shouldStackMessageActionsPreview(probeWidthPx = 201, availableWidthPx = 200))
    }
}
