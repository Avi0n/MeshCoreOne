package one.meshcore.shared.chat.ui

import one.meshcore.shared.chat.translation.MessageTranslationPhase

/** Fill of the message bubble. Failed outgoing uses the error colors; the rest follow direction. */
internal enum class BubbleFillRole {
    FAILED,
    OUTGOING,
    INCOMING,
}

/** Direction, sender chrome, and sibling indent for one timeline row. */
internal data class ChatBubblePlacement(
    val outgoing: Boolean,
    val showSenderAbove: Boolean,
    val showIncomingAvatarColumn: Boolean,
    val fill: BubbleFillRole,
    val siblingIndentDp: Int,
)

/**
 * Channel rows reserve the incoming avatar column even when the sender label is hidden.
 * Direct messages and outgoing rows do not. A failed outgoing send is the only error fill.
 */
internal fun chatBubblePlacement(
    outgoing: Boolean,
    isChannelMessage: Boolean,
    senderName: String,
    showSenderName: Boolean,
    hasFailed: Boolean,
): ChatBubblePlacement {
    val incomingChannel = !outgoing && isChannelMessage
    val fill = when {
        outgoing && hasFailed -> BubbleFillRole.FAILED
        outgoing -> BubbleFillRole.OUTGOING
        else -> BubbleFillRole.INCOMING
    }
    return ChatBubblePlacement(
        outgoing = outgoing,
        showSenderAbove = showSenderName && incomingChannel && senderName.isNotEmpty(),
        showIncomingAvatarColumn = incomingChannel,
        fill = fill,
        siblingIndentDp = if (incomingChannel) INCOMING_AVATAR_COLUMN_WIDTH_DP else 0,
    )
}

/** How the composed accessibility phrase treats the translation control. */
internal enum class TranslationSpokenKind {
    TRANSLATED,
    IN_PROGRESS,
    ORIGINAL,
}

/** Showing speaks the translation, in-progress appends a status, and every other phase keeps the body. */
internal fun translationSpokenKind(phase: MessageTranslationPhase?): TranslationSpokenKind = when (phase) {
    is MessageTranslationPhase.Showing -> TranslationSpokenKind.TRANSLATED
    MessageTranslationPhase.InProgress -> TranslationSpokenKind.IN_PROGRESS
    else -> TranslationSpokenKind.ORIGINAL
}

/** Spoken flood-region caption. A missing region stays silent even when several names matched. */
internal enum class RegionSpokenKind {
    NONE,
    AMBIGUOUS,
    NAMED,
}

internal fun regionSpokenKind(region: String?, matchCount: Int): RegionSpokenKind = when {
    region == null -> RegionSpokenKind.NONE
    footerCaptionIsInteractive(matchCount) -> RegionSpokenKind.AMBIGUOUS
    else -> RegionSpokenKind.NAMED
}
