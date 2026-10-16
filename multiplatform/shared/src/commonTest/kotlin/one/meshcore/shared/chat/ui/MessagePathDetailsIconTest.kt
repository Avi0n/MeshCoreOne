package one.meshcore.shared.chat.ui

import one.meshcore.shared.chat.pathdetail.MessagePathDisplay
import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessagePathDetailsIconTest {
    @Test
    fun pathDetailsUsesRouteGlyphAndRepeatUsesBranch() {
        assertEquals(AppIcons.Route, PATH_DETAILS_ICON)
        assertEquals(AppIcons.Repeat, REPEAT_DETAILS_ICON)
    }

    @Test
    fun pathMapPresentsAfterActionsDismissWhenDisplayIsReady() {
        val display = MessagePathDisplay(
            available = true,
            rows = emptyList(),
            hasIntermediateHops = false,
            pathString = "",
            pathStringForClipboard = "",
        )
        assertFalse(shouldPresentPathMap(actionsSheetOpen = true, display = display))
        assertTrue(shouldPresentPathMap(actionsSheetOpen = false, display = display))
        assertFalse(shouldPresentPathMap(actionsSheetOpen = false, display = null))
        assertFalse(shouldPresentPathMap(actionsSheetOpen = true, display = null))
    }
}
