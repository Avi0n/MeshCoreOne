package one.meshcore.shared.chat.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class ShareContactEmptyIconTest {
    @Test
    fun emptyUsesPersonTwoAnalog() {
        assertEquals(AppIcons.Group, SHARE_CONTACT_EMPTY_ICON)
    }
}
