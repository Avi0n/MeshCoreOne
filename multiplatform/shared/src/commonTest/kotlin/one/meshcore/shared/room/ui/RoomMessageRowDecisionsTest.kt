package one.meshcore.shared.room.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.CustomAccessibilityAction
import one.meshcore.shared.chat.translation.MessageTranslationPhase
import one.meshcore.shared.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomMessageRowDecisionsTest {
    @Test
    fun outgoingSitsOnTheEndAndIncomingOnTheStart() {
        assertEquals(Alignment.End, roomMessageHorizontalAlignment(outgoing = true))
        assertEquals(Alignment.Start, roomMessageHorizontalAlignment(outgoing = false))
        assertEquals(Arrangement.End, roomMessageHorizontalArrangement(outgoing = true))
        assertEquals(Arrangement.Start, roomMessageHorizontalArrangement(outgoing = false))
    }

    @Test
    fun authorLineShowsOnlyForNamedIncoming() {
        assertTrue(roomMessageShowsAuthor(outgoing = false, showSenderName = true, author = "Alice"))
        assertFalse(roomMessageShowsAuthor(outgoing = true, showSenderName = true, author = "Alice"))
        assertFalse(roomMessageShowsAuthor(outgoing = false, showSenderName = false, author = "Alice"))
        assertFalse(roomMessageShowsAuthor(outgoing = false, showSenderName = true, author = ""))
    }

    @Test
    fun spokenBodyFollowsTranslationPhase() {
        val showing = MessageTranslationPhase.Showing(translatedText = "Hello everyone", targetLanguageCode = "en")
        assertEquals(
            "Translated: Hello everyone",
            roomSpokenBody(
                phase = showing,
                original = "Hola",
                translatedLine = "Translated: Hello everyone",
                inProgressLine = "Hola, Translating",
            ),
        )
        assertEquals(
            "Hola, Translating",
            roomSpokenBody(
                phase = MessageTranslationPhase.InProgress,
                original = "Hola",
                translatedLine = "Translated: Hello everyone",
                inProgressLine = "Hola, Translating",
            ),
        )
        assertEquals(
            "Hola",
            roomSpokenBody(
                phase = MessageTranslationPhase.Offer,
                original = "Hola",
                translatedLine = "Translated: Hello everyone",
                inProgressLine = "Hola, Translating",
            ),
        )
        assertEquals(
            "Hola",
            roomSpokenBody(
                phase = null,
                original = "Hola",
                translatedLine = "Translated: Hello everyone",
                inProgressLine = "Hola, Translating",
            ),
        )
    }

    @Test
    fun bubbleToneFollowsOutgoingFailure() {
        assertEquals(RoomBubbleTone.FAILED, roomBubbleTone(outgoing = true, status = MessageStatus.FAILED))
        assertEquals(RoomBubbleTone.OUTGOING, roomBubbleTone(outgoing = true, status = MessageStatus.SENT))
        assertEquals(RoomBubbleTone.OUTGOING, roomBubbleTone(outgoing = true, status = null))
        assertEquals(RoomBubbleTone.INCOMING, roomBubbleTone(outgoing = false, status = MessageStatus.FAILED))
        assertEquals(RoomBubbleTone.INCOMING, roomBubbleTone(outgoing = false, status = null))
    }

    @Test
    fun translationActionsFollowPhase() {
        var translated = false
        val offer = actions(phase = MessageTranslationPhase.Offer, onTranslate = { translated = true })
        assertEquals(listOf("Translate"), offer.map { it.label })
        assertTrue(offer.single().action())
        assertTrue(translated)

        var showedOriginal = false
        val showing = actions(
            phase = MessageTranslationPhase.Showing(translatedText = "Hello everyone", targetLanguageCode = "en"),
            onTranslate = { showedOriginal = true },
        )
        assertEquals(listOf("Show original"), showing.map { it.label })
        assertTrue(showing.single().action())
        assertTrue(showedOriginal)

        assertEquals(emptyList(), actions(phase = MessageTranslationPhase.InProgress).map { it.label })
        assertEquals(emptyList(), actions(phase = null).map { it.label })
    }

    @Test
    fun failedOutgoingAddsRetryAndIncomingFailureDoesNot() {
        var retried = false
        var translated = false
        val failed = actions(
            phase = MessageTranslationPhase.Offer,
            outgoing = true,
            status = MessageStatus.FAILED,
            onTranslate = { translated = true },
            onRetry = { retried = true },
        )
        assertEquals(listOf("Translate", "Retry"), failed.map { it.label })
        assertTrue(failed[1].action())
        assertTrue(retried)
        assertFalse(translated)

        val incoming = actions(
            phase = MessageTranslationPhase.Offer,
            outgoing = false,
            status = MessageStatus.FAILED,
        )
        assertEquals(listOf("Translate"), incoming.map { it.label })
        assertEquals(
            emptyList(),
            actions(phase = null, outgoing = true, status = MessageStatus.SENT).map { it.label },
        )
    }

    @Test
    fun linkActionsStayAfterTheRowActions() {
        val link = CustomAccessibilityAction("Open link") { true }
        val actions = roomMessageCustomActions(
            input = RoomMessageActionInput(
                phase = MessageTranslationPhase.Offer,
                outgoing = true,
                status = MessageStatus.FAILED,
                translateLabel = "Translate",
                showOriginalLabel = "Show original",
                retryLabel = "Retry",
            ),
            linkActions = listOf(link),
            onTranslate = {},
            onRetry = {},
        )
        assertEquals(listOf("Translate", "Retry", "Open link"), actions.map { it.label })
        assertTrue(actions.last().action())
    }

    private fun actions(
        phase: MessageTranslationPhase?,
        outgoing: Boolean = false,
        status: MessageStatus? = null,
        onTranslate: () -> Unit = {},
        onRetry: () -> Unit = {},
    ): List<CustomAccessibilityAction> = roomMessageCustomActions(
        input = RoomMessageActionInput(
            phase = phase,
            outgoing = outgoing,
            status = status,
            translateLabel = "Translate",
            showOriginalLabel = "Show original",
            retryLabel = "Retry",
        ),
        linkActions = emptyList(),
        onTranslate = onTranslate,
        onRetry = onRetry,
    )
}
