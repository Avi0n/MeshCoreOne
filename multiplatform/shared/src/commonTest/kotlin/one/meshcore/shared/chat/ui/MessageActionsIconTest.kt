package one.meshcore.shared.chat.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageActionsIconTest {
    @Test
    fun sendAgainUsesUTurnForwardGlyph() {
        assertEquals(AppIcons.Redo, SEND_AGAIN_ICON)
    }

    @Test
    fun sendDmUsesTwoBubbleGlyph() {
        assertEquals(AppIcons.Forum, SEND_DM_ICON)
    }

    @Test
    fun blockSenderUsesRaisedHandGlyph() {
        assertEquals(AppIcons.FrontHand, BLOCK_SENDER_ICON)
    }
}
