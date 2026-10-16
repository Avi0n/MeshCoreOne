package one.meshcore.shared.chat.ui

import one.meshcore.shared.chat.translation.MessageTranslationPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatBubblePlacementTest {
    @Test
    fun failedOutgoingUsesTheErrorFill() {
        val placement = chatBubblePlacement(
            outgoing = true,
            isChannelMessage = true,
            senderName = "Ada",
            showSenderName = true,
            hasFailed = true,
        )
        assertEquals(BubbleFillRole.FAILED, placement.fill)
        assertTrue(placement.outgoing)
        assertFalse(placement.showSenderAbove)
        assertFalse(placement.showIncomingAvatarColumn)
        assertEquals(0, placement.siblingIndentDp)
    }

    @Test
    fun outgoingSuccessUsesThePrimaryFill() {
        val placement = chatBubblePlacement(
            outgoing = true,
            isChannelMessage = false,
            senderName = "",
            showSenderName = false,
            hasFailed = false,
        )
        assertEquals(BubbleFillRole.OUTGOING, placement.fill)
        assertEquals(0, placement.siblingIndentDp)
    }

    @Test
    fun namedIncomingChannelReservesTheAvatarColumn() {
        val placement = chatBubblePlacement(
            outgoing = false,
            isChannelMessage = true,
            senderName = "Ada",
            showSenderName = true,
            hasFailed = false,
        )
        assertEquals(BubbleFillRole.INCOMING, placement.fill)
        assertTrue(placement.showSenderAbove)
        assertTrue(placement.showIncomingAvatarColumn)
        assertEquals(INCOMING_AVATAR_COLUMN_WIDTH_DP, placement.siblingIndentDp)
    }

    @Test
    fun blankChannelSenderKeepsTheColumnWithoutALabel() {
        val placement = chatBubblePlacement(
            outgoing = false,
            isChannelMessage = true,
            senderName = "",
            showSenderName = true,
            hasFailed = false,
        )
        assertFalse(placement.showSenderAbove)
        assertTrue(placement.showIncomingAvatarColumn)
    }

    @Test
    fun hiddenGroupingOmitsTheSenderLabel() {
        val placement = chatBubblePlacement(
            outgoing = false,
            isChannelMessage = true,
            senderName = "Ada",
            showSenderName = false,
            hasFailed = false,
        )
        assertFalse(placement.showSenderAbove)
        assertTrue(placement.showIncomingAvatarColumn)
    }

    @Test
    fun directMessageHidesTheSenderAndAvatarColumn() {
        val placement = chatBubblePlacement(
            outgoing = false,
            isChannelMessage = false,
            senderName = "Ada",
            showSenderName = true,
            hasFailed = false,
        )
        assertEquals(BubbleFillRole.INCOMING, placement.fill)
        assertFalse(placement.showSenderAbove)
        assertFalse(placement.showIncomingAvatarColumn)
        assertEquals(0, placement.siblingIndentDp)
    }

    @Test
    fun translationSpokenKindFollowsThePhase() {
        assertEquals(TranslationSpokenKind.ORIGINAL, translationSpokenKind(null))
        assertEquals(TranslationSpokenKind.ORIGINAL, translationSpokenKind(MessageTranslationPhase.Offer))
        assertEquals(TranslationSpokenKind.IN_PROGRESS, translationSpokenKind(MessageTranslationPhase.InProgress))
        assertEquals(
            TranslationSpokenKind.TRANSLATED,
            translationSpokenKind(MessageTranslationPhase.Showing("hola", "es")),
        )
    }

    @Test
    fun regionSpokenKindStaysSilentWhenTheRegionIsMissing() {
        assertEquals(RegionSpokenKind.NONE, regionSpokenKind(null, matchCount = 3))
        assertEquals(RegionSpokenKind.NAMED, regionSpokenKind("Metro", matchCount = 1))
        assertEquals(RegionSpokenKind.NAMED, regionSpokenKind("Metro", matchCount = 0))
        assertEquals(RegionSpokenKind.AMBIGUOUS, regionSpokenKind("Metro", matchCount = 2))
    }
}
