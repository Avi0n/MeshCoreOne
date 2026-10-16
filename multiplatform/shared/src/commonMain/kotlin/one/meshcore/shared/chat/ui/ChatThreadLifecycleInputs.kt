package one.meshcore.shared.chat.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.settings.TranslationTargetPreferencesStore

interface ChatThreadLifecycle {
    /**
     * Takes the text a notification quick reply parked when it could not send, or null when none is
     * parked. Reading consumes it, so the composer restores a parked reply exactly once.
     */
    val consumeQuickReplyDraft: suspend (ChatConversationID, () -> Boolean) -> String?

    /** Reloads the conversation list after mark-read, delete, or channel clear. */
    val onConversationsChanged: suspend () -> Unit

    /**
     * Additional open-view background pulses. The app owner also flushes retained editors with no
     * mounted presentation.
     */
    val appBackground: Flow<Unit>

    /**
     * In-bubble Translate target and default-app toggle. Null uses app locale and in-app Translate.
     */
    val translationTargetPreferences: TranslationTargetPreferencesStore?

    /**
     * Emits when an app-side contact mutation changed the table (iOS `contactsVersion`). The open
     * thread reloads mention candidates and sender names so a contact added after open is tappable.
     */
    val contactListInvalidations: Flow<Unit>

    /**
     * System Translation overlay when on-device ML Kit fails (iOS `.translationPresentation`).
     * No-op on hosts that have no overlay.
     */
    val presentSystemTranslation: (String) -> Unit

    /**
     * Bumped after a backup restore so an already-composed thread drops its pre-restore coordinator.
     * Mirrors iOS `servicesVersion` after `notifyDataRestored`.
     */
    val dataRestoredGeneration: StateFlow<Int>?

    /**
     * Per-slot generation iOS `channelSlotGenerations` bumps when a channel occupant changes. The
     * host retires the affected editor before bumping; the open thread leaves the vacated slot.
     */
    val channelSlotGenerations: StateFlow<Map<ChatConversationID, Int>>?
}

/** Draft restore, background flush, and the generations that drop a stale open thread. */
data class ChatThreadLifecycleInputs(
    override val consumeQuickReplyDraft: suspend (ChatConversationID, () -> Boolean) -> String? = { _, _ -> null },
    override val onConversationsChanged: suspend () -> Unit = {},
    override val appBackground: Flow<Unit> = emptyFlow(),
    override val translationTargetPreferences: TranslationTargetPreferencesStore? = null,
    override val contactListInvalidations: Flow<Unit> = emptyFlow(),
    override val presentSystemTranslation: (String) -> Unit = {},
    override val dataRestoredGeneration: StateFlow<Int>? = null,
    override val channelSlotGenerations: StateFlow<Map<ChatConversationID, Int>>? = null,
) : ChatThreadLifecycle
