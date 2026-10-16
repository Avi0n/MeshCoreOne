package one.meshcore.shared.chat

import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.RouteType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatMessageActionDetailsTest {
    @Test
    fun outgoingShowsSentAndOptionalRoundTripAndHeardRepeats() {
        val bare = ChatFixtures.message(direction = MessageDirection.OUTGOING)
        val bareLines = ChatMessageActionDetails.lines(bare)
        assertEquals(1, bareLines.size)
        assertIs<ChatMessageActionDetails.Line.Sent>(bareLines[0])

        val withExtras = ChatFixtures.message(direction = MessageDirection.OUTGOING).copy(
            roundTripTime = 500,
            heardRepeats = 2,
        )
        val lines = ChatMessageActionDetails.lines(withExtras)
        assertEquals(3, lines.size)
        assertIs<ChatMessageActionDetails.Line.Sent>(lines[0])
        val rtt = assertIs<ChatMessageActionDetails.Line.RoundTrip>(lines[1])
        assertEquals(500L, rtt.milliseconds)
        val heard = assertIs<ChatMessageActionDetails.Line.HeardRepeats>(lines[2])
        assertEquals(2, heard.count)
    }

    @Test
    fun incomingAlwaysShowsHopsSentAndReceived() {
        val message = ChatFixtures.message(direction = MessageDirection.INCOMING)
        val lines = ChatMessageActionDetails.lines(message)
        assertIs<ChatMessageActionDetails.Line.Hops>(lines.first())
        assertTrue(lines.any { it is ChatMessageActionDetails.Line.Sent })
        assertTrue(lines.any { it is ChatMessageActionDetails.Line.Received })
    }

    @Test
    fun incomingDirectRoutedHopsLineFlagsDirect() {
        val message = ChatFixtures.message(direction = MessageDirection.INCOMING).copy(
            routeType = RouteType.DIRECT,
        )
        val hops = assertIs<ChatMessageActionDetails.Line.Hops>(ChatMessageActionDetails.lines(message).first())
        assertTrue(hops.isDirect)
    }

    @Test
    fun incomingTcFloodShowsFloodRegionLine() {
        val message = ChatFixtures.message(direction = MessageDirection.INCOMING).copy(
            routeType = RouteType.TC_FLOOD,
            regionScope = "US915",
        )
        val lines = ChatMessageActionDetails.lines(message)
        val region = lines.filterIsInstance<ChatMessageActionDetails.Line.FloodRegion>().single()
        assertEquals(RegionMatchResult.Unique("US915"), region.match)
    }

    @Test
    fun incomingTcFloodPrefersAmbiguousMatchesOverStickyScope() {
        val message = ChatFixtures.message(direction = MessageDirection.INCOMING).copy(
            routeType = RouteType.TC_FLOOD,
            regionScope = "Germany",
            regionScopeMatches = listOf("de-hh", "de-by"),
        )
        val region = ChatMessageActionDetails.lines(message)
            .filterIsInstance<ChatMessageActionDetails.Line.FloodRegion>()
            .single()
        assertEquals(RegionMatchResult.Ambiguous(listOf("de-by", "de-hh")), region.match)
    }

    @Test
    fun incomingNonTcFloodOmitsFloodRegionLine() {
        val message = ChatFixtures.message(direction = MessageDirection.INCOMING).copy(
            routeType = RouteType.FLOOD,
            regionScope = "US915",
        )
        val lines = ChatMessageActionDetails.lines(message)
        assertTrue(lines.none { it is ChatMessageActionDetails.Line.FloodRegion })
    }

    @Test
    fun incomingCorrectedTimestampAddsOriginalSendTimeLine() {
        val message = ChatFixtures.message(
            direction = MessageDirection.INCOMING,
            timestampCorrected = true,
            senderTimestamp = 1_650_000_000,
        ).copy(timestamp = 1_700_000_000)
        val lines = ChatMessageActionDetails.lines(message)
        val sent = lines.filterIsInstance<ChatMessageActionDetails.Line.Sent>().single()
        assertTrue(sent.adjusted)
        val original = lines.filterIsInstance<ChatMessageActionDetails.Line.OriginalSendTime>().single()
        assertEquals(1_650_000_000.0, original.epochSeconds)
    }

    @Test
    fun incomingUncorrectedTimestampOmitsOriginalSendTimeLine() {
        val message = ChatFixtures.message(direction = MessageDirection.INCOMING, timestampCorrected = false)
        val lines = ChatMessageActionDetails.lines(message)
        assertFalse(lines.any { it is ChatMessageActionDetails.Line.OriginalSendTime })
    }

    @Test
    fun incomingSnrPresentAddsSnrLine() {
        val message = ChatFixtures.message(direction = MessageDirection.INCOMING).copy(snr = 4.5)
        val lines = ChatMessageActionDetails.lines(message)
        val snr = lines.filterIsInstance<ChatMessageActionDetails.Line.Snr>().single()
        assertEquals(4.5, snr.decibels)
    }
}
