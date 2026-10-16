package one.meshcore.shared.chat.ui

import one.meshcore.shared.ui.filterBarVisible
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Search hides the chip row instead of cloning iOS `GlassFilterBar` dimming. */
class ConversationListFilterBarTest {
    @Test
    fun searchingHidesTheFilterBar() {
        assertFalse(filterBarVisible(isSearching = true))
        assertTrue(filterBarVisible(isSearching = false))
    }
}
