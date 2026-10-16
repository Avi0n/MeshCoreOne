package one.meshcore.shared.channel

import one.meshcore.shared.chat.ChatFixtures
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelDisplayNameTest {
    @Test
    fun emptyNameFallsBackToFormattedIndex() {
        assertEquals("Channel 3", channelDisplayName("", 3) { "Channel $it" })
        assertEquals("Ops", channelDisplayName("Ops", 3) { "Channel $it" })
    }

    @Test
    fun dtoUsesTheSameFallback() {
        val unnamed = ChatFixtures.channel(name = "", index = 7)
        assertEquals("Channel 7", unnamed.displayName { "Channel $it" })
        assertEquals("Ops", unnamed.copy(name = "Ops").displayName { "Channel $it" })
    }
}
