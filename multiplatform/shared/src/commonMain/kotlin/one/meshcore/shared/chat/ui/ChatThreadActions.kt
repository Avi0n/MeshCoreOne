package one.meshcore.shared.chat.ui

import one.meshcore.shared.model.Uuid

/**
 * The thread-screen intents, grouped into one holder so the [ChatThreadScreen] signature stays within the
 * convention's parameter bounds. Each callback maps to a view-model action the host wires.
 */
data class ChatThreadActions(
    val onTextChange: (String) -> Unit,
    val onSend: () -> Unit,
    val onNearTop: () -> Unit,
    val onNearBottomChanged: (Boolean) -> Unit,
    val onScrollToBottom: () -> Unit,
    /** Pins to the newest row only while already at the bottom (composer focus). */
    val onPinToBottom: () -> Unit = {},
    val onRetry: (Uuid) -> Unit,
    val onDelete: (Uuid) -> Unit,
    val onClearError: () -> Unit,
    val onClearSendError: () -> Unit,
    val onClearErrorBanner: () -> Unit = {},
    val onBack: () -> Unit,
    /** Inserts a quoted reply into the composer for [messageId]. */
    val onReply: (Uuid) -> Unit = {},
    /** Raises the composer now (share insert). Reply uses [ComposerViewModel.requestFocus]. */
    val onFocusComposer: () -> Unit = {},
    /** Appends a share token to the live compose field (iOS `insertShared`). */
    val onInsertShared: (String) -> Unit = {},
    /** Re-sends an outgoing message from the actions sheet (iOS Send Again), including failed rows. */
    val onSendAgain: (Uuid) -> Unit = {},
    /** Scrolls to the placed new-messages divider, if any (jump-to-divider affordance). */
    val onScrollToDivider: () -> Unit = {},
    /** Scrolls to [messageId], an unseen self-mention (scroll-to-mention affordance). */
    val onScrollToMention: (Uuid) -> Unit = {},
    /** Persists that the self-mention at [messageId] has scrolled into view. */
    val onMentionSeen: (Uuid) -> Unit = {},
    /** Surfaces a reaction-send fault (iOS `errorMessage = error.userFacingMessage`). */
    val onReactionFailed: (Throwable) -> Unit = {},
    /** Retires the one-shot open positioning after the list has shown the presented snapshot. */
    val onDividerTargetConsumed: () -> Unit = {},
)

/** Idle thread: back is live, and every send, scroll, and delete intent is a no-op. */
internal fun idleChatThreadActions(onBack: () -> Unit) = ChatThreadActions(
    onTextChange = {},
    onSend = {},
    onNearTop = {},
    onNearBottomChanged = {},
    onScrollToBottom = {},
    onRetry = {},
    onDelete = {},
    onClearError = {},
    onClearSendError = {},
    onBack = onBack,
)
