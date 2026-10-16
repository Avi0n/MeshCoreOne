package one.meshcore.shared.chat.ui

import one.meshcore.shared.chat.MessageFooter
import one.meshcore.shared.chat.text.MentionUtilities
import one.meshcore.shared.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the pure outgoing-footer ordering, incoming hop/path/region chip order, the GIF autoplay gate,
 * chat bubble parity fixes. The composables read these directly, so a drift from the iOS layout semantics
 * (status glyph grouped inside the bubble; mention vs quote reply) fails here without a device.
 */
class ChatBubbleFooterTest {
    private fun footer(
        heardRepeats: Int = 0,
        sendCount: Int = 1,
        status: MessageStatus = MessageStatus.SENT,
        sendTime: Double? = SEND_TIME,
    ) = MessageFooter(
        showStatusRow = true,
        status = status,
        isChannelMessage = false,
        heardRepeats = heardRepeats,
        retryAttempt = 0,
        maxRetryAttempts = 0,
        sendCount = sendCount,
        sendTimeToShowEpochSeconds = sendTime,
        sendTimeWasCorrected = false,
        showHop = false,
        hopCount = 0,
        formattedPath = null,
        regionToShow = null,
    )

    @Test
    fun statusGlyphAlwaysTrailsTheOutgoingFooter() {
        val segments = outgoingFooterSegments(footer())
        assertEquals(OutgoingFooterSegment.STATUS, segments.last())
    }

    @Test
    fun sendTimeShowsOnEveryBubbleAndDropsOnlyWhenAbsent() {
        assertTrue(OutgoingFooterSegment.TIME in outgoingFooterSegments(footer()))
        assertEquals(listOf(OutgoingFooterSegment.STATUS), outgoingFooterSegments(footer(sendTime = null)))
    }

    @Test
    fun fullFooterOrdersBadgesThenTimeThenStatus() {
        val segments = outgoingFooterSegments(footer(heardRepeats = 3, sendCount = 2))
        assertEquals(
            listOf(
                OutgoingFooterSegment.SEND_COUNT,
                OutgoingFooterSegment.HEARD,
                OutgoingFooterSegment.TIME,
                OutgoingFooterSegment.STATUS,
            ),
            segments,
        )
    }

    @Test
    fun sendCountBadgeHiddenForASingleSend() {
        assertFalse(OutgoingFooterSegment.SEND_COUNT in outgoingFooterSegments(footer(sendCount = 1)))
        assertTrue(OutgoingFooterSegment.SEND_COUNT in outgoingFooterSegments(footer(sendCount = 2)))
    }

    @Test
    fun heardBadgeHiddenWhenNoRepeatsHeard() {
        assertFalse(OutgoingFooterSegment.HEARD in outgoingFooterSegments(footer(heardRepeats = 0)))
        assertTrue(OutgoingFooterSegment.HEARD in outgoingFooterSegments(footer(heardRepeats = 1)))
    }

    @Test
    fun outgoingSendCountChipUsesMultiplierSuffix() {
        assertEquals("2x", outgoingSendCountChipText(2))
        assertEquals("3x", outgoingSendCountChipText(3))
    }

    @Test
    fun outgoingHeardChipUsesSingularOnlyForOneRepeat() {
        assertTrue(outgoingHeardUsesSingular(1))
        assertFalse(outgoingHeardUsesSingular(2))
    }

    @Test
    fun incomingPlacesTimeFirstOnTheSameRowAsChips() {
        val footer = incomingFooter(
            showHop = true,
            hopCount = 0,
            formattedPath = "A > B",
            regionToShow = "on",
        )
        assertEquals(
            listOf(
                IncomingFooterSegment.TIME,
                IncomingFooterSegment.HOP,
                IncomingFooterSegment.PATH,
                IncomingFooterSegment.REGION,
            ),
            incomingFooterSegments(footer),
        )
    }

    @Test
    fun incomingTimeOnlyFooterIsJustTime() {
        assertEquals(
            listOf(IncomingFooterSegment.TIME),
            incomingFooterSegments(incomingFooter()),
        )
    }

    @Test
    fun incomingOmitsFooterWhenTimeAndChipsAreAbsent() {
        val footer = incomingFooter(
            showHop = false,
            formattedPath = null,
            regionToShow = null,
            sendTime = null,
        )
        assertEquals(emptyList(), incomingFooterSegments(footer))
    }

    @Test
    fun incomingHopChipShowsWheneverShowHopEvenAtZeroHops() {
        assertTrue(IncomingFooterSegment.HOP in incomingFooterSegments(incomingFooter(showHop = true, hopCount = 0)))
        assertFalse(IncomingFooterSegment.HOP in incomingFooterSegments(incomingFooter(showHop = false, hopCount = 3)))
    }

    @Test
    fun incomingOmitsEmptyPathRegionAndHeard() {
        val footer = incomingFooter(
            showHop = false,
            hopCount = 2,
            formattedPath = "",
            regionToShow = null,
            heardRepeats = 4,
        )
        assertEquals(listOf(IncomingFooterSegment.TIME), incomingFooterSegments(footer))
        assertFalse(IncomingFooterSegment.HOP in incomingFooterSegments(footer))
        assertFalse(IncomingFooterSegment.PATH in incomingFooterSegments(footer))
        assertFalse(IncomingFooterSegment.REGION in incomingFooterSegments(footer))
    }

    @Test
    fun gifAutoPlaysOnlyWhenPreferenceOnAndMotionNotReduced() {
        assertTrue(shouldAutoPlayGif(autoPlayGIFs = true, reduceMotion = false))
        assertFalse(shouldAutoPlayGif(autoPlayGIFs = true, reduceMotion = true))
        assertFalse(shouldAutoPlayGif(autoPlayGIFs = false, reduceMotion = false))
        assertFalse(shouldAutoPlayGif(autoPlayGIFs = false, reduceMotion = true))
    }

    @Test
    fun defaultReplyAppendsAMentionAndKeepsTypedText() {
        val draft = "on my way"
        val result = MentionUtilities.appendMention(name = "Alice", draft = draft)
        assertTrue(result.startsWith(draft), "typed composer text must be preserved")
        assertTrue(result.contains(MentionUtilities.createMention("Alice")))
    }

    @Test
    fun quotedReplyReplacesWithAQuotedPreview() {
        val result = MentionUtilities.buildReplyText(mentionName = "Alice", messageText = "hello there friend")
        assertTrue(result.startsWith(MentionUtilities.createMention("Alice")))
        assertTrue(result.contains(">"), "quoted reply carries a quote marker")
    }

    private fun incomingFooter(
        showHop: Boolean = false,
        hopCount: Int = 0,
        formattedPath: String? = null,
        regionToShow: String? = null,
        heardRepeats: Int = 0,
        sendTime: Double? = SEND_TIME,
    ) = MessageFooter(
        showStatusRow = false,
        status = MessageStatus.SENT,
        isChannelMessage = true,
        heardRepeats = heardRepeats,
        retryAttempt = 0,
        maxRetryAttempts = 0,
        sendCount = 1,
        sendTimeToShowEpochSeconds = sendTime,
        sendTimeWasCorrected = false,
        showHop = showHop,
        hopCount = hopCount,
        formattedPath = formattedPath,
        regionToShow = regionToShow,
    )

    private companion object {
        const val SEND_TIME = 1_700_000_000.0
    }
}
