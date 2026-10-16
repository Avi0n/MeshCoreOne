package one.meshcore.shared.chat.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class NewChatEmptyIconTest {
    @Test
    fun emptyUsesPersonTwoAnalog() {
        assertEquals(AppIcons.Group, NEW_CHAT_EMPTY_ICON)
    }
}
