package one.meshcore.shared.chat

import one.meshcore.shared.chat.ui.IncomingFooterSegment
import one.meshcore.shared.chat.ui.incomingFooterSegments
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.RouteType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the W3 footer hop / path / region slots the builder fills in `makeFooter`: hop and region are
 * gated on flood routing, the formatted path passes through from the build inputs, and the toggles gate
 * each independently. Mirrors the Swift `MessageFragmentBuilder.makeFooter` hop/region/path additions.
 */
class FooterHopPathRegionTest {
    private fun env(showIncomingHopCount: Boolean = false, showIncomingRegion: Boolean = false): EnvInputs = EnvInputs(
        currentUserName = "Me",
        showIncomingSendTime = false,
        showIncomingHopCount = showIncomingHopCount,
        showIncomingRegion = showIncomingRegion,
    )

    private fun floodChannelMessage() = ChatFixtures.message(
        channelIndex = 0,
        direction = MessageDirection.INCOMING,
    ).copy(
        pathLength = 0x02, // mode 0, 2 hops
        regionScope = "EU433",
    )

    private fun directMessage() = ChatFixtures.message(channelIndex = null).copy(
        routeType = RouteType.DIRECT,
        pathLength = 0x02,
        regionScope = "EU433",
    )

    @Test
    fun hopShownForFloodRoutedMessageWhenToggleOn() {
        val item = MessageFragmentBuilder.makeItem(
            floodChannelMessage(),
            buildInputs(ChatFixtures.RADIO),
            env(showIncomingHopCount = true),
        )
        assertTrue(item.footer.showHop)
        assertEquals(2, item.footer.hopCount)
    }

    @Test
    fun hopHiddenForDirectRoutedMessageEvenWhenToggleOn() {
        val item = MessageFragmentBuilder.makeItem(
            directMessage(),
            buildInputs(ChatFixtures.RADIO),
            env(showIncomingHopCount = true),
        )
        assertFalse(item.footer.showHop)
        assertEquals(2, item.footer.hopCount) // count is still carried, only the flag is gated
    }

    @Test
    fun hopHiddenForOutgoingFloodEvenWhenToggleOn() {
        val item = MessageFragmentBuilder.makeItem(
            ChatFixtures.message(channelIndex = 0).copy(pathLength = 0x02),
            buildInputs(ChatFixtures.RADIO),
            env(showIncomingHopCount = true),
        )
        assertFalse(item.footer.showHop)
    }

    @Test
    fun hopHiddenWhenToggleOff() {
        val item = MessageFragmentBuilder.makeItem(
            floodChannelMessage(),
            buildInputs(ChatFixtures.RADIO),
            env(showIncomingHopCount = false),
        )
        assertFalse(item.footer.showHop)
    }

    @Test
    fun regionShownForFloodRoutedMessageWhenToggleOn() {
        val item = MessageFragmentBuilder.makeItem(
            floodChannelMessage(),
            buildInputs(ChatFixtures.RADIO),
            env(showIncomingRegion = true),
        )
        assertEquals("EU433", item.footer.regionToShow)
    }

    @Test
    fun regionHiddenForDirectRoutedMessage() {
        val item = MessageFragmentBuilder.makeItem(
            directMessage(),
            buildInputs(ChatFixtures.RADIO),
            env(showIncomingRegion = true),
        )
        assertNull(item.footer.regionToShow)
    }

    @Test
    fun regionHiddenWhenToggleOff() {
        val item = MessageFragmentBuilder.makeItem(
            floodChannelMessage(),
            buildInputs(ChatFixtures.RADIO),
            env(showIncomingRegion = false),
        )
        assertNull(item.footer.regionToShow)
    }

    @Test
    fun formattedPathPassesThroughFromInputs() {
        val inputs = buildInputs(ChatFixtures.RADIO).copy(formattedPath = "A3 → 7F")
        val item = MessageFragmentBuilder.makeItem(floodChannelMessage(), inputs, env())
        assertEquals("A3 → 7F", item.footer.formattedPath)
    }

    @Test
    fun incomingHeardCountRequiresOptInAndExtraArrival() {
        val message = floodChannelMessage().copy(heardRepeats = 2)
        val inputs = buildInputs(ChatFixtures.RADIO)
        val hidden = MessageFragmentBuilder.makeItem(message, inputs, env()).footer
        val shown = MessageFragmentBuilder.makeItem(message, inputs, env().copy(showIncomingHeardCount = true)).footer
        val firstOnly = MessageFragmentBuilder.makeItem(
            message.copy(heardRepeats = 0),
            inputs,
            env().copy(showIncomingHeardCount = true),
        ).footer
        val outgoing = MessageFragmentBuilder.makeItem(
            message.copy(direction = MessageDirection.OUTGOING),
            inputs,
            env().copy(showIncomingHeardCount = true),
        ).footer

        assertFalse(hidden.showHeardCount)
        assertFalse(IncomingFooterSegment.HEARD in incomingFooterSegments(hidden))
        assertTrue(shown.showHeardCount)
        assertEquals(3, shown.incomingHeardTotal)
        assertTrue(IncomingFooterSegment.HEARD in incomingFooterSegments(shown))
        assertFalse(firstOnly.showHeardCount)
        assertFalse(outgoing.showHeardCount)
    }

    @Test
    fun regionChipJoinsMultiMatchAndIgnoresStickyScope() {
        val item = MessageFragmentBuilder.makeItem(
            floodChannelMessage().copy(
                regionScope = "Germany",
                regionScopeMatches = listOf("de-hh", "de-by"),
            ),
            buildInputs(ChatFixtures.RADIO),
            env(showIncomingRegion = true),
        )
        assertEquals("de-by / de-hh", item.footer.regionToShow)
        assertEquals(listOf("de-by", "de-hh"), item.footer.regionMatchNames)
    }
}
