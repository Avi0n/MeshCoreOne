package one.meshcore.shared.chat.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** iOS `SenderNameMetrics` + `IncomingBubbleAvatarMetrics.columnWidth` (28+6) for a channel name. */
class SenderNamePlacementTest {
    @Test
    fun channelSenderNameSitsAboveTheBubblePastTheAvatarColumn() {
        assertEquals(34, INCOMING_AVATAR_COLUMN_WIDTH_DP)
        assertEquals(38, senderNameAboveBubbleStartDp(reserveAvatarColumn = true))
        assertEquals(4, senderNameAboveBubbleStartDp(reserveAvatarColumn = false))
        assertEquals(1, senderNameAboveBubbleBottomDp())
        assertEquals(6, REACTION_OVERLAP_DP)
    }
}
