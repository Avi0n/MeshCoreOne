package one.meshcore.shared.room.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomConversationMissingSessionChromeTest {
    @Test
    fun missingSessionUsesTheRoomGlyph() {
        assertEquals(AppIcons.Forum, ROOM_CONVERSATION_MISSING_ICON)
    }
}
