package one.meshcore.shared.room.ui

import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomReplyActionLabelTest {
    @Test
    fun actionsHeaderAuthorMatchesTheBubbleSenderLine() {
        assertEquals(FontWeight.SemiBold, ROOM_ACTIONS_AUTHOR_WEIGHT)
    }
}
