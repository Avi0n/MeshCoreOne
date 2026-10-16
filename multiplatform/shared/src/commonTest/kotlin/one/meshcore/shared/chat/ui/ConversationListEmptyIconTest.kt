package one.meshcore.shared.chat.ui

import one.meshcore.shared.chat.ChatFilter
import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationListEmptyIconTest {
    @Test
    fun emptyIconsMatchIosChatFilterGlyphs() {
        assertEquals(AppIcons.Chat, ChatFilter.ALL.emptyStateIcon())
        assertEquals(AppIcons.CheckCircle, ChatFilter.UNREAD.emptyStateIcon())
        assertEquals(AppIcons.Person, ChatFilter.DIRECT_MESSAGES.emptyStateIcon())
        assertEquals(AppIcons.Tag, ChatFilter.CHANNELS.emptyStateIcon())
        assertEquals(AppIcons.MeetingRoom, ChatFilter.ROOMS.emptyStateIcon())
    }
}
