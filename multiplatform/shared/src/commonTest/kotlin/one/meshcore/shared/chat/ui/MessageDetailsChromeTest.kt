package one.meshcore.shared.chat.ui

import kotlin.test.Test
import kotlin.test.assertTrue

class MessageDetailsChromeTest {
    @Test
    fun detailsIconIsSmallerThanAListItemLeading() {
        assertTrue(ACTION_INFO_ICON_DP < 24)
    }
}
