package one.meshcore.shared.room.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import one.meshcore.shared.chat.translation.MessageTranslationPhase
import one.meshcore.shared.model.MessageStatus

/** Failed outgoing sends use the error tone. Other outgoing sends use the primary tone. */
internal enum class RoomBubbleTone {
    FAILED,
    OUTGOING,
    INCOMING,
}

/** Labels and send state the row has already resolved for its custom actions. */
internal data class RoomMessageActionInput(
    val phase: MessageTranslationPhase?,
    val outgoing: Boolean,
    val status: MessageStatus?,
    val translateLabel: String,
    val showOriginalLabel: String,
    val retryLabel: String,
)

/** Outgoing room messages sit on the trailing edge. Incoming messages sit on the leading edge. */
internal fun roomMessageHorizontalAlignment(outgoing: Boolean): Alignment.Horizontal =
    if (outgoing) Alignment.End else Alignment.Start

/** The row groups the avatar and bubble toward the same edge as the message. */
internal fun roomMessageHorizontalArrangement(outgoing: Boolean): Arrangement.Horizontal =
    if (outgoing) Arrangement.End else Arrangement.Start

/** Only an incoming row with a sender name shows that name above the bubble. */
internal fun roomMessageShowsAuthor(outgoing: Boolean, showSenderName: Boolean, author: String): Boolean =
    !outgoing && showSenderName && author.isNotEmpty()

/**
 * A showing translation speaks the line the screen already formatted. In-progress speech is the
 * original plus the progress suffix. Every other phase speaks the original text.
 */
internal fun roomSpokenBody(
    phase: MessageTranslationPhase?,
    original: String,
    translatedLine: String,
    inProgressLine: String,
): String = when (phase) {
    is MessageTranslationPhase.Showing -> translatedLine
    MessageTranslationPhase.InProgress -> inProgressLine
    else -> original
}

/**
 * Offer and showing each add one translation action. A failed outgoing send also adds retry.
 * Link actions stay after those, in the order the row already built them.
 */
internal fun roomMessageCustomActions(
    input: RoomMessageActionInput,
    linkActions: List<CustomAccessibilityAction>,
    onTranslate: () -> Unit,
    onRetry: () -> Unit,
): List<CustomAccessibilityAction> = buildList {
    when (input.phase) {
        MessageTranslationPhase.Offer ->
            add(
                CustomAccessibilityAction(input.translateLabel) {
                    onTranslate()
                    true
                },
            )
        is MessageTranslationPhase.Showing ->
            add(
                CustomAccessibilityAction(input.showOriginalLabel) {
                    onTranslate()
                    true
                },
            )
        else -> Unit
    }
    if (input.outgoing && input.status == MessageStatus.FAILED) {
        add(
            CustomAccessibilityAction(input.retryLabel) {
                onRetry()
                true
            },
        )
    }
    addAll(linkActions)
}

internal fun roomBubbleTone(outgoing: Boolean, status: MessageStatus?): RoomBubbleTone = when {
    outgoing && status == MessageStatus.FAILED -> RoomBubbleTone.FAILED
    outgoing -> RoomBubbleTone.OUTGOING
    else -> RoomBubbleTone.INCOMING
}

@Composable
internal fun roomBubbleColor(tone: RoomBubbleTone): Color = when (tone) {
    RoomBubbleTone.FAILED -> MaterialTheme.colorScheme.errorContainer
    RoomBubbleTone.OUTGOING -> MaterialTheme.colorScheme.primaryContainer
    RoomBubbleTone.INCOMING -> MaterialTheme.colorScheme.surfaceVariant
}
