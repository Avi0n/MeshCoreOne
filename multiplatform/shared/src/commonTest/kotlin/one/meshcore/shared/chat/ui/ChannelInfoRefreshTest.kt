package one.meshcore.shared.chat.ui

import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.ChannelFloodScopeStorage
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelInfoRefreshTest {
    @Test
    fun `a fetched channel replaces the open-thread snapshot`() {
        val open = ChatFixtures.channel(name = "Ops")
        val fetched = open.copy(
            floodScopeModeRawValue = ChannelFloodScopeStorage.MODE_SPECIFIC,
            regionScope = "EU433",
        )
        assertEquals(fetched, channelAfterInfoDismiss(open, fetched))
        assertEquals(ChannelFloodScope.Region("EU433"), fetched.floodScope)
    }

    @Test
    fun `a fetch miss keeps the previous snapshot`() {
        val open = ChatFixtures.channel(name = "Ops")
        assertEquals(open, channelAfterInfoDismiss(open, fetched = null))
    }

    @Test
    fun threadHeaderTitlePrefersLiveContactThenChannelThenFallback() {
        val named = ChatFixtures.contact(name = "Alice")
        assertEquals("Alice", threadHeaderTitle(named, null, "fallback", ::unnamedChannelName))
        assertEquals("Al", threadHeaderTitle(named.copy(nickname = "Al"), null, "fallback", ::unnamedChannelName))
        val channel = ChatFixtures.channel(name = "Ops")
        assertEquals("Ops", threadHeaderTitle(null, channel, "fallback", ::unnamedChannelName))
        assertEquals(
            "Renamed",
            threadHeaderTitle(null, channel.copy(name = "Renamed"), "fallback", ::unnamedChannelName),
        )
        assertEquals(
            "Canal 2",
            threadHeaderTitle(null, channel.copy(name = "", index = 2), "fallback") {
                "Canal $it"
            },
        )
        assertEquals("fallback", threadHeaderTitle(null, null, "fallback", ::unnamedChannelName))
    }
}

private fun unnamedChannelName(index: Int): String = "Canal $index"
