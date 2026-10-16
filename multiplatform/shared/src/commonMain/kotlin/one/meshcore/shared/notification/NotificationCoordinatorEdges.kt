package one.meshcore.shared.notification

import one.meshcore.shared.settings.PersistentLogger

/** Preference, authorization, badge, and log seams. Defaults post every message and skip the badge. */
data class NotificationCoordinatorEdges(
    val preferences: NotificationPreferencesProvider = NotificationPreferencesProvider.AllEnabled,
    val drafts: QuickReplyDraftStore = QuickReplyDraftStore(),
    val isAuthorized: () -> Boolean = { true },
    val unreadCounts: suspend () -> UnreadCounts = { UnreadCounts.Zero },
    val onConversationsChanged: suspend () -> Unit = {},
    val diagnostic: (String) -> Unit = {},
    val logger: PersistentLogger? = null,
)
