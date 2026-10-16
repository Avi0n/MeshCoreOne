package one.meshcore.shared.notification

/** Parks a quick-reply draft after a failed reply, and returns it when that conversation opens. */
suspend fun NotificationCoordinator.saveQuickReplyDraft(key: ConversationKey, text: String) =
    runtime.drafts.saveDraft(key, text)

suspend fun NotificationCoordinator.consumeQuickReplyDraft(
    key: ConversationKey,
    acceptsDraft: () -> Boolean = { true },
): String? = runtime.drafts.consumeDraft(key, acceptsDraft)

/**
 * Warns that a quick reply could not be sent and its text was parked as a draft. Authorization is the
 * only gate: iOS posts this regardless of the per-kind message toggles and the sync-suppression window,
 * because a silently dropped reply the user believes was sent is the failure this exists to prevent.
 */
suspend fun NotificationCoordinator.postQuickReplyFailed(key: ConversationKey, conversationName: String) {
    if (!runtime.isAuthorized()) return
    runtime.poster.postQuickReplyFailed(QuickReplyFailedNotification(key, conversationName))
}
