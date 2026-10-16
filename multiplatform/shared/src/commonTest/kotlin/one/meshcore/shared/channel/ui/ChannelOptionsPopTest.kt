package one.meshcore.shared.channel.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelOptionsPopTest {
    @Test
    fun systemBackPopsCreateJoinScanToTheOptionsHub() {
        assertEquals(ChannelOptionsPop.HUB, channelOptionsPopChild(hasSelectedOption = true))
    }

    @Test
    fun systemBackOnTheOptionsHubDismissesTheSheet() {
        assertEquals(ChannelOptionsPop.SHEET, channelOptionsPopChild(hasSelectedOption = false))
    }
}
