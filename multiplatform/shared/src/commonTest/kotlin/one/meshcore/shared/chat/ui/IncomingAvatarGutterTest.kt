package one.meshcore.shared.chat.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** iOS `IncomingBubbleAvatarMetrics.columnWidth` is size + gap. */
class IncomingAvatarGutterTest {
    @Test
    fun columnWidthIsSizePlusGap() {
        assertEquals(28, INCOMING_AVATAR_SIZE_DP)
        assertEquals(6, INCOMING_AVATAR_GAP_DP)
        assertEquals(34, INCOMING_AVATAR_COLUMN_WIDTH_DP)
    }
}
